
package com.example.pip

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Intent
import android.os.Build
import android.os.IBinder
import android.os.SystemClock
import android.speech.tts.TextToSpeech
import android.util.Log
import androidx.core.app.NotificationCompat
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.util.Locale

class SmartCaneService : Service(), TextToSpeech.OnInitListener {

    private val serviceScope =
        CoroutineScope(SupervisorJob() + Dispatchers.Main)

    private var videoJob: Job? = null
    private var detectionJob: Job? = null
    private var retryJob: Job? = null
    private var healthJob: Job? = null

    private var isCheckingConnection = false

    @Volatile
    private var isPiConnected = false

    private var textToSpeech: TextToSpeech? = null

    @Volatile
    private var ttsReady = false

    private var lastAnnouncement = ""

    // Latency statistics
    private var minimumLatency = Double.POSITIVE_INFINITY
    private var maximumLatency = Double.NEGATIVE_INFINITY
    private var totalLatencySum = 0.0
    private var latencySampleCount = 0L

    // Prevents the same Pi camera frame from being sampled twice.
    private var lastSampledCaptureEpochNs = 0L

    // Camera FPS
    private var frameCount = 0
    private var lastFpsUpdate = System.currentTimeMillis()

    private var consecutiveFailures = 0

    // Connection audio state
    private var lastAnnouncedConnectionState: Boolean? = null

    @Volatile
    private var pendingConnectionAnnouncement: String? = null

    private var connectionUtteranceCounter = 0L

    override fun onCreate() {
        super.onCreate()

        createNotificationChannel()
        startForeground(NOTIFICATION_ID, createNotification())

        textToSpeech = TextToSpeech(this, this)

        resetStatistics(clearSamples = true)

        SmartCaneState.updateConnectionStatus(
            "Connecting to Raspberry Pi..."
        )

        connectToPi(PI_BASE_URL)
    }

    override fun onStartCommand(
        intent: Intent?,
        flags: Int,
        startId: Int
    ): Int = START_STICKY

    override fun onBind(intent: Intent?): IBinder? = null

    // ---------------- CONNECTION ----------------

    private fun connectToPi(baseUrl: String) {

        if (isCheckingConnection || isPiConnected) return

        isCheckingConnection = true

        serviceScope.launch {
            try {
                PiConnectionService.setBaseUrl(baseUrl)

                val result = PiConnectionService.checkConnection()

                isCheckingConnection = false

                if (result == "Connected to Raspberry Pi") {

                    isPiConnected = true
                    consecutiveFailures = 0

                    SmartCaneState.updateConnectionStatus(
                        "Raspberry Pi Connected"
                    )

                    announceConnectionState(true)

                    Log.i(TAG, "Raspberry Pi connected")

                    val clockResult =
                        PiConnectionService.synchronizeClock()

                    if (!isPiConnected) return@launch

                    SmartCaneState.updateLatencyComponents(
                        total = null,
                        piProcessing = null,
                        pollingWait = null,
                        httpRoundTrip = null,
                        androidProcessing = null,
                        clockUncertainty =
                            clockResult?.estimatedUncertaintyMs?.toFloat(),
                        inference = null
                    )

                    Log.i(
                        TAG,
                        "Clock sync: offset=${clockResult?.piMinusPhoneOffsetNs}, " +
                                "uncertainty=${clockResult?.estimatedUncertaintyMs} ms, " +
                                "RTT=${clockResult?.roundTripMs} ms"
                    )

                    startVideoReceiver()
                    startDetectionPolling()
                    startHealthMonitoring()

                } else {
                    handleConnectionFailure(
                        "Raspberry Pi connection check failed"
                    )
                }

            } catch (e: CancellationException) {
                isCheckingConnection = false
                throw e

            } catch (e: Exception) {
                isCheckingConnection = false

                Log.e(TAG, "Connection attempt failed", e)

                handleConnectionFailure(
                    "Raspberry Pi connection attempt failed"
                )
            }
        }
    }

