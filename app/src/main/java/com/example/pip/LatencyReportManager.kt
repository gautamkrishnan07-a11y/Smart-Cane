
package com.example.pip

import android.content.ContentValues
import android.content.Context
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import android.util.Log
import java.io.BufferedOutputStream
import java.io.OutputStream
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import kotlin.math.ceil
import kotlin.math.sqrt
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

data class LatencySample(
    val totalLatencyMs: Double,
    val piProcessingMs: Double,
    val inferenceMs: Double,
    val httpRoundTripMs: Double,
    val androidProcessingMs: Double,
    val clockUncertaintyMs: Double
)

object LatencyReportManager {

    private const val TAG = "LatencyReportManager"
    private const val REQUIRED_SAMPLES = 100

    private val samples = mutableListOf<LatencySample>()

    private val _samplesFlow =
        MutableStateFlow<List<LatencySample>>(emptyList())

    val samplesFlow: StateFlow<List<LatencySample>> =
        _samplesFlow.asStateFlow()

    private val _reportStatusFlow =
        MutableStateFlow("Collecting samples")

    val reportStatusFlow: StateFlow<String> =
        _reportStatusFlow.asStateFlow()

    private val _reportFilenameFlow =
        MutableStateFlow<String?>(null)

    val reportFilenameFlow: StateFlow<String?> =
        _reportFilenameFlow.asStateFlow()

    private var reportGenerated = false

    @Synchronized
    fun addSample(context: Context, sample: LatencySample) {

        if (reportGenerated) return

        val values = listOf(
            sample.totalLatencyMs,
            sample.piProcessingMs,
            sample.inferenceMs,
            sample.httpRoundTripMs,
            sample.androidProcessingMs,
            sample.clockUncertaintyMs
        )

        if (values.any { !it.isFinite() || it < 0.0 }) {
            Log.w(TAG, "Invalid latency sample ignored")
            return
        }

        if (samples.size >= REQUIRED_SAMPLES) {
            generateReportIfReady(context)
            return
        }

        samples.add(sample)

        _samplesFlow.value = samples.toList()

        _reportStatusFlow.value =
            "Collecting samples: ${samples.size}/$REQUIRED_SAMPLES"

        Log.i(
            TAG,
            "Latency sample ${samples.size}/$REQUIRED_SAMPLES recorded"
        )

        if (samples.size == REQUIRED_SAMPLES) {
            generateReportIfReady(context)
        }
    }

    @Synchronized
    private fun generateReportIfReady(context: Context) {

        if (reportGenerated || samples.size != REQUIRED_SAMPLES) {
            return
        }

        try {
            val filename = createReport(
                context.applicationContext,
                samples.toList()
            )

            reportGenerated = true

            _reportFilenameFlow.value = filename
            _reportStatusFlow.value = "Report saved: $filename"

            Log.i(TAG, "Word report created successfully: $filename")

        } catch (e: Exception) {

            reportGenerated = false

            _reportStatusFlow.value =
                "Report generation failed. Samples retained; retry pending."

            Log.e(TAG, "Word report generation failed", e)
        }
    }

    @Synchronized
    fun reset() {
        samples.clear()
        reportGenerated = false

        _samplesFlow.value = emptyList()
        _reportStatusFlow.value = "Collecting samples"
        _reportFilenameFlow.value = null

        Log.i(TAG, "Latency sample collection reset")
    }

    @Synchronized
    fun getSampleCount(): Int = samples.size

    @Synchronized
    fun getSamples(): List<LatencySample> = samples.toList()

    // ---------------- REPORT CREATION ----------------

    private fun createReport(
        context: Context,
        data: List<LatencySample>
    ): String {

        require(data.size == REQUIRED_SAMPLES) {
            "Exactly 100 samples are required"
        }

        val timestamp = SimpleDateFormat(
            "yyyyMMdd_HHmmss",
            Locale.getDefault()
        ).format(Date())

        val filename = "SmartCane_Latency_Report_$timestamp.docx"

        val outputStream = createOutputStream(context, filename)

        ZipOutputStream(BufferedOutputStream(outputStream)).use { zip ->

            addZipEntry(
                zip,
                "[Content_Types].xml",
                contentTypesXml()
            )

            addZipEntry(
                zip,
                "_rels/.rels",
                relationshipsXml()
            )

            addZipEntry(
                zip,
                "word/document.xml",
                documentXml(data, timestamp)
            )

            addZipEntry(
                zip,
                "word/styles.xml",
                stylesXml()
            )

            addZipEntry(
                zip,
                "word/_rels/document.xml.rels",
                documentRelationshipsXml()
            )
        }

        return filename
    }

