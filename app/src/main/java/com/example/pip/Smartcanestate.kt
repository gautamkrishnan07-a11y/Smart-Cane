
package com.example.pip

import android.graphics.Bitmap
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow

object SmartCaneState {

    // ---------------- CAMERA FRAME ----------------

    private val _videoFrame = MutableStateFlow<Bitmap?>(null)
    val videoFrame = _videoFrame.asStateFlow()

    // ---------------- DETECTED OBJECTS ----------------

    private val _detections =
        MutableStateFlow<List<Detection>>(emptyList())

    val detections = _detections.asStateFlow()

    // ---------------- CONNECTION STATUS ----------------

    private val _connectionStatus =
        MutableStateFlow("Disconnected")

    val connectionStatus = _connectionStatus.asStateFlow()

    // ---------------- RASPBERRY PI PROCESSING ----------------

    private val _processingLatencyMs =
        MutableStateFlow<Double?>(null)

    val processingLatencyMs = _processingLatencyMs.asStateFlow()

    // ---------------- CURRENT TOTAL LATENCY ----------------

    private val _totalLatencyMs = MutableStateFlow<Float?>(null)
    val totalLatencyMs = _totalLatencyMs.asStateFlow()

    // ---------------- INDIVIDUAL TIMING MEASUREMENTS ----------------

    private val _piProcessingMs = MutableStateFlow<Float?>(null)
    val piProcessingMs = _piProcessingMs.asStateFlow()

    private val _inferenceMs = MutableStateFlow<Float?>(null)
    val inferenceMs = _inferenceMs.asStateFlow()

    private val _pollingWaitMs = MutableStateFlow<Float?>(null)
    val pollingWaitMs = _pollingWaitMs.asStateFlow()

    private val _httpRoundTripMs = MutableStateFlow<Float?>(null)
    val httpRoundTripMs = _httpRoundTripMs.asStateFlow()

    private val _androidProcessingMs = MutableStateFlow<Float?>(null)
    val androidProcessingMs = _androidProcessingMs.asStateFlow()

    private val _clockUncertaintyMs = MutableStateFlow<Float?>(null)
    val clockUncertaintyMs = _clockUncertaintyMs.asStateFlow()

    // ---------------- LATENCY STATISTICS ----------------

    private val _currentLatencyMs = MutableStateFlow<Float?>(null)
    val currentLatencyMs = _currentLatencyMs.asStateFlow()

    private val _minimumLatencyMs = MutableStateFlow<Float?>(null)
    val minimumLatencyMs = _minimumLatencyMs.asStateFlow()

    private val _maximumLatencyMs = MutableStateFlow<Float?>(null)
    val maximumLatencyMs = _maximumLatencyMs.asStateFlow()

    private val _averageLatencyMs = MutableStateFlow<Float?>(null)
    val averageLatencyMs = _averageLatencyMs.asStateFlow()

    // Number of valid snapshot latency measurements.

    private val _latencySampleCount = MutableStateFlow(0L)
    val latencySampleCount = _latencySampleCount.asStateFlow()

    // ---------------- CAMERA FPS ----------------

    private val _fps = MutableStateFlow(0f)
    val fps = _fps.asStateFlow()

    // ---------------- LATENCY DIAGNOSTICS ----------------

    // Retained for compatibility with existing UI references.
    // These are not used to determine latency samples.

    private val _announcementQueuedStatus =
        MutableStateFlow("Not used for latency measurement")

    val announcementQueuedStatus =
        _announcementQueuedStatus.asStateFlow()

    private val _ttsCallbackStatus =
        MutableStateFlow("Not used for latency measurement")

    val ttsCallbackStatus =
        _ttsCallbackStatus.asStateFlow()

    private val _timingRecordStatus =
        MutableStateFlow("Waiting")

    val timingRecordStatus =
        _timingRecordStatus.asStateFlow()

    private val _clockSyncStatus =
        MutableStateFlow("Waiting")

    val clockSyncStatus =
        _clockSyncStatus.asStateFlow()

    private val _latencyCalculationStatus =
        MutableStateFlow("Waiting")

    val latencyCalculationStatus =
        _latencyCalculationStatus.asStateFlow()

    private val _lastLatencyDiagnostic =
        MutableStateFlow("Waiting for a new frame snapshot")

    val lastLatencyDiagnostic =
        _lastLatencyDiagnostic.asStateFlow()

    private val _lastRecordedLatencyMs =
        MutableStateFlow<Float?>(null)

    val lastRecordedLatencyMs =
        _lastRecordedLatencyMs.asStateFlow()

    // ---------------- DIAGNOSTIC UPDATE ----------------