    private fun handleConnectionFailure(reason: String) {

        Log.w(TAG, reason)

        isPiConnected = false
        isCheckingConnection = false

        PiConnectionService.clearBaseUrl()

        // Preserve collected latency samples after temporary failures.
        SmartCaneState.clearVideoFrame()
        SmartCaneState.updateDetections(emptyList())

        SmartCaneState.updateConnectionStatus(
            "Raspberry Pi Disconnected"
        )

        announceConnectionState(false)

        scheduleConnectionRetry()
    }

    private fun announceConnectionState(connected: Boolean) {

        if (lastAnnouncedConnectionState == connected) return

        lastAnnouncedConnectionState = connected

        val message = if (connected) {
            "Smart Cane connected."
        } else {
            "Smart Cane disconnected."
        }

        if (!ttsReady) {
            pendingConnectionAnnouncement = message
            return
        }

        speakConnectionStatus(message)
    }

    private fun speakConnectionStatus(message: String) {

        if (!ttsReady) {
            pendingConnectionAnnouncement = message
            return
        }

        val utteranceId =
            "smart_cane_connection_${++connectionUtteranceCounter}"

        textToSpeech?.speak(
            message,
            TextToSpeech.QUEUE_ADD,
            null,
            utteranceId
        )
    }

    private fun handlePiDisconnected() {

        if (!isPiConnected &&
            PiConnectionService.getBaseUrl() == null
        ) {
            return
        }

        isPiConnected = false

        Log.w(
            TAG,
            "Pi disconnected. Clearing camera and live measurements."
        )

        videoJob?.cancel()
        detectionJob?.cancel()
        healthJob?.cancel()

        videoJob = null
        detectionJob = null
        healthJob = null

        consecutiveFailures = 0
        lastAnnouncement = ""

        PiConnectionService.clearBaseUrl()

        // Preserve collected latency samples across temporary disconnections.
        SmartCaneState.clearVideoFrame()
        SmartCaneState.updateDetections(emptyList())

        SmartCaneState.updateConnectionStatus(
            "Raspberry Pi Disconnected"
        )

        announceConnectionState(false)

        scheduleConnectionRetry()
    }

    private fun scheduleConnectionRetry() {

        if (retryJob?.isActive == true) return

        retryJob = serviceScope.launch {
            delay(3000)

            retryJob = null

            if (!isPiConnected) {
                connectToPi(PI_BASE_URL)
            }
        }
    }

    // ---------------- VIDEO ----------------

    private fun startVideoReceiver() {

        videoJob?.cancel()

        val baseUrl = PiConnectionService.getBaseUrl() ?: return

        videoJob = serviceScope.launch {
            try {
                PiVideoReceiver.receiveFrames(baseUrl) { bitmap ->

                    if (isPiConnected) {

                        SmartCaneState.updateVideoFrame(bitmap)

                        frameCount++

                        val now = System.currentTimeMillis()
                        val elapsed = now - lastFpsUpdate

                        if (elapsed >= 1000) {

                            val fps = frameCount * 1000.0 / elapsed

                            SmartCaneState.updateFps(fps.toFloat())

                            frameCount = 0
                            lastFpsUpdate = now
                        }
                    }
                }

            } catch (e: CancellationException) {
                throw e

            } catch (e: Exception) {
                Log.e(TAG, "Video receiver failed", e)
                handlePiDisconnected()
            }
        }
    }

    // ---------------- DETECTION POLLING ----------------