    // ---------------- OUTPUT LOCATION ----------------

    private fun createOutputStream(
        context: Context,
        filename: String
    ): OutputStream {

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {

            val values = ContentValues().apply {

                put(
                    MediaStore.MediaColumns.DISPLAY_NAME,
                    filename
                )

                put(
                    MediaStore.MediaColumns.MIME_TYPE,
                    "application/vnd.openxmlformats-officedocument.wordprocessingml.document"
                )

                put(
                    MediaStore.MediaColumns.RELATIVE_PATH,
                    Environment.DIRECTORY_DOWNLOADS
                )
            }

            val uri = context.contentResolver.insert(
                MediaStore.Downloads.EXTERNAL_CONTENT_URI,
                values
            ) ?: throw IllegalStateException(
                "Unable to create report file"
            )

            return context.contentResolver.openOutputStream(uri)
                ?: throw IllegalStateException(
                    "Unable to open report output stream"
                )
        }

        val directory =
            context.getExternalFilesDir(Environment.DIRECTORY_DOWNLOADS)
                ?: throw IllegalStateException(
                    "Downloads directory unavailable"
                )

        if (!directory.exists()) {
            directory.mkdirs()
        }

        return java.io.File(directory, filename).outputStream()
    }

    // ---------------- ZIP CONTENT ----------------

    private fun addZipEntry(
        zip: ZipOutputStream,
        name: String,
        content: String
    ) {
        zip.putNextEntry(ZipEntry(name))
        zip.write(content.toByteArray(Charsets.UTF_8))
        zip.closeEntry()
    }

    // ---------------- WORD DOCUMENT ----------------

