package com.runelitetablet.telemetry

import android.content.Context

/**
 * Application seam for local capture lifecycle.
 *
 * UI, launch, and presentation code never coordinate the registry and
 * background exporter independently.
 */
interface CaptureController {
    val active: CaptureSession?

    fun start(
        backend: RendererBackend,
        telemetryMode: TelemetryMode,
        targetHz: Int,
    ): CaptureSession

    fun stop()
}

class LocalCaptureController(context: Context) : CaptureController {
    private val applicationContext = context.applicationContext

    override val active: CaptureSession?
        get() = CaptureSessionRegistry.active

    override fun start(
        backend: RendererBackend,
        telemetryMode: TelemetryMode,
        targetHz: Int,
    ): CaptureSession {
        val session = CaptureSessionRegistry.start(backend, telemetryMode, targetHz)
        TelemetryExporter.start(applicationContext, session)
        return session
    }

    override fun stop() {
        TelemetryExporter.stop()
        CaptureSessionRegistry.stop()
    }
}