    private fun startDetectionPolling() {

        detectionJob?.cancel()

        detectionJob = serviceScope.launch {

            val pollingIntervalNs =
                POLLING_INTERVAL_MS * 1_000_000L

            // Monotonic-clock schedule for three-second start-to-start polling.
            var nextPollTimeNs =
                SystemClock.elapsedRealtimeNanos()

            while (isActive && isPiConnected) {

                val success =
                    DetectionRepository.fetchAndUpdateDetections()

                if (!isPiConnected) break

                if (!success) {

                    consecutiveFailures++

                    Log.w(
                        TAG,
                        "Detection request failure $consecutiveFailures"
                    )

                    if (consecutiveFailures >= 3) {
                        handlePiDisconnected()
                        break
                    }

                } else {

                    consecutiveFailures = 0

                    SmartCaneState.updateConnectionStatus(
                        "Raspberry Pi Connected"
                    )

                    announceConnectionState(true)

                    updateLiveDetectionMetrics()

                    // Record one latency sample for each new Pi frame.
                    recordSnapshotLatency()

                    // Speech announcements are independent of sampling.
                    announceDetections()
                }

                // Schedule the next request relative to the previous
                // scheduled request start, not its completion time.
                nextPollTimeNs += pollingIntervalNs

                var currentTimeNs =
                    SystemClock.elapsedRealtimeNanos()

                // Skip missed slots if a request takes longer than
                // the polling interval. Never overlap requests.
                while (nextPollTimeNs <= currentTimeNs) {
                    nextPollTimeNs += pollingIntervalNs
                    currentTimeNs =
                        SystemClock.elapsedRealtimeNanos()
                }

                val remainingTimeNs =
                    nextPollTimeNs -
                            SystemClock.elapsedRealtimeNanos()

                if (remainingTimeNs > 0L) {

                    val delayMs =
                        (remainingTimeNs + 999_999L) / 1_000_000L

                    delay(delayMs)
                }
            }
        }
    }

    // ---------------- LIVE DETECTION METRICS ----------------

    private fun updateLiveDetectionMetrics() {

        if (!isPiConnected) return

        val timing = DetectionRepository.latestFrameTiming ?: return
        val clock = PiConnectionService.getClockSyncResult() ?: return

        SmartCaneState.updateDetectionMetrics(
            piProcessing = timing.piProcessingMs.toFloat(),
            inference = timing.inferenceMs
                .takeIf { it.isFinite() && it >= 0.0 }
                ?.toFloat(),
            pollingWait = null,
            httpRoundTrip = timing.httpRoundTripMs.toFloat(),
            clockUncertainty =
                clock.estimatedUncertaintyMs.toFloat()
        )

        Log.d(
            TAG,
            "Live components: Pi=${timing.piProcessingMs} ms, " +
                    "Inference=${timing.inferenceMs} ms, " +
                    "HTTP=${timing.httpRoundTripMs} ms, " +
                    "uncertainty=${clock.estimatedUncertaintyMs} ms"
        )
    }

    // ---------------- SNAPSHOT-BASED LATENCY ----------------