    private fun documentXml(
        data: List<LatencySample>,
        timestamp: String
    ): String {

        val totalValues = data.map { it.totalLatencyMs }
        val sorted = totalValues.sorted()
        val mean = totalValues.average()

        val median = (sorted[49] + sorted[50]) / 2.0
        val minimum = sorted.first()
        val maximum = sorted.last()

        val variance = totalValues.sumOf {
            val difference = it - mean
            difference * difference
        } / (totalValues.size - 1)

        val standardDeviation = sqrt(variance)

        val p95 =
            sorted[ceil(0.95 * sorted.size).toInt() - 1]

        val xml = StringBuilder()

        xml.append(
            """
            <?xml version="1.0" encoding="UTF-8" standalone="yes"?>
            <w:document
                xmlns:w="http://schemas.openxmlformats.org/wordprocessingml/2006/main">
                <w:body>
            """.trimIndent()
        )

        xml.append(
            paragraph(
                "SMART CANE LATENCY EVALUATION",
                true,
                30
            )
        )

        xml.append(
            paragraph(
                "100-Sample Experimental Report",
                true,
                24
            )
        )

        xml.append(paragraph("Generated: $timestamp"))
        xml.append(paragraph(""))

        // 1. Statistical Summary

        xml.append(
            paragraph(
                "1. Statistical Summary",
                true,
                24
            )
        )

        xml.append(
            table(
                listOf("Parameter", "Value"),
                listOf(
                    listOf("Number of samples", data.size.toString()),
                    listOf("Mean latency", format(mean) + " ms"),
                    listOf("Median latency", format(median) + " ms"),
                    listOf("Minimum latency", format(minimum) + " ms"),
                    listOf("Maximum latency", format(maximum) + " ms"),
                    listOf("Standard deviation", format(standardDeviation) + " ms"),
                    listOf("95th percentile latency", format(p95) + " ms")
                )
            )
        )

        xml.append(paragraph(""))

        // 2. Individual Measurements

        xml.append(
            paragraph(
                "2. Individual Latency Measurements",
                true,
                24
            )
        )

        xml.append(
            paragraph(
                "Each entry represents one unique Raspberry Pi camera-frame snapshot " +
                        "returned in an Android detection-polling response."
            )
        )

        val individualRows = data.mapIndexed { index, sample ->
            listOf(
                "Sample ${index + 1}",
                "${format(sample.totalLatencyMs)} ms"
            )
        }

        xml.append(
            table(
                listOf("Snapshot", "Capture-to-Android Response (ms)"),
                individualRows
            )
        )

        xml.append(paragraph(""))

        // 3. Component Measurements

        xml.append(
            paragraph(
                "3. Detailed Component Measurements",
                true,
                24
            )
        )

        val componentRows = data.mapIndexed { index, sample ->
            listOf(
                (index + 1).toString(),
                format(sample.totalLatencyMs),
                format(sample.piProcessingMs),
                format(sample.inferenceMs),
                format(sample.httpRoundTripMs),
                format(sample.androidProcessingMs),
                format(sample.clockUncertaintyMs)
            )
        }

        xml.append(
            table(
                listOf(
                    "No.",
                    "Total (ms)",
                    "Pi processing (ms)",
                    "Inference (ms)",
                    "HTTP RTT (ms)",
                    "Android post-response (ms)",
                    "Clock uncertainty (ms)"
                ),
                componentRows
            )
        )

        xml.append(paragraph(""))

        // 4. Experimental Configuration

        xml.append(
            paragraph(
                "4. Experimental Configuration",
                true,
                24
            )
        )

        xml.append(paragraph("Processing platform: Raspberry Pi 5"))
        xml.append(paragraph("Object detection model: YOLOv8s TensorFlow Lite"))
        xml.append(paragraph("Android application: Kotlin"))
        xml.append(paragraph("Audio output: Android Text-to-Speech (excluded from latency measurement)"))
        xml.append(paragraph("Detection polling interval: 3 seconds"))
        xml.append(paragraph("Number of recorded samples: 100"))

        xml.append(paragraph(""))

        // 5. Measurement Methodology

        xml.append(
            paragraph(
                "5. Measurement Methodology",
                true,
                24
            )
        )

        xml.append(
            paragraph(
                "End-to-end latency is measured from the Raspberry Pi camera-frame " +
                        "capture timestamp to the time Android receives the corresponding " +
                        "HTTP response from the /detections endpoint. The Android response " +
                        "timestamp is converted to the Raspberry Pi clock domain using " +
                        "the estimated clock offset."
            )
        )

        xml.append(
            paragraph(
                "The measurement includes Raspberry Pi frame processing, the wait until " +
                        "the next Android polling request, and HTTP/network response delay. " +
                        "It ends when the Android application receives the response; " +
                        "subsequent Android processing and speech generation are excluded."
            )
        )

        xml.append(
            paragraph(
                "Pi processing latency includes preprocessing, inference, " +
                        "post-processing, and JPEG encoding. Inference time is a subset " +
                        "of Pi processing time and must not be added again to the total."
            )
        )

        xml.append(
            paragraph(
                "Each unique frame is identified by its captureEpochNs timestamp and is " +
                        "counted at most once. A frame produces one sample regardless of " +
                        "whether it contains zero, one, or multiple detected objects, and " +
                        "regardless of whether the detection results changed from the " +
                        "previous poll. Repeated polling responses containing the same " +
                        "frame timestamp are not counted again. The configured polling " +
                        "interval is approximately three seconds; it does not mean the " +
                        "camera is triggered to capture a frame exactly every three seconds."
            )
        )

        xml.append(
            paragraph(
                "The Android post-response processing field is stored as 0.00 because " +
                        "this measurement ends at HTTP response receipt. That value is a " +
                        "placeholder indicating that post-response processing was not " +
                        "measured; it must not be interpreted as a measured zero-duration " +
                        "operation. Text-to-Speech timing is not part of this latency metric."
            )
        )

        xml.append(paragraph(""))

        // 6. Raw Measurement Data

        xml.append(
            paragraph(
                "6. Raw Measurement Data",
                true,
                24
            )
        )

        xml.append(
            paragraph(
                "All 100 individual observations are included in this document. " +
                        "The statistical values above are calculated from the recorded " +
                        "end-to-end latency observations."
            )
        )

        xml.append(
            """
                <w:sectPr>
                    <w:pgSz w:w="16838" w:h="11906" w:orient="landscape"/>
                    <w:pgMar w:top="720" w:right="720"
                        w:bottom="720" w:left="720"/>
                </w:sectPr>
                </w:body>
            </w:document>
            """.trimIndent()
        )

        return xml.toString()
    }

    // ---------------- PARAGRAPH GENERATION ----------------

