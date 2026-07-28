package com.runelitetablet.di

import android.content.Context
import android.os.SystemClock
import com.runelitetablet.BuildConfig
import com.runelitetablet.logging.AppLog
import com.runelitetablet.logging.DebugLogServer
import com.runelitetablet.presentation.PresentationBackends
import com.runelitetablet.presentation.hybrid.ExternalXlorieLoader
import com.runelitetablet.termux.TermuxProcessPin
import com.runelitetablet.telemetry.CaptureController
import com.runelitetablet.telemetry.LocalCaptureController
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

    val debugLogServer: DebugLogServer? = if (BuildConfig.DEBUG) {
        DebugLogServer(core.lineProcessLauncher).also { AppLog.startDebugServer(it) }
    } else null

    init {
        val elapsed = SystemClock.elapsedRealtime() - creationStart
        AppLog.i("DI", "AppContainer: creation completed in ${elapsed}ms, 5 modules wired")
    }

    fun destroy() {
        AppLog.stopDebugServer()
    }
}