    private fun recordSnapshotLatency() {

        if (!isPiConnected) return

        val timing = DetectionRepository.latestFrameTiming

        if (timing == null) {
            SmartCaneState.updateLatencyDiagnostics(
                latencyCalculation = "Failed",
                diagnosticMessage =
                    "No frame timing available for this response"
            )
            return
        }

        // A repeated response for the same camera frame is not a new sample.
        if (timing.captureEpochNs <= lastSampledCaptureEpochNs) {
            Log.d(
                TAG,
                "Duplicate frame ignored: ${timing.captureEpochNs}"
            )
            return
        }

        val clock = PiConnectionService.getClockSyncResult()

        if (clock == null) {
            SmartCaneState.updateLatencyDiagnostics(
                clockSync = "Unavailable",
                latencyCalculation = "Failed",
                diagnosticMessage = "Clock synchronization unavailable"
            )
            return
        }

        val piProcessingMs = timing.piProcessingMs
        val inferenceMs = timing.inferenceMs
        val httpRoundTripMs = timing.httpRoundTripMs
        val clockUncertaintyMs = clock.estimatedUncertaintyMs

        val componentValues = listOf(
            piProcessingMs,
            inferenceMs,
            httpRoundTripMs,
            clockUncertaintyMs
        )

        if (componentValues.any { !it.isFinite() || it < 0.0 }) {
            SmartCaneState.updateLatencyDiagnostics(
                latencyCalculation = "Failed",
                diagnosticMessage =
                    "Invalid timing component values: $componentValues"
            )
            return
        }

        if (timing.captureEpochNs <= 0L ||
            timing.responseReceivedEpochNs <= 0L
        ) {
            SmartCaneState.updateLatencyDiagnostics(
                latencyCalculation = "Failed",
                diagnosticMessage =
                    "Invalid capture or response timestamp"
            )
            return
        }

        // Convert Android response-received epoch time into Pi epoch time.
        // Clock offset = Pi epoch time minus Android epoch time.
        val responsePiEpochNs =
            timing.responseReceivedEpochNs +
                    clock.piMinusPhoneOffsetNs

        if (responsePiEpochNs < timing.captureEpochNs) {
            SmartCaneState.updateLatencyDiagnostics(
                latencyCalculation = "Failed",
                diagnosticMessage =
                    "Invalid timestamp relationship: " +
                            "capture=${timing.captureEpochNs}, " +
                            "responsePi=$responsePiEpochNs"
            )
            return
        }

        // Capture-to-Android-response latency.
        val totalLatencyMs =
            (responsePiEpochNs - timing.captureEpochNs) / 1_000_000.0

        if (!totalLatencyMs.isFinite() || totalLatencyMs < 0.0) {
            SmartCaneState.updateLatencyDiagnostics(
                latencyCalculation = "Failed",
                diagnosticMessage =
                    "Invalid calculated latency: $totalLatencyMs ms"
            )
            return
        }

        if (!isPiConnected) return

        // Measurement ends when Android receives the response.
        // Android post-response processing and TTS delay are not measured.
        val androidProcessingMs = 0.0

        val previousCount = LatencyReportManager.getSampleCount()

        LatencyReportManager.addSample(
            applicationContext,
            LatencySample(
                totalLatencyMs = totalLatencyMs,
                piProcessingMs = piProcessingMs,
                inferenceMs = inferenceMs,
                httpRoundTripMs = httpRoundTripMs,
                androidProcessingMs = androidProcessingMs,
                clockUncertaintyMs = clockUncertaintyMs
            )
        )

        val newCount = LatencyReportManager.getSampleCount()

        SmartCaneState.updateLatencySampleCount(newCount)

        // Only mark this frame as sampled if the report manager accepted it.
        if (newCount > previousCount) {

            lastSampledCaptureEpochNs = timing.captureEpochNs

            SmartCaneState.updateLatencyComponents(
                total = totalLatencyMs.toFloat(),
                piProcessing = piProcessingMs.toFloat(),
                pollingWait = null,
                httpRoundTrip = httpRoundTripMs.toFloat(),
                androidProcessing = null,
                clockUncertainty = clockUncertaintyMs.toFloat(),
                inference = inferenceMs.toFloat()
            )

            updateTotalLatencyStatistics(totalLatencyMs)

            SmartCaneState.updateLatencyDiagnostics(
                clockSync = "Available",
                timingRecord = "Found",
                latencyCalculation = "Successful",
                diagnosticMessage =
                    "New snapshot latency sample recorded",
                lastRecordedLatency = totalLatencyMs.toFloat()
            )

            Log.i(
                TAG,
                "Snapshot latency sample $newCount/100 recorded: " +
                        "$totalLatencyMs ms, " +
                        "capture=${timing.captureEpochNs}, " +
                        "responsePi=$responsePiEpochNs"
            )

        } else {

            SmartCaneState.updateLatencyDiagnostics(
                latencyCalculation = "Not recorded",
                diagnosticMessage =
                    "Sample was not accepted by the report manager"
            )

            Log.w(
                TAG,
                "Sample not accepted. Existing count=$newCount"
            )
        }
    }

    // ---------------- LATENCY STATISTICS ----------------

