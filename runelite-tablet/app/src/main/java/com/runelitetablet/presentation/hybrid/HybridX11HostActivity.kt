package com.runelitetablet.presentation.hybrid

import android.content.SharedPreferences
import android.graphics.Color
import android.os.Build
import android.os.Bundle
import android.os.SystemClock
import android.os.Debug
import android.os.Process
import android.content.Intent
import android.preference.PreferenceManager
import android.view.Choreographer
import android.view.Gravity
import android.view.SurfaceControl
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.widget.FrameLayout
import android.widget.TextView
import androidx.activity.ComponentActivity
import androidx.activity.enableEdgeToEdge
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.lifecycle.lifecycleScope
import com.runelitetablet.domain.presentation.FrameTimingTracker
import com.runelitetablet.BuildConfig
import com.runelitetablet.RuneLiteTabletApp
import com.runelitetablet.logging.AppLog
import com.runelitetablet.logging.FdTracker
import com.runelitetablet.logging.PerfSnapshots
import com.runelitetablet.domain.command.CommandRunner
import com.runelitetablet.perf.CpuBooster
import com.runelitetablet.perf.PerfDashboard
import com.runelitetablet.telemetry.FrameEventType
import com.runelitetablet.telemetry.FrameOutcome
import com.runelitetablet.telemetry.FrameStage
import com.runelitetablet.ui.LaunchPreferences
import com.termux.x11.LorieView
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch
import java.util.Locale
import java.util.UUID

class HybridX11HostActivity : ComponentActivity() {
    private val captureController by lazy {
        (application as RuneLiteTabletApp).container.captureController
    }
    private lateinit var statusView: TextView
    private lateinit var lorieView: LorieView
    private lateinit var rootView: FrameLayout
    private lateinit var inputController: HybridInputController
    private lateinit var sharedPrefs: SharedPreferences
    private lateinit var attachController: X11AttachmentController
    private var lastHandledGeneration = 0
    private var lastStatusMessage: String? = null
    private var lifecycleActive = false
    private var hasEverAttached = false
    private var binderDiedTimeoutJob: Job? = null
    private var perfDashboard: PerfDashboard? = null
    private var cpuBooster: CpuBooster? = null
    private var commandRunner: CommandRunner? = null
    private var cpuBoostAttempted = false
    private var jankRegistration: SurfaceControl.OnJankDataListenerRegistration? = null
    private var jankRegistrationAttempts = 0
    private val targetHz: Int by lazy { LaunchPreferences(this).targetHz }
    private val frameTracker: FrameTimingTracker by lazy { FrameTimingTracker(targetHz = targetHz) }
    private var hostVsyncCallbackCount = 0L
    private var lastHostVsyncLogCount = 0L
    private var previousFrameTimeNanos = 0L
    private var frameSessionId: String? = null
    private val deadlineNs: Long by lazy { frameTracker.deadlineNs() + 100_000L }
    private val frameCallback = object : Choreographer.FrameCallback {
        override fun doFrame(frameTimeNanos: Long) {
            val priorFrameTime = previousFrameTimeNanos
            val callbackMissed = priorFrameTime > 0 && frameTimeNanos - priorFrameTime > deadlineNs
            captureController.active?.recorder?.record(
                threadId = Process.myTid(),
                frameSeq = 0,
                stage = FrameStage.HOST_VSYNC_CALLBACK,
                eventType = if (priorFrameTime > 0) FrameEventType.SPAN else FrameEventType.INSTANT,
                outcome = if (callbackMissed) FrameOutcome.TIMED_OUT else FrameOutcome.ACCEPTED,
                beginMonotonicNs = if (priorFrameTime > 0) priorFrameTime else frameTimeNanos,
                endMonotonicNs = frameTimeNanos,
                threadCpuTimeNs = Debug.threadCpuTimeNanos()
            )
            frameTracker.recordFrame(frameTimeNanos)
            hostVsyncCallbackCount++
            if (BuildConfig.DEBUG && hostVsyncCallbackCount % 120 == 0L) {
                AppLog.thread(
                    "Choreographer",
                    Thread.currentThread().name,
                    "doFrame",
                    "hostVsyncCallback=$hostVsyncCallbackCount"
                )
            }
            val sessionId = frameSessionId
            // Log frame stats every 120 frames (~1 second at 120fps)
            if (hostVsyncCallbackCount - lastHostVsyncLogCount >= 120) {
                lastHostVsyncLogCount = hostVsyncCallbackCount
                val fps = frameTracker.getAverageFps()
                val missedDeadlines = frameTracker.getMissedDeadlineCount()
                val p99 = frameTracker.getFrameTimePercentile(99.0)
                val heapMb = (Runtime.getRuntime().let { it.totalMemory() - it.freeMemory() } / 1_048_576L).toInt()
                AppLog.hostVsync(fps, missedDeadlines, p99, targetHz, "periodic heap=${heapMb}MB", sessionId)
                val freeMemMb = (Runtime.getRuntime().freeMemory() / (1024 * 1024)).toInt()
                if (heapMb > 128 && missedDeadlines > 2) {
                    AppLog.perf("MEMORY_PRESSURE: heap=${heapMb}MB free=${freeMemMb}MB host_vsync_misses=$missedDeadlines callbackRate=$fps")
                }
            }
            // Per-callback misses stay in the binary recorder. Logcat receives
            // only the one-second aggregate above.
            previousFrameTimeNanos = frameTimeNanos
            try {
                Choreographer.getInstance().postFrameCallback(this)
            } catch (e: Exception) {
                AppLog.e("FRAME", "Choreographer re-registration FAILED — frame loop dead", e)
            }
        }
    }
    private val prefListener = SharedPreferences.OnSharedPreferenceChangeListener { prefs, _ ->
        if (!::lorieView.isInitialized || !lifecycleActive) {
            AppLog.w("HYBRID_X11", "SharedPrefs listener blocked — lifecycle inactive or lorieView uninitialized")
            return@OnSharedPreferenceChangeListener
        }
        AppLog.d("HYBRID_X11", "prefListener: preference changed, reloading")
        lorieView.reloadPreferences(prefs)
    }

