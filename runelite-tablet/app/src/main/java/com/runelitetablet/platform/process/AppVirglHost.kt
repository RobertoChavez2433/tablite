package com.runelitetablet.platform.process

import android.content.Context
import com.runelitetablet.logging.AppLog
import java.io.File
import java.io.FileOutputStream
import java.util.concurrent.TimeUnit

/**
 * Gated app-UID owner for the packaged VirGL vtest host.
 *
 * Keeping VirGL and the AHardwareBuffer consumer in com.runelitetablet is the
 * prerequisite for a same-UID SCM_RIGHTS frame handoff. The initial gate still
 * uses the measured completed-frame TCP bridge so the process/renderer route
 * can be verified independently before changing frame ownership.
 */
class AppVirglHost private constructor(
    private val process: Process,
    private val outputThread: Thread,
    val logFile: File,
) {
    val isAlive: Boolean
        get() = process.isAlive

    fun stop() {
        if (!process.isAlive) return
        AppLog.i(TAG, "Stopping app-UID VirGL process=$process")
        process.destroy()
        if (!process.waitFor(STOP_TIMEOUT_MS, TimeUnit.MILLISECONDS)) {
            AppLog.w(TAG, "App-UID VirGL did not stop gracefully; destroying process=$process")
            process.destroyForcibly()
        }
        outputThread.join(STOP_TIMEOUT_MS)
    }

    companion object {
        private const val TAG = "AppVirglHost"
        private const val EXECUTABLE_NAME = "libvirgl_test_server_app.so"
        private const val STOP_TIMEOUT_MS = 1_000L

        fun start(
            context: Context,
            vtestTcpPort: Int,
            frameTcpPort: Int,
            frameAhbSocket: String?,
            ahbProbeWidth: Int,
            ahbProbeHeight: Int,
            ahbProbeFrames: Int,
            captureId: String,
            telemetryMode: String,
        ): AppVirglHost? {
            require(vtestTcpPort in 1..65535) { "Invalid vtest TCP port: $vtestTcpPort" }
            require(
                frameTcpPort in 1..65535 || !frameAhbSocket.isNullOrBlank()
            ) { "A frame TCP port or AHB socket is required" }

            val nativeLibraryDir = context.applicationInfo.nativeLibraryDir
            val executable = File(nativeLibraryDir, EXECUTABLE_NAME)
            if (!executable.canExecute()) {
                AppLog.e(TAG, "Packaged VirGL executable is unavailable: $executable")
                return null
            }

            val safeCaptureId = captureId
                .ifBlank { "unknown" }
                .replace(Regex("[^A-Za-z0-9._-]"), "_")
                .take(96)
            val logFile = File(context.filesDir, "app-virgl-$safeCaptureId.log")
            val command = listOf(
                executable.absolutePath,
                // Keep accepting sequential Mesa clients: launcher diagnostics
                // run glxinfo before RuneLite opens the production connection.
                "--no-fork",
                "--tcp-port",
                vtestTcpPort.toString(),
            )

            return try {
                val builder = ProcessBuilder(command)
                    .redirectErrorStream(true)
                builder.environment().apply {
                    put("LD_LIBRARY_PATH", nativeLibraryDir)
                    put("VTEST_TCP_PORT", vtestTcpPort.toString())
                    if (!frameAhbSocket.isNullOrBlank()) {
                        put("RLT_FRAME_AHB_SOCKET", frameAhbSocket)
                        if (ahbProbeFrames > 0) {
                            put("RLT_FRAME_AHB_PROBE", "1")
                            put("RLT_FRAME_AHB_PROBE_WIDTH", ahbProbeWidth.toString())
                            put("RLT_FRAME_AHB_PROBE_HEIGHT", ahbProbeHeight.toString())
                            put("RLT_FRAME_AHB_PROBE_FRAMES", ahbProbeFrames.toString())
                        } else {
                            remove("RLT_FRAME_AHB_PROBE")
                            remove("RLT_FRAME_AHB_PROBE_WIDTH")
                            remove("RLT_FRAME_AHB_PROBE_HEIGHT")
                            remove("RLT_FRAME_AHB_PROBE_FRAMES")
                        }
                        remove("RLT_FRAME_TCP_PORT")
                    } else {
                        put("RLT_FRAME_TCP_PORT", frameTcpPort.toString())
                        remove("RLT_FRAME_AHB_PROBE")
                        remove("RLT_FRAME_AHB_PROBE_WIDTH")
                        remove("RLT_FRAME_AHB_PROBE_HEIGHT")
                        remove("RLT_FRAME_AHB_PROBE_FRAMES")
                        remove("RLT_FRAME_AHB_SOCKET")
                    }
                    put("RLT_FRAME_STREAM_BYPASS_X11_DISPLAY", "1")
                    put("RLT_CAPTURE_ID", captureId.ifBlank { "unknown" })
                    put("RLT_TELEMETRY_MODE", telemetryMode.ifBlank { "SUMMARY" })
                    put("VIRGL_RENDERER_THREAD", "1")
                    put("VIRGL_RENDERER_ASYNC", "1")
                    // The Termux-owned telemetry page is intentionally not passed
                    // across UIDs. Sequence transport moves into the vtest protocol
                    // after this process/renderer gate passes.
                    remove("RLT_VTEST_TELEMETRY_PATH")
                }
                val process = builder.start()
                val outputThread = Thread({
                    runCatching {
                        FileOutputStream(logFile, true).bufferedWriter().use { writer ->
                            process.inputStream.bufferedReader().useLines { lines ->
                                lines.forEach { line ->
                                    writer.appendLine(line)
                                    writer.flush()
                                    if (line.startsWith("RLT_")) {
                                        AppLog.nativeIngress("app_virgl", line)
                                    }
                                }
                            }
                        }
                    }.onFailure {
                        AppLog.e(TAG, "App-UID VirGL output collector failed", it)
                    }
                }, "app-virgl-output").apply {
                    isDaemon = true
                    start()
                }
                AppLog.i(
                    TAG,
                    "Started app-UID VirGL process=$process vtestTcpPort=$vtestTcpPort " +
                        "frameTransport=${frameAhbSocket?.let { "ahb:@$it" } ?: "tcp:$frameTcpPort"} " +
                        "captureId=$safeCaptureId log=$logFile"
                )
                AppVirglHost(process, outputThread, logFile)
            } catch (t: Throwable) {
                AppLog.e(TAG, "Failed to start app-UID VirGL command=$command", t)
                null
            }
        }
    }
}