    private fun paragraph(
        text: String,
        bold: Boolean = false,
        fontSize: Int = 22
    ): String {

        val safeText = escapeXml(text)
        val boldTag = if (bold) "<w:b/>" else ""

        return """
            <w:p>
                <w:r>
                    <w:rPr>
                        $boldTag
                        <w:sz w:val="$fontSize"/>
                    </w:rPr>
                    <w:t xml:space="preserve">$safeText</w:t>
                </w:r>
            </w:p>
        """.trimIndent()
    }

    // ---------------- TABLE GENERATION ----------------

    private fun table(
        headers: List<String>,
        rows: List<List<String>>
    ): String {

        val xml = StringBuilder()

        xml.append(
            """
            <w:tbl>
                <w:tblPr>
                    <w:tblBorders>
                        <w:top w:val="single" w:sz="4"/>
                        <w:left w:val="single" w:sz="4"/>
                        <w:bottom w:val="single" w:sz="4"/>
                        <w:right w:val="single" w:sz="4"/>
                        <w:insideH w:val="single" w:sz="4"/>
                        <w:insideV w:val="single" w:sz="4"/>
                    </w:tblBorders>
                </w:tblPr>
            """.trimIndent()
        )

        xml.append(tableRow(headers, true))

        rows.forEach { row ->
            xml.append(tableRow(row, false))
        }

        xml.append("</w:tbl>")

        return xml.toString()
    }

    private fun tableRow(
        cells: List<String>,
        header: Boolean
    ): String {

        val xml = StringBuilder("<w:tr>")

        cells.forEach { cell ->

            val boldTag = if (header) "<w:b/>" else ""

            xml.append(
                """
                <w:tc>
                    <w:tcPr>
                        <w:tcW w:w="2200" w:type="auto"/>
                    </w:tcPr>
                    <w:p>
                        <w:r>
                            <w:rPr>
                                $boldTag
                                <w:sz w:val="16"/>
                            </w:rPr>
                            <w:t xml:space="preserve">${escapeXml(cell)}</w:t>
                        </w:r>
                    </w:p>
                </w:tc>
                """.trimIndent()
            )
        }

        xml.append("</w:tr>")

        return xml.toString()
    }

    // ---------------- FORMATTING ----------------

    private fun format(value: Double): String =
        String.format(Locale.US, "%.2f", value)

    private fun escapeXml(value: String): String =
        value
            .replace("&", "&amp;")
            .replace("<", "&lt;")
            .replace(">", "&gt;")
            .replace("\"", "&quot;")
            .replace("'", "&apos;")

    // ---------------- DOCX STRUCTURE ----------------

    private fun contentTypesXml() = """
        <?xml version="1.0" encoding="UTF-8" standalone="yes"?>
        <Types xmlns="http://schemas.openxmlformats.org/package/2006/content-types">
            <Default Extension="rels"
                ContentType="application/vnd.openxmlformats-package.relationships+xml"/>
            <Default Extension="xml" ContentType="application/xml"/>
            <Override PartName="/word/document.xml"
                ContentType="application/vnd.openxmlformats-officedocument.wordprocessingml.document.main+xml"/>
            <Override PartName="/word/styles.xml"
                ContentType="application/vnd.openxmlformats-officedocument.wordprocessingml.styles+xml"/>
        </Types>
    """.trimIndent()

    private fun relationshipsXml() = """
        <?xml version="1.0" encoding="UTF-8" standalone="yes"?>
        <Relationships xmlns="http://schemas.openxmlformats.org/package/2006/relationships">
            <Relationship Id="rId1"
                Type="http://schemas.openxmlformats.org/officeDocument/2006/relationships/officeDocument"
                Target="word/document.xml"/>
        </Relationships>
    """.trimIndent()

    private fun documentRelationshipsXml() = """
        <?xml version="1.0" encoding="UTF-8" standalone="yes"?>
        <Relationships xmlns="http://schemas.openxmlformats.org/package/2006/relationships"/>
    """.trimIndent()

    private fun stylesXml() = """
        <?xml version="1.0" encoding="UTF-8" standalone="yes"?>
        <w:styles xmlns:w="http://schemas.openxmlformats.org/wordprocessingml/2006/main">
            <w:style w:type="paragraph" w:default="1" w:styleId="Normal">
                <w:name w:val="Normal"/>
                <w:rPr>
                    <w:rFonts w:ascii="Arial" w:hAnsi="Arial"/>
                    <w:sz w:val="22"/>
                </w:rPr>
            </w:style>
        </w:styles>
    """.trimIndent()
}
