package com.runelitetablet.telemetry

import android.content.Context
import android.util.Log
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
                Log.e(LOG_TAG, "Background telemetry export failed", error)
            }
        }

        @Synchronized
        fun close() {
            runCatching { output?.flush() }
            runCatching { output?.close() }
            output = null
        }

        private fun writeManifest() {
            val file = File(directory, "app-capture-manifest-v1.json")
            if (!file.exists()) {
                file.writeText(json.encodeToString(session.manifest))
            }
        }

        private fun writeEvents() {
            while (true) {
                val events = session.recorder.drain(MAX_DRAIN_BATCH)
                if (events.isEmpty()) break
                val stream = output ?: BufferedOutputStream(
                    FileOutputStream(eventsFile, true),
                    OUTPUT_BUFFER_BYTES
                ).also { output = it }
                events.forEach { stream.write(FrameEventBinaryCodec.encode(it)) }
                stream.flush()
            }
        }

        private fun writeSummary() {
            File(directory, "app-telemetry-summary-v1.json")
                .writeText(json.encodeToString(session.recorder.snapshotSummary()))
        }
    }

    private const val FLUSH_INTERVAL_SECONDS = 1L
    private const val MAX_DRAIN_BATCH = 4_096
    private const val OUTPUT_BUFFER_BYTES = 256 * 1024
    private const val LOG_TAG = "RLT-Telemetry"
}