    fun updateLatencyDiagnostics(
        announcementQueued: String? = null,
        ttsCallback: String? = null,
        timingRecord: String? = null,
        clockSync: String? = null,
        latencyCalculation: String? = null,
        diagnosticMessage: String? = null,
        lastRecordedLatency: Float? = null
    ) {

        announcementQueued?.let {
            _announcementQueuedStatus.value = it
        }

        ttsCallback?.let {
            _ttsCallbackStatus.value = it
        }

        timingRecord?.let {
            _timingRecordStatus.value = it
        }

        clockSync?.let {
            _clockSyncStatus.value = it
        }

        latencyCalculation?.let {
            _latencyCalculationStatus.value = it
        }

        diagnosticMessage?.let {
            _lastLatencyDiagnostic.value = it
        }

        lastRecordedLatency?.let {
            if (it.isFinite() && it >= 0f) {
                _lastRecordedLatencyMs.value = it
            }
        }
    }

    // ---------------- UPDATE FUNCTIONS ----------------

    fun updateVideoFrame(bitmap: Bitmap) {
        _videoFrame.value = bitmap
    }

    fun clearVideoFrame() {
        _videoFrame.value = null
    }

    fun updateDetections(results: List<Detection>) {
        _detections.value = results
    }

    fun updateConnectionStatus(status: String) {
        _connectionStatus.value = status
    }

    fun updateProcessingLatency(latencyMs: Double?) {

        _processingLatencyMs.value =
            latencyMs?.takeIf {
                it.isFinite() && it >= 0.0
            }
    }

    // ---------------- DETECTION METRICS ----------------

    fun updateDetectionMetrics(
        piProcessing: Float?,
        inference: Float?,
        pollingWait: Float?,
        httpRoundTrip: Float?,
        clockUncertainty: Float?
    ) {

        _piProcessingMs.value = validMetric(piProcessing)
        _inferenceMs.value = validMetric(inference)
        _pollingWaitMs.value = validMetric(pollingWait)
        _httpRoundTripMs.value = validMetric(httpRoundTrip)
        _clockUncertaintyMs.value = validMetric(clockUncertainty)
    }

    // ---------------- LATENCY COMPONENTS ----------------

    fun updateLatencyComponents(
        total: Float?,
        piProcessing: Float?,
        pollingWait: Float?,
        httpRoundTrip: Float?,
        androidProcessing: Float?,
        clockUncertainty: Float?,
        inference: Float? = null
    ) {

        _totalLatencyMs.value = validMetric(total)
        _piProcessingMs.value = validMetric(piProcessing)
        _pollingWaitMs.value = validMetric(pollingWait)
        _httpRoundTripMs.value = validMetric(httpRoundTrip)
        _androidProcessingMs.value = validMetric(androidProcessing)
        _clockUncertaintyMs.value = validMetric(clockUncertainty)
        _inferenceMs.value = validMetric(inference)
    }

    // ---------------- FPS ----------------

    fun updateFps(value: Float) {

        if (value.isFinite() && value >= 0f) {
            _fps.value = value
        }
    }

    // ---------------- STATISTICS UPDATE ----------------

    fun updateLatencyStatistics(
        current: Float,
        minimum: Float,
        maximum: Float,
        average: Float? = null
    ) {

        if (
            !current.isFinite() ||
            !minimum.isFinite() ||
            !maximum.isFinite() ||
            current < 0f ||
            minimum < 0f ||
            maximum < minimum ||
            (
                    average != null &&
                            (!average.isFinite() || average < 0f)
                    )
        ) {
            return
        }

        _currentLatencyMs.value = current
        _minimumLatencyMs.value = minimum
        _maximumLatencyMs.value = maximum
        _averageLatencyMs.value = average
    }

    // ---------------- SAMPLE COUNT ----------------

    fun updateLatencySampleCount(count: Int) {

        _latencySampleCount.value =
            count.coerceAtLeast(0).toLong()
    }

    // ---------------- RESET FUNCTIONS ----------------

    fun resetLatencyDiagnostics() {

        _announcementQueuedStatus.value =
            "Not used for latency measurement"

        _ttsCallbackStatus.value =
            "Not used for latency measurement"

        _timingRecordStatus.value = "Waiting"
        _clockSyncStatus.value = "Waiting"
        _latencyCalculationStatus.value = "Waiting"

        _lastLatencyDiagnostic.value =
            "Waiting for a new frame snapshot"

        _lastRecordedLatencyMs.value = null
    }

    fun resetPerformanceStatistics() {

        _currentLatencyMs.value = null
        _minimumLatencyMs.value = null
        _maximumLatencyMs.value = null
        _averageLatencyMs.value = null

        _latencySampleCount.value = 0L

        _totalLatencyMs.value = null
        _piProcessingMs.value = null
        _inferenceMs.value = null
        _pollingWaitMs.value = null
        _httpRoundTripMs.value = null
        _androidProcessingMs.value = null
        _clockUncertaintyMs.value = null

        _fps.value = 0f

        resetLatencyDiagnostics()
    }

    // ---------------- COMPLETE STATE RESET ----------------

    fun clear() {

        _videoFrame.value = null
        _detections.value = emptyList()
        _connectionStatus.value = "Disconnected"
        _processingLatencyMs.value = null

        resetPerformanceStatistics()
    }

    // ---------------- VALIDATION HELPER ----------------

    private fun validMetric(value: Float?): Float? {

        return value?.takeIf {
            it.isFinite() && it >= 0f
        }
    }
}
