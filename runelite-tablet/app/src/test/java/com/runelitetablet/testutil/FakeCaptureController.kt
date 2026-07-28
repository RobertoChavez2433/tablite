package com.runelitetablet.testutil

import com.runelitetablet.telemetry.CaptureController
import com.runelitetablet.telemetry.CaptureSession
import com.runelitetablet.telemetry.CaptureSessionRegistry
import com.runelitetablet.telemetry.RendererBackend
import com.runelitetablet.telemetry.TelemetryMode

class FakeCaptureController : CaptureController {
    override val active: CaptureSession?
        get() = CaptureSessionRegistry.active

    override fun start(
        backend: RendererBackend,
        telemetryMode: TelemetryMode,
        targetHz: Int,
    ): CaptureSession = CaptureSessionRegistry.start(backend, telemetryMode, targetHz)

    override fun stop() {
        CaptureSessionRegistry.stop()
    }
}