    private fun updateTotalLatencyStatistics(latency: Double) {

        if (!isPiConnected) return

        if (!latency.isFinite() || latency < 0.0) {
            Log.e(TAG, "Invalid latency value: $latency")
            return
        }

        minimumLatency = minOf(minimumLatency, latency)
        maximumLatency = maxOf(maximumLatency, latency)

        totalLatencySum += latency
        latencySampleCount++

        val averageLatency =
            totalLatencySum / latencySampleCount

        SmartCaneState.updateLatencyStatistics(
            current = latency.toFloat(),
            minimum = minimumLatency.toFloat(),
            maximum = maximumLatency.toFloat(),
            average = averageLatency.toFloat()
        )

        Log.i(
            TAG,
            """
            -------- SNAPSHOT LATENCY STATISTICS --------
            Current latency: $latency ms
            Minimum latency: $minimumLatency ms
            Maximum latency: $maximumLatency ms
            Average latency: $averageLatency ms
            Valid samples: $latencySampleCount
            --------------------------------------------
            """.trimIndent()
        )
    }

    // ---------------- RESET STATISTICS ----------------

    private fun resetStatistics(clearSamples: Boolean = false) {

        minimumLatency = Double.POSITIVE_INFINITY
        maximumLatency = Double.NEGATIVE_INFINITY

        totalLatencySum = 0.0
        latencySampleCount = 0L

        lastSampledCaptureEpochNs = 0L

        frameCount = 0
        lastFpsUpdate = System.currentTimeMillis()

        // Clear report samples only for a fresh service session.
        // Temporary Wi-Fi failures do not erase the collection.
        if (clearSamples) {
            LatencyReportManager.reset()
        }

        SmartCaneState.resetPerformanceStatistics()
        SmartCaneState.updateProcessingLatency(null)

        SmartCaneState.updateLatencySampleCount(
            LatencyReportManager.getSampleCount()
        )

        Log.i(
            TAG,
            "Performance statistics reset; report samples=" +
                    LatencyReportManager.getSampleCount()
        )
    }

    // ---------------- HEALTH MONITORING ----------------

    private fun startHealthMonitoring() {

        healthJob?.cancel()

        healthJob = serviceScope.launch {

            while (isActive && isPiConnected) {

                delay(10000)

                if (!isPiConnected ||
                    PiConnectionService.getBaseUrl() == null
                ) {
                    break
                }

                val result = PiConnectionService.checkConnection()

                if (!isPiConnected) break

                if (result != "Connected to Raspberry Pi") {

                    Log.w(TAG, "Health check failed")

                    handlePiDisconnected()
                    break
                }
            }
        }
    }

    // ---------------- VOICE ANNOUNCEMENTS ----------------

    private data class AnnouncementGroup(
        val className: String,
        val direction: String,
        val count: Int
    )

    private fun announceDetections() {

        if (!ttsReady || !isPiConnected) return

        val detections = SmartCaneState.detections.value

        if (detections.isEmpty()) {
            lastAnnouncement = ""
            return
        }

        val groups = detections
            .groupBy {
                Pair(
                    it.className.trim().lowercase(Locale.US),
                    it.getDirection().lowercase(Locale.US)
                )
            }
            .map { (key, objects) ->
                AnnouncementGroup(
                    className = key.first,
                    direction = key.second,
                    count = objects.size
                )
            }
            .sortedWith(
                compareBy<AnnouncementGroup>(
                    { it.className },
                    { it.direction }
                )
            )

        val descriptions = groups.map { group ->

            val quantity = numberToWords(group.count)

            val objectName = if (group.count == 1) {
                singularName(group.className)
            } else {
                pluralName(group.className)
            }

            "$quantity $objectName ${directionPhrase(group.direction)}"
        }

        val description = when (descriptions.size) {

            1 -> descriptions[0]

            2 -> "${descriptions[0]} and ${descriptions[1]}"

            else ->
                descriptions.dropLast(1).joinToString(", ") +
                        " and " + descriptions.last()
        }

        val message = description.replaceFirstChar {
            if (it.isLowerCase()) {
                it.titlecase(Locale.US)
            } else {
                it.toString()
            }
        } + "."

        // Announcements occur only when the spoken description changes.
        // They do not trigger or control latency sampling.
        if (message != lastAnnouncement) {

            val utteranceId =
                "smart_cane_announcement_${System.currentTimeMillis()}"

            val result = textToSpeech?.speak(
                message,
                TextToSpeech.QUEUE_ADD,
                null,
                utteranceId
            )

            if (result == TextToSpeech.ERROR) {
                Log.e(TAG, "TTS speak request failed: $utteranceId")
            } else {
                lastAnnouncement = message
            }
        }
    }

