package com.example.pip

import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.launch
import java.util.Locale

class MainActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        startForegroundService(
            Intent(this, SmartCaneService::class.java)
        )

        setContent {
            MaterialTheme {
                Surface(
                    modifier = Modifier.fillMaxSize()
                ) {
                    SmartCaneScreen()
                }
            }
        }
    }
}

@Composable
fun SmartCaneScreen() {

    val connectionStatus by
    SmartCaneState.connectionStatus.collectAsState()

    val videoFrame by
    SmartCaneState.videoFrame.collectAsState()

    val detections by
    SmartCaneState.detections.collectAsState()

    val totalLatency by
    SmartCaneState.totalLatencyMs.collectAsState()

    val currentLatency by
    SmartCaneState.currentLatencyMs.collectAsState()

    val minimumLatency by
    SmartCaneState.minimumLatencyMs.collectAsState()

    val maximumLatency by
    SmartCaneState.maximumLatencyMs.collectAsState()

    val piProcessing by
    SmartCaneState.piProcessingMs.collectAsState()

    val inference by
    SmartCaneState.inferenceMs.collectAsState()

    val httpRoundTrip by
    SmartCaneState.httpRoundTripMs.collectAsState()

    val clockUncertainty by
    SmartCaneState.clockUncertaintyMs.collectAsState()

    val fps by
    SmartCaneState.fps.collectAsState()

    // Latency diagnostics

    val timingRecord by
    SmartCaneState.timingRecordStatus.collectAsState()

    val clockSync by
    SmartCaneState.clockSyncStatus.collectAsState()

    val latencyCalculation by
    SmartCaneState.latencyCalculationStatus.collectAsState()

    val diagnosticMessage by
    SmartCaneState.lastLatencyDiagnostic.collectAsState()

    val lastRecordedLatency by
    SmartCaneState.lastRecordedLatencyMs.collectAsState()

    // Live latency samples

    val latencySamples by
    LatencyReportManager.samplesFlow.collectAsState()

    // Word report status

    val reportStatus by
    LatencyReportManager.reportStatusFlow.collectAsState()

    val reportFilename by
    LatencyReportManager.reportFilenameFlow.collectAsState()

    val sampleCount = latencySamples.size

    val isDisconnected =
        connectionStatus.contains(
            "Disconnected",
            ignoreCase = true
        )

    // Clock diagnostic state

    val coroutineScope = rememberCoroutineScope()

    var clockDiagnostic by remember {
        mutableStateOf(
            PiConnectionService.getClockSyncResult()
        )
    }

    var clockDiagnosticStatus by remember {
        mutableStateOf("Press Synchronize Clock to retrieve fresh values.")
    }

    var isSynchronizing by remember {
        mutableStateOf(false)
    }

    LazyColumn(
        modifier = Modifier
            .fillMaxSize()
            .padding(16.dp),
        contentPadding = PaddingValues(bottom = 24.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {

        // ---------------- TITLE ----------------

        item {
            Text(
                text = "Smart Cane",
                style = MaterialTheme.typography.headlineMedium
            )
        }

        // ---------------- CONNECTION ----------------

        item {
            Card(
                modifier = Modifier.fillMaxWidth()
            ) {
                Text(
                    text = connectionStatus,
                    modifier = Modifier.padding(16.dp),
                    style = MaterialTheme.typography.titleMedium
                )
            }
        }

        // ---------------- CAMERA ----------------

        item {
            Card(
                modifier = Modifier.fillMaxWidth()
            ) {
                Column(
                    modifier = Modifier.padding(12.dp),
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {

                    Text(
                        text = "Raspberry Pi Camera",
                        style = MaterialTheme.typography.titleMedium
                    )

                    Spacer(modifier = Modifier.height(8.dp))

                    if (videoFrame != null && !isDisconnected) {

                        Image(
                            bitmap = videoFrame!!.asImageBitmap(),
                            contentDescription = "Live camera feed",
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(240.dp),
                            contentScale = ContentScale.Fit
                        )

                    } else {

                        Text(
                            if (isDisconnected) {
                                "Smart Cane Disconnected"
                            } else {
                                "Waiting for camera feed..."
                            }
                        )
                    }
                }
            }
        }

        // ---------------- PERFORMANCE ----------------

        item {
            Card(
                modifier = Modifier.fillMaxWidth()
            ) {
                Column(
                    modifier = Modifier.padding(16.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp)
                ) {

                    Text(
                        text = "End-to-End Performance",
                        style = MaterialTheme.typography.titleLarge
                    )

                    Text(
                        text = "Capture-to-Android Response Latency",
                        style = MaterialTheme.typography.titleMedium
                    )

                    Text(
                        text = if (isDisconnected) {
                            "--"
                        } else {
                            formatLatencyPrecise(totalLatency)
                        },
                        style = MaterialTheme.typography.headlineSmall
                    )

                    MetricRow(
                        "Current Total Latency",
                        if (isDisconnected) "--"
                        else formatLatencyPrecise(currentLatency)
                    )

                    MetricRow(
                        "Minimum Total Latency",
                        if (isDisconnected) "--"
                        else formatLatencyPrecise(minimumLatency)
                    )

                    MetricRow(
                        "Maximum Total Latency",
                        if (isDisconnected) "--"
                        else formatLatencyPrecise(maximumLatency)
                    )

                    Text(
                        text = "Processing Breakdown",
                        style = MaterialTheme.typography.titleMedium
                    )

                    MetricRow(
                        "Pi Processing",
                        if (isDisconnected) "--"
                        else formatLatency(piProcessing)
                    )

                    MetricRow(
                        "Inference",
                        if (isDisconnected) "--"
                        else formatLatency(inference)
                    )

                    MetricRow(
                        "HTTP Round-Trip Time",
                        if (isDisconnected) "--"
                        else formatLatency(httpRoundTrip)
                    )

                    MetricRow(
                        "Android Post-Response Processing",
                        "--"
                    )

                    MetricRow(
                        "Clock Uncertainty (Estimate)",
                        if (isDisconnected) "--"
                        else formatLatency(clockUncertainty)
                    )

                    MetricRow(
                        "Camera FPS",
                        if (isDisconnected) {
                            "--"
                        } else {
                            String.format(
                                Locale.US,
                                "%.1f FPS",
                                fps
                            )
                        }
                    )

                    Text(
                        text =
                            "End-to-end latency is measured from the " +
                                    "Raspberry Pi camera-frame capture timestamp " +
                                    "to the time Android receives the corresponding " +
                                    "/detections HTTP response.",
                        style = MaterialTheme.typography.bodySmall
                    )

                    Text(
                        text =
                            "Each unique frame snapshot is counted once, " +
                                    "regardless of the number of detected objects " +
                                    "or whether the detection results change.",
                        style = MaterialTheme.typography.bodySmall
                    )

                    Text(
                        text =
                            "The Android polling interval is approximately " +
                                    "3 seconds. The measurement includes the wait " +
                                    "for the next polling request and HTTP response delay.",
                        style = MaterialTheme.typography.bodySmall
                    )

                    Text(
                        text =
                            "Clock synchronization uncertainty is reported " +
                                    "separately and is not subtracted from measured latency.",
                        style = MaterialTheme.typography.bodySmall
                    )

                    Text(
                        text =
                            "Inference time is included within Pi processing " +
                                    "time and must not be added separately.",
                        style = MaterialTheme.typography.bodySmall
                    )

                    Text(
                        text =
                            "Text-to-Speech is independent of latency sampling " +
                                    "and is not included in this measurement.",
                        style = MaterialTheme.typography.bodySmall
                    )
                }
            }
        }

        // ---------------- LATENCY DIAGNOSTICS ----------------

        item {
            Card(
                modifier = Modifier.fillMaxWidth()
            ) {
                Column(
                    modifier = Modifier.padding(16.dp),
                    verticalArrangement = Arrangement.spacedBy(10.dp)
                ) {

                    Text(
                        text = "Latency Diagnostics",
                        style = MaterialTheme.typography.titleLarge
                    )

                    MetricRow(
                        "Frame Timing Record",
                        timingRecord
                    )

                    MetricRow(
                        "Clock Synchronization",
                        clockSync
                    )

                    MetricRow(
                        "Latency Calculation",
                        latencyCalculation
                    )

                    MetricRow(
                        "Last Recorded Latency",
                        formatLatency(lastRecordedLatency)
                    )

                    Text(
                        text = "Diagnostic Message",
                        style = MaterialTheme.typography.titleMedium
                    )

                    Text(
                        text = diagnosticMessage,
                        style = MaterialTheme.typography.bodyMedium
                    )
                }
            }
        }

        // ---------------- CLOCK SYNCHRONIZATION DIAGNOSTICS ----------------

        item {
            Card(
                modifier = Modifier.fillMaxWidth()
            ) {
                Column(
                    modifier = Modifier.padding(16.dp),
                    verticalArrangement = Arrangement.spacedBy(10.dp)
                ) {

                    Text(
                        text = "Clock Synchronization Test",
                        style = MaterialTheme.typography.titleLarge
                    )

                    Text(
                        text = clockDiagnosticStatus,
                        style = MaterialTheme.typography.bodyMedium
                    )

                    Button(
                        onClick = {
                            coroutineScope.launch {

                                isSynchronizing = true
                                clockDiagnosticStatus =
                                    "Synchronizing with Raspberry Pi..."

                                try {
                                    val result =
                                        PiConnectionService.synchronizeClock(
                                            samples = 8
                                        )

                                    clockDiagnostic = result

                                    clockDiagnosticStatus =
                                        if (result != null) {
                                            "Clock synchronization successful."
                                        } else {
                                            "Synchronization failed. Check Pi connection."
                                        }

                                } catch (e: Exception) {
                                    clockDiagnosticStatus =
                                        "Error: ${e.message ?: "Unknown error"}"
                                } finally {
                                    isSynchronizing = false
                                }
                            }
                        },
                        enabled = !isSynchronizing && !isDisconnected
                    ) {
                        Text(
                            if (isSynchronizing) {
                                "Synchronizing..."
                            } else {
                                "Synchronize Clock"
                            }
                        )
                    }

                    if (clockDiagnostic != null) {

                        val result = clockDiagnostic!!

                        MetricRow(
                            "Android Midpoint Timestamp",
                            formatEpochSeconds(result.phoneEpochNs)
                        )

                        MetricRow(
                            "Raspberry Pi Timestamp",
                            formatEpochSeconds(result.piEpochNs)
                        )

                        MetricRow(
                            "Clock Offset (Pi - Android)",
                            formatOffset(result.piMinusPhoneOffsetNs)
                        )

                        MetricRow(
                            "Estimated Uncertainty",
                            formatLatencyDouble(result.estimatedUncertaintyMs)
                        )

                        MetricRow(
                            "Synchronization RTT",
                            formatLatencyDouble(result.roundTripMs)
                        )

                        Text(
                            text =
                                "A positive offset means the Raspberry Pi clock " +
                                        "is ahead of the Android clock. A negative " +
                                        "offset means it is behind.",
                            style = MaterialTheme.typography.bodySmall
                        )

                    } else {

                        Text(
                            text = "No clock synchronization measurement available.",
                            style = MaterialTheme.typography.bodySmall
                        )
                    }
                }
            }
        }

        // ---------------- SAMPLE COLLECTION ----------------

        item {
            Card(
                modifier = Modifier.fillMaxWidth()
            ) {
                Column(
                    modifier = Modifier.padding(16.dp),
                    verticalArrangement = Arrangement.spacedBy(10.dp)
                ) {

                    Text(
                        text = "Latency Sample Collection",
                        style = MaterialTheme.typography.titleLarge
                    )

                    Text(
                        text = "$sampleCount / 100 Snapshots Recorded",
                        style = MaterialTheme.typography.titleMedium
                    )

                    MetricRow(
                        "Remaining Snapshots",
                        "${(100 - sampleCount).coerceAtLeast(0)}"
                    )

                    Text(
                        text = reportStatus,
                        style = MaterialTheme.typography.bodyMedium
                    )

                    if (reportFilename != null) {
                        Text(
                            text = "Saved in Downloads:\n$reportFilename",
                            style = MaterialTheme.typography.bodySmall
                        )
                    }

                    // ADDED: Restart Measurement button

                    if (sampleCount >= 100 && reportFilename != null) {
                        Button(
                            onClick = {
                                // Reset only the latency measurement session.
                                // The generated Word document remains in Downloads.
                                LatencyReportManager.reset()
                                SmartCaneState.updateLatencySampleCount(0)
                            }
                        ) {
                            Text("Restart Measurement")
                        }
                    }
                }
            }
        }

        // ---------------- INDIVIDUAL SAMPLES ----------------

        item {
            Text(
                text = "Individual Snapshot Measurements",
                style = MaterialTheme.typography.titleLarge
            )
        }

        if (latencySamples.isEmpty()) {

            item {
                Card(
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text(
                        text = "No valid snapshot latency samples recorded yet.",
                        modifier = Modifier.padding(16.dp)
                    )
                }
            }

        } else {

            itemsIndexed(latencySamples) { index, sample ->

                Card(
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Column(
                        modifier = Modifier.padding(12.dp),
                        verticalArrangement = Arrangement.spacedBy(6.dp)
                    ) {

                        Text(
                            text = "Snapshot ${index + 1}",
                            style = MaterialTheme.typography.titleMedium
                        )

                        MetricRow(
                            "Capture-to-Response Latency",
                            formatLatencyDouble(sample.totalLatencyMs)
                        )

                        MetricRow(
                            "Pi Processing",
                            formatLatencyDouble(sample.piProcessingMs)
                        )

                        MetricRow(
                            "Inference",
                            formatLatencyDouble(sample.inferenceMs)
                        )

                        MetricRow(
                            "HTTP Round-Trip",
                            formatLatencyDouble(sample.httpRoundTripMs)
                        )

                        MetricRow(
                            "Android Post-Response Processing",
                            "--"
                        )

                        MetricRow(
                            "Clock Uncertainty",
                            formatLatencyDouble(sample.clockUncertaintyMs)
                        )
                    }
                }
            }
        }

        // ---------------- DETECTED OBJECTS ----------------

        item {
            Text(
                text = "Detected Objects (${detections.size})",
                style = MaterialTheme.typography.titleLarge
            )
        }

        if (detections.isEmpty()) {

            item {
                Text("No objects detected yet.")
            }

        } else {

            itemsIndexed(detections) { _, detection ->

                Card(
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Column(
                        modifier = Modifier.padding(12.dp),
                        verticalArrangement = Arrangement.spacedBy(4.dp)
                    ) {

                        Text(
                            text = detection.className,
                            style = MaterialTheme.typography.titleMedium
                        )

                        Text(
                            text = String.format(
                                Locale.US,
                                "Confidence: %.1f%%",
                                detection.confidence * 100
                            )
                        )

                        Text(
                            text = "Direction: ${detection.getDirection()}"
                        )
                    }
                }
            }
        }
    }
}

// ---------------- METRIC ROW ----------------

@Composable
private fun MetricRow(
    label: String,
    value: String
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {

        Text(
            text = label,
            style = MaterialTheme.typography.bodyMedium
        )

        Text(
            text = value,
            style = MaterialTheme.typography.titleMedium
        )
    }
}

// ---------------- FORMATTING ----------------

private fun formatLatency(value: Float?): String {

    return if (value != null && value.isFinite()) {
        String.format(
            Locale.US,
            "%.1f ms",
            value
        )
    } else {
        "--"
    }
}

private fun formatLatencyPrecise(value: Float?): String {

    return if (value != null && value.isFinite()) {
        String.format(
            Locale.US,
            "%.2f ms",
            value
        )
    } else {
        "--"
    }
}

private fun formatLatencyDouble(value: Double?): String {

    return if (value != null && value.isFinite()) {
        String.format(
            Locale.US,
            "%.2f ms",
            value
        )
    } else {
        "--"
    }
}

private fun formatEpochSeconds(epochNs: Long): String {

    return String.format(
        Locale.US,
        "%.3f s",
        epochNs / 1_000_000_000.0
    )
}

private fun formatOffset(offsetNs: Long): String {

    return String.format(
        Locale.US,
        "%+.3f ms",
        offsetNs / 1_000_000.0
    )
}