    private fun registerJankListenerWhenAttached() {
        if (Build.VERSION.SDK_INT < 36 || !lifecycleActive || jankRegistration != null) return
        val attachedSurface = rootView.rootSurfaceControl
        if (attachedSurface == null) {
            jankRegistrationAttempts++
            if (jankRegistrationAttempts < MAX_JANK_REGISTRATION_ATTEMPTS) {
                rootView.postDelayed(::registerJankListenerWhenAttached, JANK_REGISTRATION_RETRY_MS)
            } else {
                AppLog.w("HOST_VSYNC", "Android 16 OnJankDataListener registration unavailable after attachment retries")
            }
            return
        }

        jankRegistration = attachedSurface.registerOnJankDataListener(mainExecutor) { batch ->
            val recorder = captureController.active?.recorder ?: return@registerOnJankDataListener
            val threadId = Process.myTid()
            val threadCpuNs = Debug.threadCpuTimeNanos()
            batch.forEach { data ->
                recorder.record(
                    threadId = threadId,
                    frameSeq = 0,
                    stage = FrameStage.ANDROID_JANK_CLASSIFICATION,
                    eventType = FrameEventType.SPAN,
                    outcome = if (data.jankType == SurfaceControl.JankData.JANK_NONE) {
                        FrameOutcome.PRESENTED
                    } else {
                        FrameOutcome.TIMED_OUT
                    },
                    beginMonotonicNs = data.scheduledAppFrameTimeNanos,
                    endMonotonicNs = data.actualAppFrameTimeNanos,
                    threadCpuTimeNs = threadCpuNs,
                    platformFrameId = data.vsyncId,
                    flags = data.jankType.toLong()
                )
            }
        }
        AppLog.i("HOST_VSYNC", "Android 16 OnJankDataListener registration=active attempts=$jankRegistrationAttempts")
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        // Full immersive: hide status bar + navigation bar so Termux:X11's canvas gets
        // the entire physical screen. `enableEdgeToEdge` above lets content draw under
        // the bars, but without this call they remain visible (opaque or as overlays
        // depending on OEM theming). On Samsung tablets (e.g. Tab S10 Ultra) system
        // bars otherwise consume ~200px top and ~100px bottom of a 1848-tall screen,
        // leaving RL's canvas in an ugly letterboxed window.
        WindowCompat.getInsetsController(window, window.decorView).apply {
            hide(WindowInsetsCompat.Type.systemBars())
            systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        }
        // Also extend under display cutouts so we get those pixels too on notched displays.
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            @Suppress("DEPRECATION")
            window.attributes = window.attributes.apply {
                layoutInDisplayCutoutMode =
                    WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES
            }
        }
        @Suppress("DEPRECATION")
        window.attributes = window.attributes.apply { preferredRefreshRate = 120f }

