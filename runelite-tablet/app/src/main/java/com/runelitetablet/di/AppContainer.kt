package com.runelitetablet.di

import android.content.Context
import android.os.SystemClock
import com.runelitetablet.BuildConfig
import com.runelitetablet.logging.AppLog
import com.runelitetablet.logging.AgentDiagnosticsSession
import com.runelitetablet.logging.DebugLogServer
import com.runelitetablet.perf.PerfDashboard
import com.runelitetablet.perf.ProducerRateTracker
import com.runelitetablet.presentation.PresentationBackends
import com.runelitetablet.presentation.hybrid.ExternalXlorieLoader
import com.runelitetablet.termux.TermuxProcessPin
import com.runelitetablet.telemetry.CaptureController
import com.runelitetablet.telemetry.LocalCaptureController
import com.runelitetablet.telemetry.NativeTelemetryIngressServer
import com.runelitetablet.ui.DisplayPreferences
import com.runelitetablet.ui.LaunchPreferences

class AppContainer(private val context: Context) {
    private val creationStart = SystemClock.elapsedRealtime()

    val core = CoreModule(context).also {
        it.logger.lifecycle("AppContainer: creation started")
        AppLog.d("DI", "AppContainer: CoreModule instantiated")
    }
    val termux = TermuxModule(context).also {
        AppLog.d("DI", "AppContainer: TermuxModule instantiated")
    }
    val auth = AuthModule(context, core.httpClient).also {
        AppLog.d("DI", "AppContainer: AuthModule instantiated")
    }
    val installer = InstallerModule(context, core.httpClient).also {
        AppLog.d("DI", "AppContainer: InstallerModule instantiated")
    }
    val setup = SetupModule(
        context, termux.commandRunner, termux.packageChecker,
        installer.apkDownloader, installer.apkInstaller, core.logger
    ).also {
        AppLog.d("DI", "AppContainer: SetupModule instantiated")
    }
    val perf = PerfModule(core.logger).also {
        AppLog.d("DI", "AppContainer: PerfModule instantiated")
    }
    val displayPreferences = DisplayPreferences(context)
    val launchPreferences = LaunchPreferences(context)
    val presentationBackend = PresentationBackends.stable
    val xlorieLoader = ExternalXlorieLoader()
    val captureController: CaptureController = LocalCaptureController(context)
    fun createTermuxProcessPin(): TermuxProcessPin = TermuxProcessPin(context)

    val agentDiagnosticsSession: AgentDiagnosticsSession? = if (BuildConfig.DEBUG) {
        AgentDiagnosticsSession.create(context, DebugLogServer.DEFAULT_PORT)
    } else null
    val debugLogServer: DebugLogServer? = if (BuildConfig.DEBUG) {
        DebugLogServer(
            lineProcessLauncher = core.lineProcessLauncher,
            diagnosticsSession = requireNotNull(agentDiagnosticsSession)
        ).also { AppLog.startDebugServer(it) }
    } else null
    private val nativeTelemetryIngress = NativeTelemetryIngressServer(core.logger) { message ->
        when {
            message.startsWith("RLT_FRAME_RATE_V1 ") -> {
                AppLog.nativeIngress("producer", message)
                ProducerRateTracker.parse(message)?.let { sample ->
                    val outcome = perf.dashboard.updateProducerStats(sample)
                    if (outcome != ProducerRateTracker.UpdateOutcome.ACCEPTED) {
                        core.logger.perf("PRODUCER-RATE-UPDATE: outcome=$outcome")
                    }
                }
            }
            message.startsWith("RLT_VIRGL_WORKER_V1 ") ->
                AppLog.nativeIngress("virgl_worker", message)
            message.startsWith("RLT_MESA_VTEST_V1 ") ->
                AppLog.nativeIngress("mesa_vtest", message)
            message.startsWith("RLT_VIRGL_HOST_V1 ") ->
                AppLog.nativeIngress("virgl_host", message)
            message.startsWith("RLT_VTEST_TRANSPORT_V1 ") ->
                AppLog.nativeIngress("vtest_transport", message)
            message.startsWith("RLT_VTEST_FENCE_V1 ") ->
                AppLog.nativeIngress("vtest_fence", message)
            message.startsWith("RLT_FRAME_STREAM_V1 ") ->
                AppLog.nativeIngress("frame_stream", message)
            message.startsWith("RLT_AHB_STREAM_V1 ") ->
                AppLog.nativeIngress("ahb_stream", message)
            message.startsWith("RLT_AHB_GPU_V1 ") ->
                AppLog.nativeIngress("ahb_gpu", message)
            message.startsWith("RLT_DIRECT_TRANSFER_V1 ") ->
                AppLog.nativeIngress("direct_transfer", message)
            message.startsWith("RLT_TELEMETRY_COST_V1 ") ->
                AppLog.nativeIngress("telemetry_cost", message)
        }
    }

    init {
        debugLogServer?.nativeLogListener = ::handleNativeLogSummary
        nativeTelemetryIngress.start()
        val elapsed = SystemClock.elapsedRealtime() - creationStart
        AppLog.i("DI", "AppContainer: creation completed in ${elapsed}ms, 5 modules wired")
    }

    fun destroy() {
        nativeTelemetryIngress.stop()
        AppLog.stopDebugServer()
    }

    private fun handleNativeLogSummary(message: String) {
        PerfDashboard.parseXloriePerfLine(message)?.let { stats ->
            perf.dashboard.updateConsumerStats(
                stats.consumerRedrawRateHz,
                stats.choreoPct,
                stats.contentPct,
                stats.statePct
            )
        }
        PerfDashboard.parseXlorieStageRatesLine(message)?.let(perf.dashboard::updateX11StageRates)
        PerfDashboard.parseXloriePresentationLine(message)?.let(perf.dashboard::updatePresentationStats)
        PerfDashboard.parseXlorieCopyStatsLine(message)?.let(perf.dashboard::updateCopyStats)
    }
}
