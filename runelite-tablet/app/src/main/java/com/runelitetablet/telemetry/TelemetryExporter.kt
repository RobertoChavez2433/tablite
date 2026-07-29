package com.runelitetablet.telemetry

import android.content.Context
import com.runelitetablet.logging.AppLog
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.io.BufferedOutputStream
import java.io.File
import java.io.FileOutputStream
import java.util.concurrent.Executors
import java.util.concurrent.ScheduledFuture
import java.util.concurrent.TimeUnit

/**
 * Background-only local exporter. No capture leaves the device.
 */
object TelemetryExporter {
    private val executor = Executors.newSingleThreadScheduledExecutor { runnable ->
        Thread(runnable, "rlt-telemetry-export").apply { isDaemon = true }
    }
    private val json = Json {
        prettyPrint = true
        encodeDefaults = true
    }
    private var task: ScheduledFuture<*>? = null
    private var state: ExportState? = null

    @Synchronized
    fun start(context: Context, session: CaptureSession) {
        task?.cancel(false)
        state?.close()

        val directory = File(context.filesDir, "captures/${session.manifest.runId}")
        directory.mkdirs()
        TelemetryCostTracker.reset()
        val newState = ExportState(directory, session)
        state = newState
        executor.execute { newState.flush() }
        task = executor.scheduleAtFixedRate(
            { newState.flush() },
            FLUSH_INTERVAL_SECONDS,
            FLUSH_INTERVAL_SECONDS,
            TimeUnit.SECONDS
        )
    }

    @Synchronized
    fun stop() {
        task?.cancel(false)
        task = null
        state?.let { current ->
            executor.execute {
                current.flush()
                current.close()
            }
        }
        state = null
    }

    private class ExportState(
        private val directory: File,
        private val session: CaptureSession
    ) {
        private val eventsFile = File(directory, "app-frame-events-v1.bin")
        private var output: BufferedOutputStream? = null

        @Synchronized
        fun flush() {
            try {
                writeManifest()
                writeEvents()
                writeSummary()
            } catch (error: Exception) {
                session.recorder.recordTelemetryLoss()
                AppLog.e("PERF", "Background telemetry export failed", error)
            }
        }

        @Synchronized
        fun close() {
            runCatching { output?.flush() }
            runCatching { output?.close() }
            output = null
        }

        private fun writeManifest() {
            val token = TelemetryCostTracker.begin(
                TelemetryCostTracker.Stage.EXPORT_MANIFEST
            )
            val file = File(directory, "app-capture-manifest-v1.json")
            try {
                if (!file.exists()) {
                    val encoded = json.encodeToString(session.manifest)
                    file.writeText(encoded)
                    TelemetryCostTracker.addBytes(
                        TelemetryCostTracker.Stage.EXPORT_MANIFEST,
                        encoded.toByteArray().size.toLong()
                    )
                }
            } finally {
                TelemetryCostTracker.end(TelemetryCostTracker.Stage.EXPORT_MANIFEST, token)
            }
        }

        private fun writeEvents() {
            val token = TelemetryCostTracker.begin(TelemetryCostTracker.Stage.EXPORT_EVENTS)
            var writtenBytes = 0L
            try {
                while (true) {
                    val events = session.recorder.drain(MAX_DRAIN_BATCH)
                    if (events.isEmpty()) break
                    val stream = output ?: BufferedOutputStream(
                        FileOutputStream(eventsFile, true),
                        OUTPUT_BUFFER_BYTES
                    ).also { output = it }
                    events.forEach {
                        val encoded = FrameEventBinaryCodec.encode(it)
                        stream.write(encoded)
                        writtenBytes += encoded.size.toLong()
                    }
                    stream.flush()
                }
            } finally {
                TelemetryCostTracker.addBytes(
                    TelemetryCostTracker.Stage.EXPORT_EVENTS,
                    writtenBytes
                )
                TelemetryCostTracker.end(TelemetryCostTracker.Stage.EXPORT_EVENTS, token)
            }
        }

        private fun writeSummary() {
            val token = TelemetryCostTracker.begin(TelemetryCostTracker.Stage.EXPORT_SUMMARY)
            try {
                val recorderSummary = session.recorder.snapshotSummary()
                val encodedSummary = json.encodeToString(recorderSummary)
                File(directory, "app-telemetry-summary-v1.json").writeText(encodedSummary)

                val cost = TelemetryCostTracker.snapshot(
                    runId = session.manifest.runId,
                    mode = session.manifest.telemetryMode
                )
                val recorderCost = TelemetryStageCostV1(
                    stage = TelemetryCostTracker.Stage.FRAME_RECORDER.wireName,
                    calls = recorderSummary.recordedEvents,
                    measuredSamples = recorderSummary.recordedEvents,
                    measuredWallNs = recorderSummary.telemetryCpuNs,
                    measuredThreadCpuNs = 0,
                    estimatedWallNs = recorderSummary.telemetryCpuNs,
                    estimatedThreadCpuNs = 0,
                    bytes = 0,
                    dropped = recorderSummary.lostEvents,
                    queueHighWaterMark = recorderSummary.queueHighWaterMark
                )
                val encodedCost = json.encodeToString(
                    cost.copy(stages = listOf(recorderCost) + cost.stages)
                )
                File(directory, "app-telemetry-cost-v1.json").writeText(encodedCost)
                TelemetryCostTracker.addBytes(
                    TelemetryCostTracker.Stage.EXPORT_SUMMARY,
                    encodedSummary.toByteArray().size.toLong() +
                        encodedCost.toByteArray().size.toLong()
                )
            } finally {
                TelemetryCostTracker.end(TelemetryCostTracker.Stage.EXPORT_SUMMARY, token)
            }
        }
    }

    private const val FLUSH_INTERVAL_SECONDS = 1L
    private const val MAX_DRAIN_BATCH = 4_096
    private const val OUTPUT_BUFFER_BYTES = 256 * 1024
}