        val loadResult = (application as com.runelitetablet.RuneLiteTabletApp).container.xlorieLoader.ensureLoaded(this)
        if (!loadResult.loaded) { showFailure(loadResult.summary()); return }

        initViews()
        ensureCaptureSession()
        initAttachController()
        observeBridgeState()
        val container = (application as com.runelitetablet.RuneLiteTabletApp).container
        perfDashboard = container.perf.dashboard
        cpuBooster = container.perf.cpuBooster
        commandRunner = container.termux.commandRunner
        // Wire native XloriePerf stats into the perf dashboard
        container.debugLogServer?.nativeLogListener = { msg ->
            PerfDashboard.parseXloriePerfLine(msg)?.let { stats ->
                perfDashboard?.updateConsumerStats(
                    stats.consumerRedrawFps,
                    stats.choreoPct,
                    stats.contentPct,
                    stats.statePct
                )
            }
        }
        AppLog.lifecycle("HybridX11HostActivity.onCreate")
        // WINDOW-geometry preflight: capture what Android hands us BEFORE any surface change.
        // Lets us tell whether immersive bars took effect, what the starting window-insets
        // look like, and whether the display is reporting DP or PX-scaled dimensions.
        val dm = resources.displayMetrics
        val flags = window.attributes.flags
        val cutoutMode = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            window.attributes.layoutInDisplayCutoutMode
        } else {
            0
        }
        AppLog.d(
            "WINDOW",
            "HybridX11HostActivity.onCreate: displayMetrics=${dm.widthPixels}x${dm.heightPixels}@${dm.density}x " +
                "(densityDpi=${dm.densityDpi}) flags=0x${flags.toString(16)} cutoutMode=$cutoutMode " +
                "systemBars=hidden(via WindowInsetsControllerCompat)"
        )
        // DISPLAY capability preflight: dump Android's advertised mode list so we can
        // tell whether 120Hz is available, which mode is currently active, and what the
        // panel would switch to under adaptive-refresh. Paired with LorieView's per-
        // surface DISPLAY log — compare the two to detect Activity-vs-Surface display
        // divergence (e.g. secondary/external displays).
        @Suppress("DEPRECATION")
        val disp = windowManager.defaultDisplay
        if (disp != null) {
            val activeMode = disp.mode
            val modes = disp.supportedModes.joinToString(";") {
                "id=${it.modeId}:${it.physicalWidth}x${it.physicalHeight}@${"%.1f".format(it.refreshRate)}Hz"
            }
            val rates = disp.supportedRefreshRates.joinToString(",") { "%.1f".format(it) }
            AppLog.d(
                "DISPLAY",
                "HybridX11HostActivity.onCreate: activeMode=id=${activeMode.modeId}:" +
                    "${activeMode.physicalWidth}x${activeMode.physicalHeight}@${"%.1f".format(activeMode.refreshRate)}Hz " +
                    "refreshRate=${"%.1f".format(disp.refreshRate)}Hz supportedRefreshRates=[$rates] " +
                    "supportedModes=[$modes] displayId=${disp.displayId} state=${disp.state}"
            )
        } else {
            AppLog.w("DISPLAY", "HybridX11HostActivity.onCreate: display unavailable — mode dump skipped")
        }
        window.decorView.setOnApplyWindowInsetsListener { v, insets ->
            // Log insets AFTER layout — systemBars should be 0/0/0/0 once the immersive
            // controller has fired; if they're non-zero something is overriding us.
            val sbInsets = androidx.core.view.WindowInsetsCompat.toWindowInsetsCompat(insets, v)
                .getInsets(androidx.core.view.WindowInsetsCompat.Type.systemBars())
            AppLog.d(
                "WINDOW",
                "onApplyWindowInsets: systemBars=left=${sbInsets.left} top=${sbInsets.top} " +
                    "right=${sbInsets.right} bottom=${sbInsets.bottom} (non-zero = bars still consuming space)"
            )
            insets
        }
        updateStatus("loaded Xlorie from ${loadResult.loadedFrom}")
    }

    private fun ensureCaptureSession() {
        if (captureController.active != null) return
        val preferences = LaunchPreferences(this)
        val session = captureController.start(
            backend = preferences.rendererBackend,
            telemetryMode = preferences.telemetryMode,
            targetHz = preferences.targetHz
        )
        val capture = session.manifest
        AppLog.state(
            "CAPTURE-START runId=${capture.runId} backend=${capture.backend} mode=${capture.telemetryMode} " +
                "targetHz=${capture.targetHz} source=hybrid-host"
        )
    }

    private fun initViews() {
        statusView = TextView(this).apply {
            setTextColor(Color.WHITE); setBackgroundColor(0x88000000.toInt())
            setPadding(24, 24, 24, 24); textSize = 14f; text = "Hybrid host booting"
        }
        lorieView = LorieView(this).apply { setBackgroundColor(Color.BLACK) }
        @Suppress("DEPRECATION")
        run { sharedPrefs = PreferenceManager.getDefaultSharedPreferences(this) }
        sharedPrefs.registerOnSharedPreferenceChangeListener(prefListener)
        lorieView.reloadPreferences(sharedPrefs)
        rootView = FrameLayout(this).apply {
            setBackgroundColor(Color.BLACK)
            addView(lorieView, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
            addView(statusView, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.TOP))
        }
        inputController = HybridInputController(rootView = rootView, lorieView = lorieView)
        inputController.install()
        lorieView.setCallback(object : LorieView.Callback {
            override fun changed(surfaceWidth: Int, surfaceHeight: Int, screenWidth: Int, screenHeight: Int) {
                @Suppress("DEPRECATION")
                val framerate = windowManager.defaultDisplay?.refreshRate?.toInt() ?: 120
                // WINDOW tag covers every window-geometry transition so we can diff expected
                // vs actual at each launch. Previously only went into the TextView overlay,
                // which made post-mortems on "why is the canvas 1480x924 instead of 2960x1848"
                // impossible. Now every callback lands in logcat + DebugLogServer.
                val dm = resources.displayMetrics
                val physW = dm.widthPixels
                val physH = dm.heightPixels
                val density = dm.density
                AppLog.d(
                    "WINDOW",
                    "LorieView.changed: surface=${surfaceWidth}x$surfaceHeight " +
                        "screen=${screenWidth}x$screenHeight refresh=${framerate}Hz " +
                        "displayMetrics=${physW}x${physH}@${density}x " +
                        "(ratios: surface/display=${String.format(Locale.ROOT, "%.2f", surfaceWidth.toFloat() / physW)}x" +
                        " screen/display=${String.format(Locale.ROOT, "%.2f", screenWidth.toFloat() / physW)}x)"
                )
                if (screenWidth > 0 && screenHeight > 0) {
                    AppLog.ipc("send", "jni_sendWindowChange", 0, "width=$screenWidth height=$screenHeight framerate=$framerate")
                    LorieView.sendWindowChange(screenWidth, screenHeight, framerate, "builtin")
                }
                inputController.onWindowMetricsChanged(surfaceWidth, surfaceHeight, screenWidth, screenHeight)
                updateStatus("surface=${surfaceWidth}x$surfaceHeight screen=${screenWidth}x$screenHeight refresh=$framerate")
            }
        })
        setContentView(rootView)
    }

    private fun initAttachController() {
        attachController = X11AttachmentController(
            connector = LorieServiceConnector(lorieView, sharedPrefs),
            onStatusUpdate = ::updateStatus, onStatusVisible = ::setStatusVisible,
            onAttached = {}, logger = AppLog
        )
        com.termux.x11.MainActivity.getInstance().setConnectionChangedListener { connected ->
            attachController.notifyConnectionStateChanged(connected)
            if (!connected && lifecycle.currentState.isAtLeast(androidx.lifecycle.Lifecycle.State.RESUMED))
                attachController.startAttachLoop(lifecycleScope)
        }
    }

    private fun observeBridgeState() {
        lifecycleScope.launch {
            AppLog.thread("launcher", Thread.currentThread().name, "observeBridgeState", "collecting bridge state")
            HybridX11Bridge.state.collectLatest { state ->
                handleBinderDeathEscapeHatch(state)
                if (state.generation <= 0 || state.generation == lastHandledGeneration) return@collectLatest
                lastHandledGeneration = state.generation
                attachController.notifyBridgeStateChanged(); setStatusVisible(true)
                updateStatus("bridge updated generation=${state.generation}, reconnecting")
                if (lifecycle.currentState.isAtLeast(androidx.lifecycle.Lifecycle.State.RESUMED))
                    attachController.startAttachLoop(lifecycleScope)
            }
        }
    }

    /**
     * If the binder dies after we were previously attached (e.g. the RuneLite JVM or
     * Termux:X11 server exited) the attach loop retries forever and the user is stuck
     * staring at "binderAlive=false last=binder_died". Finish the activity after a short
     * grace period so they land back on the RLT main screen and can re-tap Launch.
     * Gated on `hasEverAttached` so the escape hatch never fires during the initial
     * pre-attach wait.
     */
    private fun handleBinderDeathEscapeHatch(state: HybridX11BridgeState) {
        if (state.binderAlive) {
            hasEverAttached = true
            binderDiedTimeoutJob?.cancel()
            binderDiedTimeoutJob = null
            return
        }
        if (!hasEverAttached || state.lastEvent != "binder_died") return
        if (binderDiedTimeoutJob?.isActive == true) return
        binderDiedTimeoutJob = lifecycleScope.launch {
            AppLog.w("HYBRID_X11", "binder_died after prior attach — scheduling escape hatch in 3s")
            delay(BINDER_DIED_ESCAPE_HATCH_MS)
            if (HybridX11Bridge.state.value.binderAlive) {
                AppLog.d("HYBRID_X11", "binder_died escape hatch cancelled: binder recovered")
                return@launch
            }
            AppLog.w("HYBRID_X11", "binder_died escape hatch fired — finishing activity")
            runOnUiThread { if (!isFinishing) finish() }
        }
    }

    private companion object {
        const val BINDER_DIED_ESCAPE_HATCH_MS = 3_000L
        const val MAX_JANK_REGISTRATION_ATTEMPTS = 10
        const val JANK_REGISTRATION_RETRY_MS = 100L
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        if (LorieView.connected() && HybridX11Bridge.state.value.binderAlive && !attachController.pendingBridgeReconnect) {
            setStatusVisible(false); return
        }
        attachController.notifyBridgeStateChanged()
        setStatusVisible(true)
        attachController.startAttachLoop(lifecycleScope)
    }

    override fun onResume() {
        super.onResume()
        lifecycleActive = true
        frameSessionId = "frames-" + UUID.randomUUID().toString().take(4)
        frameTracker.reset()
        hostVsyncCallbackCount = 0L
        lastHostVsyncLogCount = 0L
        previousFrameTimeNanos = 0L
        Choreographer.getInstance().postFrameCallback(frameCallback)
        if (Build.VERSION.SDK_INT >= 36) {
            jankRegistration?.removeAfter(0)
            jankRegistration = null
            jankRegistrationAttempts = 0
            rootView.post(::registerJankListenerWhenAttached)
        }
        attachController.startAttachLoop(lifecycleScope)
        // Phase 2C: Lifecycle parity — reload preferences and notify connection state on resume
        // (upstream MainActivity does this; prevents legacy_drawing regression after backgrounding)
        if (::lorieView.isInitialized && ::sharedPrefs.isInitialized) {
            lorieView.reloadPreferences(sharedPrefs)
            com.termux.x11.MainActivity.getInstance()?.clientConnectedStateChanged()
        }
        perfDashboard?.start()
        // Promote VirGL/proot from moderate cpuset (little cores 0-3) to top-app (big+prime 0-7)
        if (!cpuBoostAttempted) {
            cpuBoostAttempted = true
            lifecycleScope.launch {
                try {
                    val runner = commandRunner ?: return@launch
                    val result = runner.execute(
                        commandPath = "${CommandRunner.TERMUX_BIN_PATH}/bash",
                        arguments = arrayOf("-c", "SESSION_DIR=\$(cat \$PREFIX/tmp/rlt-session/current 2>/dev/null); " +
                            "echo virgl=\$(cat \$SESSION_DIR/virgl.pid 2>/dev/null); " +
                            "echo java=\$(cat \$SESSION_DIR/java.pid 2>/dev/null)"),
                        timeoutMs = 10_000L
                    )
                    val stdout = result.stdout ?: ""
                    val virglPid = Regex("""virgl=(\d+)""").find(stdout)?.groupValues?.get(1)?.toIntOrNull() ?: 0
                    val javaPid = Regex("""java=(\d+)""").find(stdout)?.groupValues?.get(1)?.toIntOrNull() ?: 0
                    if (virglPid > 0 || javaPid > 0) {
                        cpuBooster?.boostFromSessionPids(virglPid, javaPid)
                    } else {
                        AppLog.w("CPU_BOOST", "No PIDs found in session: $stdout")
                        cpuBoostAttempted = false // retry next resume
                    }
                } catch (e: Exception) {
                    AppLog.w("CPU_BOOST", "PID discovery failed: ${e.message}")
                    cpuBoostAttempted = false
                }
            }
        }
        AppLog.lifecycle("HybridX11HostActivity.onResume: frame callback + attach loop + perf dashboard started, sessionId=$frameSessionId")
    }

    override fun onPause() {
        lifecycleActive = false
        if (Build.VERSION.SDK_INT >= 36) {
            jankRegistration?.flush()
            jankRegistration?.removeAfter(0)
            jankRegistration = null
        }
        perfDashboard?.stop()
        Choreographer.getInstance().removeFrameCallback(frameCallback)
        frameSessionId = null
        attachController.cancelAttachLoop()
        FdTracker.dumpLeaks("onPause")
        AppLog.lifecycle("HybridX11HostActivity.onPause: frame callback + attach loop + perf dashboard stopped")
        super.onPause()
    }

    override fun onDestroy() {
        com.termux.x11.MainActivity.getInstance().setConnectionChangedListener(null)
        if (::sharedPrefs.isInitialized) sharedPrefs.unregisterOnSharedPreferenceChangeListener(prefListener)
        super.onDestroy()
    }

    private fun showFailure(message: String) {
        statusView = TextView(this).apply {
            setTextColor(Color.WHITE); setBackgroundColor(Color.BLACK)
            setPadding(24, 24, 24, 24); textSize = 16f
            text = "Hybrid X11 host failed to load\n$message"
        }
        setContentView(statusView)
    }

    private fun setStatusVisible(visible: Boolean) {
        if (!::statusView.isInitialized) return
        val v = if (visible) View.VISIBLE else View.GONE
        if (statusView.visibility != v) runOnUiThread { statusView.visibility = v }
    }

    private fun updateStatus(message: String) {
        val bridgeState = HybridX11Bridge.state.value
        val t = String.format(Locale.US, "%.1fs", SystemClock.uptimeMillis() / 1000f)
        val full = "Hybrid X11 host\nt=$t\n$message\nbinderAlive=${bridgeState.binderAlive} broadcasts=${bridgeState.broadcastCount} last=${bridgeState.lastEvent}"
        if (full == lastStatusMessage) return
        lastStatusMessage = full
        if (::statusView.isInitialized) runOnUiThread { statusView.text = full }
        AppLog.step("hybrid_x11", "HybridX11HostActivity: $message")
    }
}