    // ---------------- OBJECT NAMING ----------------

    private fun singularName(className: String): String {
        return when (className) {
            "tv" -> "TV"
            else -> className
        }
    }

    private fun pluralName(className: String): String {
        return when (className) {

            "person" -> "people"
            "people" -> "people"

            "mouse" -> "mice"
            "mice" -> "mice"

            "knife" -> "knives"
            "knives" -> "knives"

            "toothbrush" -> "toothbrushes"

            "sheep" -> "sheep"
            "deer" -> "deer"

            "tv" -> "TVs"

            else -> {
                when {
                    className.endsWith("ch") ||
                            className.endsWith("sh") ||
                            className.endsWith("s") ||
                            className.endsWith("x") ||
                            className.endsWith("z") ->
                        className + "es"

                    className.endsWith("y") &&
                            className.length > 1 &&
                            className[className.length - 2] !in "aeiou" ->
                        className.dropLast(1) + "ies"

                    else -> className + "s"
                }
            }
        }
    }

    private fun directionPhrase(direction: String): String {
        return when (direction) {
            "left" -> "on the left"
            "right" -> "on the right"
            else -> "ahead"
        }
    }

    private fun numberToWords(number: Int): String {
        return when (number) {
            1 -> "One"
            2 -> "Two"
            3 -> "Three"
            4 -> "Four"
            5 -> "Five"
            6 -> "Six"
            7 -> "Seven"
            8 -> "Eight"
            9 -> "Nine"
            10 -> "Ten"
            else -> number.toString()
        }
    }

    // ---------------- TEXT TO SPEECH ----------------

    override fun onInit(status: Int) {

        ttsReady = status == TextToSpeech.SUCCESS

        if (ttsReady) {

            textToSpeech?.language = Locale.US

            pendingConnectionAnnouncement?.let { message ->
                pendingConnectionAnnouncement = null
                speakConnectionStatus(message)
            }

        } else {

            ttsReady = false

            Log.e(TAG, "Text-to-speech initialization failed")
        }
    }

    // ---------------- NOTIFICATION ----------------

    private fun createNotificationChannel() {

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {

            val channel = NotificationChannel(
                CHANNEL_ID,
                "Smart Cane",
                NotificationManager.IMPORTANCE_LOW
            )

            getSystemService(NotificationManager::class.java)
                .createNotificationChannel(channel)
        }
    }

    private fun createNotification(): Notification =
        NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("Smart Cane")
            .setContentText("Monitoring Raspberry Pi connection")
            .setSmallIcon(android.R.drawable.ic_menu_view)
            .setOngoing(true)
            .build()

    // ---------------- SERVICE DESTRUCTION ----------------

    override fun onDestroy() {

        isPiConnected = false

        videoJob?.cancel()
        detectionJob?.cancel()
        healthJob?.cancel()
        retryJob?.cancel()

        PiConnectionService.clearBaseUrl()

        serviceScope.cancel()

        textToSpeech?.stop()
        textToSpeech?.shutdown()

        SmartCaneState.clear()

        super.onDestroy()
    }

    companion object {

        private const val TAG = "SmartCaneStats"

        private const val CHANNEL_ID = "smart_cane_channel"
        private const val NOTIFICATION_ID = 1001

        private const val POLLING_INTERVAL_MS = 3000L

        private const val PI_BASE_URL = "http://10.133.237.6:5000"
    }
}
