package com.runelitetablet.telemetry

import android.os.Process
import android.os.SystemClock
import kotlinx.serialization.Serializable
import java.io.File
import java.util.UUID

@Serializable
data class ClockAnchorsV1(
    val realtimeMs: Long,
    val monotonicNs: Long,
    val boottimeNs: Long,
    val bootId: String,
    val handshakeRoundTripNs: Long? = null
)

@Serializable
data class CaptureManifestV1(
    val schemaVersion: Int = CAPTURE_MANIFEST_SCHEMA_VERSION,
    val runId: String,
    val backend: String,
    val telemetryMode: String,
    val targetHz: Int,
    val captureDir: String,
    val processId: Int,
    val clocks: ClockAnchorsV1
)

data class CaptureSession(
    val manifest: CaptureManifestV1,
    val recorder: FrameFlightRecorder
)

/**
 * One process-local capture identity. The run ID is passed through the launcher
 * to native/X11/VirGL producers; this registry only owns app-process events.
 */
object CaptureSessionRegistry {
    @Volatile
    var active: CaptureSession? = null
        private set

    @Synchronized
    fun start(
        backend: RendererBackend,
        telemetryMode: TelemetryMode,
        targetHz: Int,
        captureDir: String? = null,
        runId: String = UUID.randomUUID().toString()
    ): CaptureSession {
        require(targetHz > 0) { "targetHz must be positive" }
        val pid = Process.myPid()
        val resolvedDir = captureDir ?: "\$HOME/.rlt/captures/$runId"
        val clocks = ClockAnchorsV1(
            realtimeMs = System.currentTimeMillis(),
            monotonicNs = System.nanoTime(),
            boottimeNs = SystemClock.elapsedRealtimeNanos(),
            bootId = readBootId()
        )
        return CaptureSession(
            manifest = CaptureManifestV1(
                runId = runId,
                backend = backend.envValue,
                telemetryMode = telemetryMode.envValue,
                targetHz = targetHz,
                captureDir = resolvedDir,
                processId = pid,
                clocks = clocks
            ),
            recorder = FrameFlightRecorder(
                runId = runId,
                backend = backend,
                mode = telemetryMode,
                processId = pid
            )
        ).also { active = it }
    }

    @Synchronized
    fun stop(): CaptureSession? = active.also { active = null }

    private fun readBootId(): String = runCatching {
        File("/proc/sys/kernel/random/boot_id").readText().trim()
    }.getOrDefault("unavailable")
}
