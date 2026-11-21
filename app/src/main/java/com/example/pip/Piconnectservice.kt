
package com.example.pip

import android.os.SystemClock
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL

data class PiDetectionResponse(
    val json: JSONObject,
    val requestStartElapsedNs: Long,
    val responseReceivedElapsedNs: Long,
    val requestStartEpochNs: Long,
    val responseReceivedEpochNs: Long
) {
    val roundTripMs: Double
        get() = (
                responseReceivedElapsedNs - requestStartElapsedNs
                ) / 1_000_000.0
}

data class ClockSyncResult(
    // Pi clock minus Android clock.
    val piMinusPhoneOffsetNs: Long,

    val estimatedUncertaintyMs: Double,
    val roundTripMs: Double,

    // Phone's estimated Unix timestamp at request midpoint.
    val phoneEpochNs: Long,

    // Unix timestamp returned by the Raspberry Pi.
    val piEpochNs: Long
)

object PiConnectionService {

    @Volatile
    private var piBaseUrl: String? = null

    @Volatile
    private var clockSyncResult: ClockSyncResult? = null

    fun setBaseUrl(url: String) {
        piBaseUrl = url.trimEnd('/')
    }

    fun getBaseUrl(): String? = piBaseUrl

    fun clearBaseUrl() {
        piBaseUrl = null
        clockSyncResult = null
    }

    fun getClockSyncResult(): ClockSyncResult? {
        return clockSyncResult
    }

    private fun currentEpochNs(): Long {
        return System.currentTimeMillis() * 1_000_000L
    }

    // -------------------------------------------------
    // CONNECTION CHECK
    // -------------------------------------------------

    suspend fun checkConnection(): String =
        withContext(Dispatchers.IO) {

            val baseUrl = piBaseUrl
                ?: return@withContext "Waiting for Raspberry Pi discovery"

            var connection: HttpURLConnection? = null

            try {
                connection = URL("$baseUrl/health")
                    .openConnection() as HttpURLConnection

                connection.requestMethod = "GET"
                connection.connectTimeout = 3000
                connection.readTimeout = 3000

                if (connection.responseCode == HttpURLConnection.HTTP_OK) {
                    "Connected to Raspberry Pi"
                } else {
                    "Pi returned HTTP ${connection.responseCode}"
                }

            } catch (e: Exception) {
                "Connection failed: ${e.message ?: "Unknown error"}"

            } finally {
                connection?.disconnect()
            }
        }

    // -------------------------------------------------
    // CLOCK SYNCHRONIZATION
    // -------------------------------------------------

    suspend fun synchronizeClock(
        samples: Int = 8
    ): ClockSyncResult? = withContext(Dispatchers.IO) {

        val baseUrl = piBaseUrl
            ?: return@withContext null

        if (samples <= 0) {
            clockSyncResult = null
            return@withContext null
        }

        val measurements = mutableListOf<ClockSyncResult>()

        repeat(samples) {

            var connection: HttpURLConnection? = null

            try {
                // Record Android monotonic and wall-clock time
                // immediately before sending the request.

                val phoneStartElapsed =
                    SystemClock.elapsedRealtimeNanos()

                val phoneStartEpoch =
                    currentEpochNs()

                connection = URL("$baseUrl/time")
                    .openConnection() as HttpURLConnection

                connection.requestMethod = "GET"
                connection.connectTimeout = 3000
                connection.readTimeout = 3000

                if (connection.responseCode == HttpURLConnection.HTTP_OK) {

                    val responseText = connection.inputStream
                        .bufferedReader()
                        .use { it.readText() }

                    // Record the end of the request using
                    // the monotonic clock.

                    val phoneEndElapsed =
                        SystemClock.elapsedRealtimeNanos()

                    val piEpoch = JSONObject(responseText)
                        .getLong("epoch_ns")

                    val rttNs =
                        phoneEndElapsed - phoneStartElapsed

                    if (piEpoch <= 0L || rttNs <= 0L) {
                        return@repeat
                    }

                    // Estimate Android's epoch time at the
                    // midpoint using the monotonic clock.

                    val phoneMidpointEpoch =
                        phoneStartEpoch + rttNs / 2L

                    // Positive offset means the Pi clock
                    // is ahead of the Android clock.

                    val offset =
                        piEpoch - phoneMidpointEpoch

                    val uncertaintyMs =
                        rttNs / 2_000_000.0

                    val roundTripMs =
                        rttNs / 1_000_000.0

                    measurements.add(
                        ClockSyncResult(
                            piMinusPhoneOffsetNs = offset,
                            estimatedUncertaintyMs = uncertaintyMs,
                            roundTripMs = roundTripMs,
                            phoneEpochNs = phoneMidpointEpoch,
                            piEpochNs = piEpoch
                        )
                    )
                }

            } catch (e: Exception) {
                // Ignore failed synchronization attempts.
                // Other attempts can still provide valid measurements.

            } finally {
                connection?.disconnect()
            }
        }

        if (measurements.isEmpty()) {
            clockSyncResult = null
            return@withContext null
        }

        // Lowest RTT generally gives the smallest
        // network-delay uncertainty.

        val best = measurements.minByOrNull {
            it.roundTripMs
        }

        clockSyncResult = best

        best
    }

    // -------------------------------------------------
    // DETECTION REQUEST WITH TIMING
    // -------------------------------------------------

    suspend fun getDetectionsWithTiming(): PiDetectionResponse? =
        withContext(Dispatchers.IO) {

            val baseUrl = piBaseUrl
                ?: return@withContext null

            var connection: HttpURLConnection? = null

            try {
                // Android request start timestamps.

                val requestStartElapsed =
                    SystemClock.elapsedRealtimeNanos()

                val requestStartEpoch =
                    currentEpochNs()

                connection = URL("$baseUrl/detections")
                    .openConnection() as HttpURLConnection

                connection.requestMethod = "GET"
                connection.connectTimeout = 3000
                connection.readTimeout = 5000

                if (connection.responseCode == HttpURLConnection.HTTP_OK) {

                    // Read the complete response body.

                    val responseText = connection.inputStream
                        .bufferedReader()
                        .use { it.readText() }

                    // Record response receipt immediately after
                    // reading the complete HTTP response.

                    val responseReceivedElapsed =
                        SystemClock.elapsedRealtimeNanos()

                    val responseReceivedEpoch =
                        currentEpochNs()

                    PiDetectionResponse(
                        json = JSONObject(responseText),

                        requestStartElapsedNs =
                            requestStartElapsed,

                        responseReceivedElapsedNs =
                            responseReceivedElapsed,

                        requestStartEpochNs =
                            requestStartEpoch,

                        responseReceivedEpochNs =
                            responseReceivedEpoch
                    )

                } else {
                    null
                }

            } catch (e: Exception) {
                null

            } finally {
                connection?.disconnect()
            }
        }

    // -------------------------------------------------
    // BACKWARD COMPATIBILITY
    // -------------------------------------------------

    suspend fun getDetections(): JSONObject? {
        return getDetectionsWithTiming()?.json
    }
}
