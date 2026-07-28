package com.runelitetablet.setup

import android.content.Context
import android.content.Intent
import com.runelitetablet.auth.AuthResult
import com.runelitetablet.domain.logging.CorrelationId
import com.runelitetablet.auth.LaunchEnvDeployer
import com.runelitetablet.telemetry.RendererBackend
import com.runelitetablet.auth.SessionValidation
import com.runelitetablet.auth.JagexOAuth2Manager
import com.runelitetablet.domain.auth.CredentialStore
import com.runelitetablet.domain.command.CommandRunner
import com.runelitetablet.domain.installer.PackageChecker
import com.runelitetablet.domain.logging.Logger
import com.runelitetablet.domain.setup.ScriptDeployer
import com.runelitetablet.directsurface.DirectSurfaceControlServer
import com.runelitetablet.presentation.PresentationBackend
import com.runelitetablet.session.RuneLiteSessionService
import com.runelitetablet.session.SessionState
import com.runelitetablet.telemetry.CaptureController
import com.runelitetablet.telemetry.TelemetryMode
import com.runelitetablet.ui.DisplayPreferences
import com.runelitetablet.ui.LaunchPreferences
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class LaunchCoordinator(
    private val context: Context,
    private val commandRunner: CommandRunner,
    private val packageChecker: PackageChecker,
    private val scriptDeployer: ScriptDeployer,
    private val credentialStore: CredentialStore,
    private val oAuth2Manager: JagexOAuth2Manager,
    private val orchestrator: SetupOrchestrator,
    private val displayPreferences: DisplayPreferences,
    private val launchPreferences: LaunchPreferences,
    private val presentationBackend: PresentationBackend,
    private val captureController: CaptureController,
    private val authCoordinator: AuthCoordinator,
    private val scope: CoroutineScope,
    private val logger: Logger? = null,
    private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO
) {
    private val _launchState = MutableStateFlow<LaunchState>(LaunchState.Idle)
    val launchState: StateFlow<LaunchState> = _launchState.asStateFlow()
    private val _healthStatus = MutableStateFlow<HealthCheckResult?>(null)
    val healthStatus: StateFlow<HealthCheckResult?> = _healthStatus.asStateFlow()
    private val _showHealthDialog = MutableStateFlow<List<String>?>(null)
    val showHealthDialog: StateFlow<List<String>?> = _showHealthDialog.asStateFlow()
    private var activeLaunchJob: Job? = null
    private var x11ReadySwitchJob: Job? = null

    fun launchRuneLite() {
        if (activeLaunchJob?.isActive == true) return
        val s = RuneLiteSessionService.sessionState.value
        if (s is SessionState.Running || s is SessionState.Starting) return
        val corrId = CorrelationId.generate("launch")
        logger?.state("LaunchCoordinator.launchRuneLite: starting pre-launch sequence", corrId)
        activeLaunchJob = scope.launch {
            try {
                _launchState.value = LaunchState.CheckingUpdate
                logger?.state("LaunchCoordinator: checking for updates", correlationId = corrId)
                parseUpdateOutput(runUpdateCheck())
                _launchState.value = LaunchState.CheckingHealth
                logger?.state("LaunchCoordinator: running health check", correlationId = corrId)
                val health = runHealthCheck(correlationId = corrId)
                _healthStatus.value = health
                if (health is HealthCheckResult.Degraded) {
                    logger?.w("LAUNCH", "LaunchCoordinator: health check degraded failures=${health.failures}", correlationId = corrId)
                    _launchState.value = LaunchState.Failed("Setup incomplete: ${health.failures.joinToString()}")
                    _showHealthDialog.value = health.failures; return@launch
                }
                performLaunch(correlationId = corrId)
            } catch (e: CancellationException) { throw e }
            catch (e: Exception) { logger?.e("LAUNCH", "LaunchCoordinator: launch error", throwable = e, correlationId = corrId); _launchState.value = LaunchState.Failed(e.message ?: "Unknown error") }
            finally { activeLaunchJob = null }
        }
    }

    suspend fun performLaunch(correlationId: String? = null) {
        logger?.state("LaunchCoordinator.performLaunch: starting", correlationId = correlationId)
        val hasCredentials = withContext(ioDispatcher) { credentialStore.hasCredentials() }
        logger?.state("LaunchCoordinator: hasCredentials=$hasCredentials", correlationId = correlationId)
        if (hasCredentials) {
            _launchState.value = LaunchState.RefreshingTokens
            val authCorrId = if (correlationId != null) CorrelationId.nested(correlationId, "auth-refresh") else null
            logger?.state("LaunchCoordinator: refreshing auth tokens parentCorrId=$correlationId childCorrId=$authCorrId", correlationId = correlationId)
            when (val authResult = authCoordinator.refreshIfNeeded(correlationId = authCorrId)) {
                is AuthResult.NeedsLogin -> {
                    logger?.w("LAUNCH", "LaunchCoordinator: auth needs login, redirecting", correlationId = correlationId)
                    authCoordinator.pendingLaunchAfterAuth.set(true)
                    _launchState.value = LaunchState.Idle; authCoordinator.startLogin(); return
                }
                is AuthResult.NetworkError -> {
                    logger?.w("LAUNCH", "LaunchCoordinator: auth refresh network error, continuing", correlationId = correlationId)
                }
                else -> {
                    logger?.state("LaunchCoordinator: auth refresh result=$authResult", correlationId = correlationId)
                }
            }
            val sessionId = withContext(ioDispatcher) { credentialStore.getCredentials() }?.sessionId
            if (sessionId != null && sessionId.isNotEmpty()) {
                _launchState.value = LaunchState.ValidatingSession
                logger?.state("LaunchCoordinator: validating session", correlationId = correlationId)
                when (oAuth2Manager.validateSession(sessionId)) {
                    is SessionValidation.Valid -> {
                        logger?.state("LaunchCoordinator: session valid", correlationId = correlationId)
                    }
                    is SessionValidation.Expired -> {
                        logger?.w("LAUNCH", "LaunchCoordinator: session expired, redirecting to login", correlationId = correlationId)
                        authCoordinator.pendingLaunchAfterAuth.set(true)
                        withContext(ioDispatcher) { credentialStore.clearCredentials() }
                        if (orchestrator.actions == null) {
                            authCoordinator.pendingLaunchAfterAuth.set(false)
                            _launchState.value = LaunchState.Failed("Activity not available"); return
                        }
                        _launchState.value = LaunchState.Idle; authCoordinator.startLogin(); return
                    }
                    is SessionValidation.NetworkError -> {
                        logger?.w("LAUNCH", "LaunchCoordinator: session validation network error, continuing", correlationId = correlationId)
                    }
                }
            }
        }
        _launchState.value = LaunchState.Launching
        val requestedUseNative = launchPreferences.useNativeTermux
        val nativeRepositoryReady = if (requestedUseNative) {
            isNativeRepositoryReady()
        } else {
            false
        }
        val launchRoute = selectLaunchRoute(
            requestedUseNative = requestedUseNative,
            nativeRepositoryReady = nativeRepositoryReady,
            requestedBackend = launchPreferences.rendererBackend
        )
        if (requestedUseNative && !launchRoute.useNative) {
            logger?.state(
                "LaunchCoordinator: native repository refresh required; using one official proot launcher run " +
                    "without modifying or automating RuneLite",
                correlationId = correlationId
            )
            if (!ensureOfficialLauncherJava(correlationId)) {
                logger?.e(
                    "LAUNCH",
                    "LaunchCoordinator: Java 21 prerequisite refresh failed; official RuneLite launcher cannot run",
                    correlationId = correlationId
                )
                _launchState.value = LaunchState.Failed("Failed to install Java 21 for the official RuneLite refresh")
                return
            }
        }
        if (launchRoute.useNative) {
            logger?.state("LaunchCoordinator: native-Termux mode enabled, deploying Bionic rlawt jar", correlationId = correlationId)
            if (!scriptDeployer.deployJars()) {
                logger?.e("LAUNCH", "LaunchCoordinator: deployJars failed — cannot run native path without bionic rlawt jar", correlationId = correlationId)
                _launchState.value = LaunchState.Failed("Failed to deploy Bionic rlawt jar for native launch")
                return
            }
        }
        logger?.state(
            "LaunchCoordinator: deploying env file backend=${presentationBackend.id} " +
                "useNative=${launchRoute.useNative} renderer=${launchRoute.rendererBackend.envValue}",
            correlationId = correlationId
        )
        val envFilePath = LaunchEnvDeployer.deployToTermuxHome(credentialStore, commandRunner)
        logger?.state("LaunchCoordinator: envFilePath=$envFilePath hasEnvFile=${envFilePath != null}", correlationId = correlationId)
        logger?.d("LAUNCH", "LaunchCoordinator: env contents=[tokens redacted] backend=${presentationBackend.id}", correlationId = correlationId)
        val success = launchInternal(
            envFilePath = envFilePath,
            correlationId = correlationId,
            useNative = launchRoute.useNative,
            rendererBackend = launchRoute.rendererBackend
        )
        if (success) {
            logger?.state("LaunchCoordinator: launch succeeded, starting session service", correlationId = correlationId)
            _launchState.value = LaunchState.Idle
            startSessionService(correlationId)
        } else {
            logger?.e("LAUNCH", "LaunchCoordinator: launch failed", correlationId = correlationId)
            _launchState.value = LaunchState.Failed("Failed to start RuneLite launch command")
        }
    }

    private suspend fun launchInternal(
        envFilePath: String? = null,
        correlationId: String? = null,
        useNative: Boolean = false,
        rendererBackend: RendererBackend = launchPreferences.rendererBackend
    ): Boolean {
        if (!presentationBackend.isInstalled(packageChecker)) {
            logger?.w("LAUNCH", "launchInternal: backend ${presentationBackend.id} not installed", correlationId = correlationId)
            return false
        }
        presentationBackend.applyLaunchPreferences(context, displayPreferences)
        val telemetryMode = launchPreferences.telemetryMode
        val targetHz = launchPreferences.targetHz
        val captureSession = captureController.start(
            backend = rendererBackend,
            telemetryMode = telemetryMode,
            targetHz = targetHz
        )
        val capture = captureSession.manifest
        val useDirectSurface = rendererBackend == com.runelitetablet.telemetry.RendererBackend.DIRECT_VIRGL_AHB ||
            rendererBackend == com.runelitetablet.telemetry.RendererBackend.DIRECT_ZINK_AHB
        logger?.state(
            "CAPTURE-START runId=${capture.runId} backend=${capture.backend} mode=${capture.telemetryMode} " +
                "targetHz=${capture.targetHz} monotonicNs=${capture.clocks.monotonicNs} " +
                "boottimeNs=${capture.clocks.boottimeNs} bootId=${capture.clocks.bootId}",
            correlationId = correlationId
        )
        val presentationIntent = presentationBackend.createLaunchIntent(context)
        if (useDirectSurface) {
            DirectSurfaceControlServer.ensureStarted(context.applicationContext)
            logger?.state(
                "launchInternal: useDirectSurface=true — direct control endpoint ${DirectSurfaceControlServer.CONTROL_ENDPOINT} ready; " +
                    "foregrounding ${presentationBackend.id} for login and deferring DirectSurfaceHostActivity until rlawt createGLContext",
                correlationId = correlationId
            )
        }
        if (presentationIntent != null && (presentationBackend.shouldForegroundBeforeBootstrap() || useDirectSurface)) {
            logger?.d("LAUNCH", "launchInternal: foregrounding ${presentationBackend.id} before bootstrap", correlationId = correlationId)
            orchestrator.actions?.launchIntent(presentationIntent); delay(700)
        }
        val scriptName = if (useNative) "launch-runelite-native.sh" else "launch-runelite.sh"
        val scriptPath = scriptDeployer.getScriptPath(scriptName)
        val success = if (useNative) {
            // Native path needs RLT_NATIVE_TERMUX=1 in the launcher's env (the script exits
            // with code 2 otherwise). We also hand the script the live screen-derived UI
            // scale because `wm size` / `xdpyinfo` aren't callable from run-as com.termux
            // (WindowManagerService refuses Binder calls and mesa-demos isn't in the base
            // Termux install). Hardcoding a scale in the shell breaks non-tablet devices —
            // see memory/feedback_no_hardcoded_ui_sizes.md.
            val bashPath = "${CommandRunner.TERMUX_BIN_PATH}/bash"
            val envFileArg = envFilePath?.let { " \"$it\"" } ?: ""
            val uiScale = computeUiScale()
            val (gameW, gameH) = computeGameSize(uiScale)
            logger?.state("launchInternal: computed UI scale=$uiScale gameSize=${gameW}x${gameH} from displayMetrics ${context.resources.displayMetrics.widthPixels}x${context.resources.displayMetrics.heightPixels}", correlationId = correlationId)
            // S81 probes-OFF A/B: drop RLAWT_PERF_FINISH / _XSYNC / _GPU_TIMER. Keep
            // RLAWT_PERF=1 so rlawt emits swap/gap statistics plus a structured,
            // sequence-derived produced_fps window. A 30-frame window keeps SUMMARY
            // useful even when the producer is running near 10 FPS.
            // Keep
            // RLT_PERF_SAMPLE=1 so the /proc per-thread sampler runs.
            // RLAWT_PERF_CSV: S81 audit — opens $HOME/rlawt-perframe.csv for raw
            // per-frame row dump. Two clock_gettimes + one fprintf per swap, line-
            // buffered; negligible overhead. Enables end-to-end frame alignment
            // between rlawt, XloriePerf, and SurfaceFlinger --latency.
            val rlawtPerf = if (telemetryMode == TelemetryMode.OFF) 0 else 1
            val perfSampler = if (telemetryMode == TelemetryMode.OFF) 0 else 1
            val rawFrameCsv = if (telemetryMode == TelemetryMode.BURST || telemetryMode == TelemetryMode.DEEP) {
                "RLAWT_PERF_CSV=\$HOME/.rlt/captures/${capture.runId}/rlawt-perframe.csv "
            } else {
                ""
            }
            val cmdLine = "RLT_NATIVE_TERMUX=1 RLT_UI_SCALE=$uiScale RLT_GAME_SIZE_W=$gameW RLT_GAME_SIZE_H=$gameH " +
                "RLT_CAPTURE_ID=${capture.runId} RLT_TELEMETRY_MODE=${capture.telemetryMode} " +
                "RLT_RENDERER_BACKEND=${capture.backend} RLT_TARGET_HZ=${capture.targetHz} " +
                "RLT_CAPTURE_DIR=\$HOME/.rlt/captures/${capture.runId} " +
                "RLT_PERF_SAMPLE=$perfSampler RLAWT_PERF=$rlawtPerf RLAWT_PERF_WINDOW=30 $rawFrameCsv" +
                "exec \"$scriptPath\"$envFileArg"
            logger?.state(
                "launchInternal: dispatching native-path command scriptPath=$scriptPath hasEnvFile=${envFilePath != null} " +
                    "uiScale=$uiScale gameSize=${gameW}x${gameH} presentation=${presentationBackend.id} " +
                    "renderer=${capture.backend} telemetry=${capture.telemetryMode} targetHz=${capture.targetHz} runId=${capture.runId}",
                correlationId = correlationId
            )
            // S82 telemetry surface — log every artifact the new launcher will produce, so a single
            // logcat grep ("TELEMETRY-ARTIFACT") gives the on-device paths without re-reading the script.
            // Paths are relative to Termux $HOME = /data/data/com.termux/files/home unless overridden.
            val termuxHome = "/data/data/com.termux/files/home"
            val captureDir = "$termuxHome/.rlt/captures/${capture.runId}"
            logger?.state("TELEMETRY-ARTIFACT: capture-dir=$captureDir", correlationId = correlationId)
            logger?.state("TELEMETRY-ARTIFACT: launcher-log=$termuxHome/runelite-native.log", correlationId = correlationId)
            if (rawFrameCsv.isNotEmpty()) {
                logger?.state("TELEMETRY-ARTIFACT: rlawt-perframe-csv=$captureDir/rlawt-perframe.csv", correlationId = correlationId)
            }
            logger?.state("TELEMETRY-ARTIFACT: affinity-timeline-csv=$termuxHome/affinity-timeline.csv", correlationId = correlationId)
            logger?.state("TELEMETRY-ARTIFACT: thread-cpu-csv=$termuxHome/thread-cpu.csv", correlationId = correlationId)
            logger?.state("TELEMETRY-ARTIFACT: x11-window-state-log=$termuxHome/x11-window-state.log", correlationId = correlationId)
            logger?.state("TELEMETRY-ARTIFACT: rlawt-summary-observation=tail-of-runelite-native.log (RLAWT-SUMMARY:)", correlationId = correlationId)
            commandRunner.launchBackground(commandPath = bashPath, arguments = arrayOf("-c", cmdLine))
        } else {
            val bashPath = "${CommandRunner.TERMUX_BIN_PATH}/bash"
            val envFileArg = envFilePath?.let { " \"$it\"" } ?: ""
            val cmdLine = "RLT_CAPTURE_ID=${capture.runId} " +
                "RLT_TELEMETRY_MODE=${capture.telemetryMode} " +
                "RLT_RENDERER_BACKEND=${capture.backend} " +
                "RLT_TARGET_HZ=${capture.targetHz} " +
                "RLT_CAPTURE_DIR=\$HOME/.rlt/captures/${capture.runId} " +
                "exec \"$scriptPath\"$envFileArg"
            logger?.state(
                "launchInternal: dispatching proot-path command scriptPath=$scriptPath " +
                    "hasEnvFile=${envFilePath != null} presentation=${presentationBackend.id} " +
                    "renderer=${capture.backend} telemetry=${capture.telemetryMode} " +
                    "targetHz=${capture.targetHz} runId=${capture.runId}",
                correlationId = correlationId
            )
            commandRunner.launchBackground(commandPath = bashPath, arguments = arrayOf("-c", cmdLine))
        }
        logger?.state("launchInternal: command dispatched success=$success useNative=$useNative", correlationId = correlationId)
        if (success && presentationIntent != null && presentationBackend.shouldWaitForReadySignal() && !useDirectSurface) {
            waitForDisplayReadyAndSwitch(presentationIntent)
        }
        return success
    }

    private fun waitForDisplayReadyAndSwitch(x11LaunchIntent: Intent) {
        x11ReadySwitchJob?.cancel()
        x11ReadySwitchJob = scope.launch {
            repeat(20) {
                val result = commandRunner.execute(
                    commandPath = "${CommandRunner.TERMUX_BIN_PATH}/bash",
                    arguments = arrayOf("-c", """
                        CURRENT_FILE="${'$'}PREFIX/tmp/rlt-session/current"
                        if [ -f "${'$'}CURRENT_FILE" ]; then
                            SESSION_DIR="$(cat "${'$'}CURRENT_FILE" 2>/dev/null)"
                            if [ -n "${'$'}SESSION_DIR" ] && [ -f "${'$'}SESSION_DIR/display.ready" ]; then echo READY; fi
                        fi
                    """.trimIndent()),
                    background = true, timeoutMs = CommandRunner.TIMEOUT_VERIFY_MS
                )
                if (result.stdout?.contains("READY") == true) {
                    orchestrator.actions?.launchIntent(x11LaunchIntent); return@launch
                }
                delay(500)
            }
        }
    }

    private suspend fun runUpdateCheck(): String = try {
        if (!scriptDeployer.deployScripts()) ""
        else commandRunner.execute(commandPath = scriptDeployer.getScriptPath("update-runelite.sh"), background = true, timeoutMs = 60_000L).stdout ?: ""
    } catch (e: TimeoutCancellationException) { "" } catch (e: CancellationException) { throw e } catch (_: Exception) { "" }

    private suspend fun isNativeRepositoryReady(): Boolean = try {
        val command = """
            REPO="${CommandRunner.TERMUX_BIN_PATH.removeSuffix("/bin")}/var/lib/proot-distro/installed-rootfs/ubuntu/root/.runelite/repository2"
            REFRESH="${CommandRunner.TERMUX_HOME_PATH}/.runelite-tablet/markers/runelite-refresh-required"
            if [ ! -f "${'$'}REFRESH" ] &&
                [ -d "${'$'}REPO" ] &&
                find "${'$'}REPO" -maxdepth 1 -type f -name '*.jar' -print -quit | grep -q .
            then
                echo READY
            else
                echo REFRESH_REQUIRED
            fi
        """.trimIndent()
        val result = commandRunner.execute(
            commandPath = "${CommandRunner.TERMUX_BIN_PATH}/bash",
            arguments = arrayOf("-c", command),
            background = true,
            timeoutMs = CommandRunner.TIMEOUT_VERIFY_MS
        )
        result.isSuccess && result.stdout?.lineSequence()?.any { it == "READY" } == true
    } catch (e: CancellationException) {
        throw e
    } catch (_: Exception) {
        false
    }

    private suspend fun ensureOfficialLauncherJava(correlationId: String?): Boolean = try {
        if (!scriptDeployer.deployScripts()) {
            false
        } else {
            logger?.state(
                "LaunchCoordinator: verifying Java 21 prerequisite for official launcher recovery",
                correlationId = correlationId
            )
            val result = commandRunner.execute(
                commandPath = scriptDeployer.getScriptPath("install-java.sh"),
                background = true,
                timeoutMs = CommandRunner.DEFAULT_TIMEOUT_MS
            )
            val completed = result.stdout?.contains("=== install-java.sh complete ===") == true
            if (!result.isSuccess || !completed) {
                logger?.e(
                    "LAUNCH",
                    "LaunchCoordinator: Java 21 prerequisite failed exitCode=${result.exitCode} " +
                        "stderr=${result.stderr?.take(300)}",
                    correlationId = correlationId
                )
            }
            result.isSuccess && completed
        }
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        logger?.e(
            "LAUNCH",
            "LaunchCoordinator: Java 21 prerequisite threw ${e.javaClass.simpleName}",
            throwable = e,
            correlationId = correlationId
        )
        false
    }

    private fun parseUpdateOutput(output: String) {
        val statusLine = output.lines().lastOrNull { it.startsWith("UPDATE_STATUS") } ?: ""
        if (statusLine.contains("downloading")) {
            val parts = statusLine.removePrefix("UPDATE_STATUS downloading ").split(" -> ")
            _launchState.value = LaunchState.Updating(parts.getOrElse(0) { "?" }, parts.getOrElse(1) { "?" })
        }
    }

    private suspend fun runHealthCheck(correlationId: String? = null): HealthCheckResult = try {
        if (!scriptDeployer.deployScripts()) HealthCheckResult.Inconclusive
        else {
            val output = commandRunner.execute(commandPath = scriptDeployer.getScriptPath("health-check.sh"), background = true, timeoutMs = 10_000L).stdout ?: ""
            val failures = output.lines().filter { it.startsWith("HEALTH") && it.contains("FAIL") }
                .map { line -> val p = line.split(" "); "${p.getOrElse(1) { "unknown" }}: ${p.getOrElse(3) { "unknown" }}" }
            if (failures.isEmpty()) HealthCheckResult.Healthy else HealthCheckResult.Degraded(failures)
        }
    } catch (e: TimeoutCancellationException) { HealthCheckResult.Inconclusive } catch (e: CancellationException) { throw e } catch (_: Exception) { HealthCheckResult.Inconclusive }

    private fun startSessionService(correlationId: String? = null) {
        logger?.state("startSessionService: starting foreground service", correlationId = correlationId)
        if (!orchestrator.hasNotificationPermission()) orchestrator.actions?.requestNotificationPermission()
        context.startForegroundService(Intent(context, RuneLiteSessionService::class.java).apply {
            action = RuneLiteSessionService.ACTION_START_SESSION
        })
        logger?.state("startSessionService: session service start confirmed", correlationId = correlationId)
    }

    fun stopSession() {
        context.startService(Intent(context, RuneLiteSessionService::class.java).apply { action = RuneLiteSessionService.ACTION_STOP_SESSION })
    }

    fun switchToGame() {
        context.startService(Intent(context, RuneLiteSessionService::class.java).apply { action = RuneLiteSessionService.ACTION_SWITCH_TO_GAME })
    }

    fun checkSession() {
        val state = RuneLiteSessionService.sessionState.value
        if (state is SessionState.Running || state is SessionState.Starting) {
            context.startService(Intent(context, RuneLiteSessionService::class.java).apply { action = RuneLiteSessionService.ACTION_CHECK_SESSION })
        }
    }

    fun dismissHealthDialog() { _showHealthDialog.value = null }
    fun launchAnyway() { _showHealthDialog.value = null; scope.launch { performLaunch() } }

    /**
     * Compute an AWT ui-scale factor from the device's real display metrics. We pick
     * integer-or-half steps (not a smooth multiplier) because RuneLite's AWT-drawn UI
     * text looks worst on fractional scales like 1.7. Buckets match the proot path's
     * --scale 2 default for phones/tablets ~2400px and ups for 4K+ panels.
     */
    private fun computeUiScale(): String {
        val m = context.resources.displayMetrics
        val bigger = maxOf(m.widthPixels, m.heightPixels)
        return when {
            bigger >= 3200 -> "2.5"
            bigger >= 2400 -> "2"
            bigger >= 1800 -> "1.5"
            else -> "1"
        }
    }

    /**
     * Compute RuneLite's `runelite.gameSize` in Java2D units from the live display
     * metrics and the already-chosen uiScale. gameSize sizes the game CANVAS only —
     * RuneLite's Frame adds the sidebar icon strip (SIDEBAR_WIDTH = 41 units) plus
     * the plugin panel when open (PANEL_WIDTH = 232 units), so a naive
     * displayMetrics / uiScale value would push the panel offscreen. We budget for
     * the sidebar open so users can reach plugins without resizing the Frame.
     * Landscape is assumed since OSRS forces landscape in-app.
     */
    private fun computeGameSize(uiScale: String): Pair<Int, Int> {
        val m = context.resources.displayMetrics
        val scale = uiScale.toDoubleOrNull() ?: 1.0
        val widthPx = maxOf(m.widthPixels, m.heightPixels)
        val heightPx = minOf(m.widthPixels, m.heightPixels)
        val frameWidth = (widthPx / scale).toInt()
        val frameHeight = (heightPx / scale).toInt()
        val sidebarReserve = 273
        return (frameWidth - sidebarReserve) to frameHeight
    }
}

internal data class LaunchRoute(
    val useNative: Boolean,
    val rendererBackend: RendererBackend
)

internal fun selectLaunchRoute(
    requestedUseNative: Boolean,
    nativeRepositoryReady: Boolean,
    requestedBackend: RendererBackend
): LaunchRoute {
    if (requestedUseNative && nativeRepositoryReady) {
        return LaunchRoute(useNative = true, rendererBackend = requestedBackend)
    }

    val fallbackBackend = if (requestedBackend == RendererBackend.SOFTWARE_X11) {
        RendererBackend.SOFTWARE_X11
    } else {
        RendererBackend.PROOT_X11_VIRGL
    }
    return LaunchRoute(useNative = false, rendererBackend = fallbackBackend)
}
