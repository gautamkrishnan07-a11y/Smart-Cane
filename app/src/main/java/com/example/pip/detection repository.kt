
package com.example.pip

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject

data class DetectionFrameTiming(
    val captureEpochNs: Long,
    val readyEpochNs: Long,
    val piProcessingMs: Double,
    val inferenceMs: Double,
    val requestStartElapsedNs: Long,
    val responseReceivedElapsedNs: Long,
    val requestStartEpochNs: Long,
    val responseReceivedEpochNs: Long,
    val httpRoundTripMs: Double
)

object DetectionRepository {

    @Volatile
    var latestFrameTiming: DetectionFrameTiming? = null
        private set

    suspend fun fetchAndUpdateDetections(): Boolean {

        // Never reuse timing information from an earlier response.
        latestFrameTiming = null

        val response =
            PiConnectionService.getDetectionsWithTiming()
                ?: return false

        return withContext(Dispatchers.Default) {

            try {
                val json = response.json

                // A valid response must contain a detections array.
                // An empty array is valid and represents zero detections.
                val detectionArray =
                    json.optJSONArray("detections")
                        ?: throw IllegalStateException(
                            "Missing or invalid detections array"
                        )

                val results = mutableListOf<Detection>()

                for (i in 0 until detectionArray.length()) {

                    val item: JSONObject =
                        detectionArray.getJSONObject(i)

                    results.add(
                        Detection(
                            className = item.optString(
                                "className",
                                "unknown"
                            ),
                            confidence = item.optDouble(
                                "confidence",
                                0.0
                            ).toFloat(),
                            centerX = item.optDouble(
                                "centerX",
                                0.5
                            ).toFloat(),
                            centerY = item.optDouble(
                                "centerY",
                                0.5
                            ).toFloat(),
                            left = item.optDouble(
                                "left",
                                0.0
                            ).toFloat(),
                            top = item.optDouble(
                                "top",
                                0.0
                            ).toFloat(),
                            right = item.optDouble(
                                "right",
                                1.0
                            ).toFloat(),
                            bottom = item.optDouble(
                                "bottom",
                                1.0
                            ).toFloat()
                        )
                    )
                }

                // ---------------- PI TIMESTAMPS ----------------

                val captureEpochNs =
                    json.optLong("captureEpochNs", 0L)

                val readyEpochNs =
                    json.optLong("readyEpochNs", 0L)

                // ---------------- PI PROCESSING ----------------

                val processingLatency =
                    json.optDouble(
                        "processingLatencyMs",
                        Double.NaN
                    )

                val inferenceLatency =
                    json.optDouble(
                        "inferenceMs",
                        Double.NaN
                    )

                // ---------------- ANDROID TIMING ----------------

                val requestStartElapsedNs =
                    response.requestStartElapsedNs

                val responseReceivedElapsedNs =
                    response.responseReceivedElapsedNs

                val requestStartEpochNs =
                    response.requestStartEpochNs

                val responseReceivedEpochNs =
                    response.responseReceivedEpochNs

                val httpRoundTripMs =
                    response.roundTripMs

                // ---------------- VALIDATION ----------------

                val validPiTimestamps =
                    captureEpochNs > 0L &&
                            readyEpochNs >= captureEpochNs

                val validProcessing =
                    processingLatency.isFinite() &&
                            processingLatency >= 0.0

                val validInference =
                    inferenceLatency.isFinite() &&
                            inferenceLatency >= 0.0

                val validAndroidTimestamps =
                    requestStartElapsedNs > 0L &&
                            responseReceivedElapsedNs >= requestStartElapsedNs &&
                            requestStartEpochNs > 0L &&
                            responseReceivedEpochNs >= requestStartEpochNs

                val validHttpTiming =
                    httpRoundTripMs.isFinite() &&
                            httpRoundTripMs >= 0.0

                if (
                    !validPiTimestamps ||
                    !validProcessing ||
                    !validInference ||
                    !validAndroidTimestamps ||
                    !validHttpTiming
                ) {

                    latestFrameTiming = null

                    LogTiming.warning(
                        "Frame timing rejected: " +
                                "capture=$captureEpochNs, " +
                                "ready=$readyEpochNs, " +
                                "processing=$processingLatency, " +
                                "inference=$inferenceLatency, " +
                                "requestElapsed=$requestStartElapsedNs, " +
                                "responseElapsed=$responseReceivedElapsedNs, " +
                                "requestEpoch=$requestStartEpochNs, " +
                                "responseEpoch=$responseReceivedEpochNs, " +
                                "HTTP=$httpRoundTripMs"
                    )

                } else {

                    latestFrameTiming = DetectionFrameTiming(
                        captureEpochNs = captureEpochNs,
                        readyEpochNs = readyEpochNs,
                        piProcessingMs = processingLatency,
                        inferenceMs = inferenceLatency,
                        requestStartElapsedNs =
                            requestStartElapsedNs,
                        responseReceivedElapsedNs =
                            responseReceivedElapsedNs,
                        requestStartEpochNs =
                            requestStartEpochNs,
                        responseReceivedEpochNs =
                            responseReceivedEpochNs,
                        httpRoundTripMs =
                            httpRoundTripMs
                    )

                    LogTiming.info(
                        "Frame timing accepted: " +
                                "capture=$captureEpochNs, " +
                                "ready=$readyEpochNs, " +
                                "processing=$processingLatency ms, " +
                                "inference=$inferenceLatency ms, " +
                                "HTTP=$httpRoundTripMs ms"
                    )
                }

                // Update detections even if timing validation fails.
                // This keeps live detection independent of latency measurement.
                SmartCaneState.updateDetections(results)

                SmartCaneState.updateProcessingLatency(
                    if (processingLatency.isFinite()) {
                        processingLatency
                    } else {
                        null
                    }
                )

                SmartCaneState.updateConnectionStatus(
                    "Connected to Raspberry Pi"
                )

                true

            } catch (e: Exception) {

                latestFrameTiming = null

                LogTiming.error(
                    "Failed to process detection response",
                    e
                )

                false
            }
        }
    }
}

// ---------------- LOGGING HELPER ----------------

private object LogTiming {

    private const val TAG = "SmartCaneStats"

    fun info(message: String) {
        android.util.Log.i(TAG, message)
    }

    fun warning(message: String) {
        android.util.Log.w(TAG, message)
    }

    fun error(message: String, throwable: Throwable) {
        android.util.Log.e(TAG, message, throwable)
    }
}
