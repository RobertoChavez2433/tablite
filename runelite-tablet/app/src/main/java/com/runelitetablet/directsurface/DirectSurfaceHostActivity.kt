package com.runelitetablet.directsurface

import android.content.SharedPreferences
import android.graphics.Color
import android.os.Build
import android.os.Bundle
import android.preference.PreferenceManager
import android.view.Surface
import android.view.SurfaceHolder
import android.view.SurfaceView
import android.view.ViewGroup
import android.view.WindowManager
import android.widget.FrameLayout
import androidx.activity.ComponentActivity
import androidx.activity.enableEdgeToEdge
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.lifecycle.lifecycleScope
import com.runelitetablet.RuneLiteTabletApp
import com.runelitetablet.logging.AppLog
import com.runelitetablet.platform.process.AppVirglHost
import com.runelitetablet.presentation.hybrid.HybridInputController
import com.runelitetablet.presentation.hybrid.HybridX11Bridge
import com.runelitetablet.presentation.hybrid.LorieServiceConnector
import com.runelitetablet.presentation.hybrid.X11AttachmentController
import com.termux.x11.LorieView

/**
 * Hosts the rlawt direct-Android-surface path over the live X11 presentation.
 *
 * On surfaceCreated, spawns the librlawtsurface worker thread. It can host
 * either the legacy abstract-socket/AHB protocol or the experimental
 * loopback completed-frame stream. The latter keeps AHB allocation and
 * presentation inside the app UID and records each receive/copy/fence/blit
 * boundary.
 *
 * The X11 view remains underneath the direct surface so RuneLite's Swing
 * sidebar and the app's trackpad-style input bridge remain functional. The
 * direct surface is resized to the actual GL frame dimensions after the first
 * authenticated frame. When RuneLite opens a plugin panel and shrinks the
 * canvas, the overlay follows the new frame size and exposes that X11 chrome.
 */
class DirectSurfaceHostActivity : ComponentActivity() {

    companion object {
        const val EXTRA_ABSTRACT_NAME = "rlt.abstract_name"
        const val EXTRA_TCP_PORT = "rlt.tcp_port"
        const val EXTRA_APP_VIRGL_HOST = "rlt.app_virgl_host"
        const val EXTRA_APP_VIRGL_AHB = "rlt.app_virgl_ahb"
        const val EXTRA_AHB_PROBE_WIDTH = "rlt.ahb_probe_width"
        const val EXTRA_AHB_PROBE_HEIGHT = "rlt.ahb_probe_height"
        const val EXTRA_AHB_PROBE_FRAMES = "rlt.ahb_probe_frames"
        const val EXTRA_VTEST_TCP_PORT = "rlt.vtest_tcp_port"
        const val EXTRA_CAPTURE_ID = "rlt.capture_id"
        const val EXTRA_TELEMETRY_MODE = "rlt.telemetry_mode"
        const val APP_VIRGL_VTEST_TCP_PORT = 49384
        private const val TAG = "DirectSurface"
        private const val OVERLAY_LAYOUT_POLL_MS = 100L

        fun frameAhbSocketName(captureId: String): String {
            val suffix = captureId
                .replace(Regex("[^A-Za-z0-9._-]"), "_")
                .take(40)
            return "rlt-virgl-ahb-$suffix"
        }
    }

    private lateinit var rootView: FrameLayout
    private lateinit var lorieView: LorieView
    private lateinit var surfaceView: SurfaceView
    private lateinit var inputController: HybridInputController
    private lateinit var sharedPrefs: SharedPreferences
    private lateinit var attachController: X11AttachmentController
    private var server: RlawtSurfaceServer? = null
    private var appVirglHost: AppVirglHost? = null
    private var abstractName: String? = null
    private var tcpPort: Int = 0
    private var useAppVirglHost: Boolean = false
    private var useAppVirglAhb: Boolean = false
    private var ahbProbeWidth: Int = 64
    private var ahbProbeHeight: Int = 64
    private var ahbProbeFrames: Int = 0
    private var vtestTcpPort: Int = 0
    private var captureId: String = "unknown"
    private var telemetryMode: String = "SUMMARY"
    private var lifecycleActive = false
    private var lastOverlayLayout: DirectOverlayLayout? = null
    private val overlayLayoutPoll = object : Runnable {
        override fun run() {
            updateDirectOverlayLayout()
            if (!isFinishing && !isDestroyed && server != null) {
                surfaceView.postDelayed(this, OVERLAY_LAYOUT_POLL_MS)
            }
        }
    }
    private val prefListener = SharedPreferences.OnSharedPreferenceChangeListener { prefs, _ ->
        if (::lorieView.isInitialized && lifecycleActive) {
            lorieView.reloadPreferences(prefs)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        WindowCompat.getInsetsController(window, window.decorView).apply {
            hide(WindowInsetsCompat.Type.systemBars())
            systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        }
        @Suppress("DEPRECATION")
        window.attributes = window.attributes.apply { preferredRefreshRate = 120f }

        abstractName = intent.getStringExtra(EXTRA_ABSTRACT_NAME)
        tcpPort = intent.getIntExtra(EXTRA_TCP_PORT, 0)
            .takeIf { it in 1..65535 }
            ?: 0
        useAppVirglHost = intent.getBooleanExtra(EXTRA_APP_VIRGL_HOST, false)
        useAppVirglAhb = intent.getBooleanExtra(EXTRA_APP_VIRGL_AHB, false)
        ahbProbeWidth = intent.getIntExtra(EXTRA_AHB_PROBE_WIDTH, 64).coerceIn(16, 4096)
        ahbProbeHeight = intent.getIntExtra(EXTRA_AHB_PROBE_HEIGHT, 64).coerceIn(16, 4096)
        ahbProbeFrames = intent.getIntExtra(EXTRA_AHB_PROBE_FRAMES, 0).coerceIn(0, 1200)
        vtestTcpPort = intent.getIntExtra(EXTRA_VTEST_TCP_PORT, 0)
            .takeIf { it in 1..65535 }
            ?: 0
        captureId = intent.getStringExtra(EXTRA_CAPTURE_ID).orEmpty().ifBlank { "unknown" }
        telemetryMode = intent.getStringExtra(EXTRA_TELEMETRY_MODE).orEmpty().ifBlank { "SUMMARY" }
        AppLog.lifecycle(
            "DirectSurfaceHost.onCreate intent=$intent abstractName=$abstractName tcpPort=$tcpPort " +
                "useAppVirglHost=$useAppVirglHost useAppVirglAhb=$useAppVirglAhb " +
                "vtestTcpPort=$vtestTcpPort " +
                "captureId=$captureId telemetryMode=$telemetryMode"
        )

        val loadResult = (application as RuneLiteTabletApp).container.xlorieLoader.ensureLoaded(this)
        if (!loadResult.loaded) {
            AppLog.e(TAG, "DirectSurfaceHost failed to load Xlorie: ${loadResult.summary()}")
            finish()
            return
        }
        initCompositeViews()
        initAttachController()

        surfaceView = SurfaceView(this).apply {
            setBackgroundColor(Color.BLACK)
            // Both the direct canvas and Xlorie are SurfaceViews. A media
            // overlay still leaves both surfaces below the window on Samsung's
            // compositor, where the stale, bypassed X11 canvas can obscure the
            // populated direct buffer. Put only the measured GL-canvas bounds
            // on top; the narrower overlay leaves RuneLite's right-side Swing
            // chrome visible through the X11 surface.
            setZOrderOnTop(true)
            holder.addCallback(object : SurfaceHolder.Callback {
                override fun surfaceCreated(holder: SurfaceHolder) {
                    val s = holder.surface
                    AppLog.lifecycle("DirectSurfaceHost.surfaceCreated holder=$holder surface=$s isValid=${s.isValid}")
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                        runCatching {
                            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                                s.setFrameRate(
                                    120f,
                                    Surface.FRAME_RATE_COMPATIBILITY_DEFAULT,
                                    Surface.CHANGE_FRAME_RATE_ALWAYS
                                )
                            } else {
                                s.setFrameRate(120f, Surface.FRAME_RATE_COMPATIBILITY_DEFAULT)
                            }
                        }.onSuccess {
                            val activeMode = display?.mode
                            AppLog.i(
                                TAG,
                                "DirectSurfaceHost.surfaceCreated setFrameRate(120Hz, compatibility=DEFAULT) OK " +
                                    "activeMode=${activeMode?.modeId}:${activeMode?.refreshRate}Hz"
                            )
                        }.onFailure {
                            AppLog.w(TAG, "DirectSurfaceHost.surfaceCreated setFrameRate(120Hz) failed: ${it.message}")
                        }
                    }
                    val srv = RlawtSurfaceServer.start(
                        s,
                        if (useAppVirglAhb) frameAhbSocketName(captureId) else abstractName,
                        if (useAppVirglAhb) 0 else tcpPort,
                        cpuUpload = useAppVirglAhb,
                    )
                    if (srv == null) {
                        AppLog.e(TAG, "RlawtSurfaceServer.start returned null in surfaceCreated")
                    } else {
                        AppLog.i(TAG, "RlawtSurfaceServer running")
                    }
                    server = srv
                    surfaceView.removeCallbacks(overlayLayoutPoll)
                    surfaceView.post(overlayLayoutPoll)

                    // The AHB listener must exist before the gated startup
                    // transport sentinel runs in the packaged renderer.
                    if (useAppVirglHost) {
                        if ((!useAppVirglAhb && tcpPort == 0) || vtestTcpPort == 0) {
                            AppLog.e(
                                TAG,
                                "App-UID VirGL requires valid frame and vtest TCP ports; " +
                                "frameTcp=$tcpPort ahb=$useAppVirglAhb vtest=$vtestTcpPort"
                            )
                        } else {
                            appVirglHost = AppVirglHost.start(
                                applicationContext,
                                vtestTcpPort,
                                tcpPort,
                                if (useAppVirglAhb) frameAhbSocketName(captureId) else null,
                                ahbProbeWidth,
                                ahbProbeHeight,
                                ahbProbeFrames,
                                captureId,
                                telemetryMode,
                            )
                            if (appVirglHost == null) {
                                AppLog.e(TAG, "AppVirglHost.start returned null")
                            }
                        }
                    }
                    if (server == null) {
                        appVirglHost?.stop()
                        appVirglHost = null
                    }
                }

                override fun surfaceChanged(holder: SurfaceHolder, format: Int, width: Int, height: Int) {
                    AppLog.lifecycle("DirectSurfaceHost.surfaceChanged ${width}x$height format=$format holder=$holder")
                }

                override fun surfaceDestroyed(holder: SurfaceHolder) {
                    AppLog.lifecycle("DirectSurfaceHost.surfaceDestroyed holder=$holder — stopping server")
                    surfaceView.removeCallbacks(overlayLayoutPoll)
                    appVirglHost?.stop()
                    appVirglHost = null
                    server?.stop()
                    server = null
                }
            })
        }
        inputController.bindPointerTarget(surfaceView, telemetryLabel = "direct_surface")
        rootView.addView(
            surfaceView,
            FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT,
            ),
        )
        setContentView(rootView)
    }

    @Suppress("DEPRECATION")
    private fun initCompositeViews() {
        lorieView = LorieView(this).apply { setBackgroundColor(Color.BLACK) }
        sharedPrefs = PreferenceManager.getDefaultSharedPreferences(this)
        sharedPrefs.registerOnSharedPreferenceChangeListener(prefListener)
        lorieView.reloadPreferences(sharedPrefs)
        rootView = FrameLayout(this).apply {
            setBackgroundColor(Color.BLACK)
            addView(
                lorieView,
                FrameLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.MATCH_PARENT,
                ),
            )
        }
        inputController = HybridInputController(rootView, lorieView)
        inputController.install()
        lorieView.setCallback(object : LorieView.Callback {
            override fun changed(
                surfaceWidth: Int,
                surfaceHeight: Int,
                screenWidth: Int,
                screenHeight: Int,
            ) {
                val refreshRate = windowManager.defaultDisplay?.refreshRate?.toInt() ?: 120
                if (screenWidth > 0 && screenHeight > 0) {
                    LorieView.sendWindowChange(screenWidth, screenHeight, refreshRate, "builtin")
                }
                inputController.onWindowMetricsChanged(
                    surfaceWidth,
                    surfaceHeight,
                    screenWidth,
                    screenHeight,
                )
                updateDirectOverlayLayout()
            }
        })
    }

    private fun initAttachController() {
        attachController = X11AttachmentController(
            connector = LorieServiceConnector(lorieView, sharedPrefs),
            onStatusUpdate = { AppLog.step("hybrid_x11", "DirectSurfaceHost: $it") },
            onStatusVisible = {},
            onAttached = { updateDirectOverlayLayout() },
            logger = AppLog,
        )
        com.termux.x11.MainActivity.getInstance().setConnectionChangedListener { connected ->
            attachController.notifyConnectionStateChanged(connected)
            if (!connected && lifecycleActive) {
                attachController.startAttachLoop(lifecycleScope)
            }
        }
    }

    private fun updateDirectOverlayLayout() {
        if (!::rootView.isInitialized || !::surfaceView.isInitialized) {
            return
        }
        val dimensions = server?.frameDimensions() ?: return
        val layout = computeDirectOverlayLayout(
            hostWidth = rootView.width,
            hostHeight = rootView.height,
            frameWidth = dimensions.first,
            frameHeight = dimensions.second,
        ) ?: return
        if (layout == lastOverlayLayout) {
            return
        }
        lastOverlayLayout = layout
        surfaceView.layoutParams = (surfaceView.layoutParams as FrameLayout.LayoutParams).apply {
            width = layout.width
            height = layout.height
            leftMargin = layout.left
            topMargin = layout.top
        }
        AppLog.i(
            TAG,
            "RLT_DIRECT_LAYOUT_V1 run_id=$captureId stage=surface_overlay outcome=applied " +
                "frame_width=${dimensions.first} frame_height=${dimensions.second} " +
                "host_width=${rootView.width} host_height=${rootView.height} " +
                "overlay_left=${layout.left} overlay_top=${layout.top} " +
                "overlay_width=${layout.width} overlay_height=${layout.height} " +
                "right_chrome_px=${rootView.width - layout.width}",
        )
    }

    override fun onNewIntent(intent: android.content.Intent) {
        super.onNewIntent(intent)
        val nextName = intent.getStringExtra(EXTRA_ABSTRACT_NAME)
        val nextTcpPort = intent.getIntExtra(EXTRA_TCP_PORT, tcpPort)
            .takeIf { it in 1..65535 }
            ?: 0
        AppLog.lifecycle(
            "DirectSurfaceHost.onNewIntent intent=$intent abstractName=$nextName " +
                "tcpPort=$nextTcpPort serverRunning=${server != null}"
        )
        if (!nextName.isNullOrBlank()) {
            abstractName = nextName
        }
        tcpPort = nextTcpPort
    }

    override fun onStart() {
        super.onStart()
        AppLog.lifecycle("DirectSurfaceHost.onStart")
    }

    override fun onResume() {
        super.onResume()
        lifecycleActive = true
        attachController.startAttachLoop(lifecycleScope)
        lorieView.reloadPreferences(sharedPrefs)
        com.termux.x11.MainActivity.getInstance()?.clientConnectedStateChanged()
        AppLog.lifecycle("DirectSurfaceHost.onResume")
    }

    override fun onPause() {
        lifecycleActive = false
        attachController.cancelAttachLoop()
        AppLog.lifecycle("DirectSurfaceHost.onPause")
        super.onPause()
    }

    override fun onStop() {
        AppLog.lifecycle("DirectSurfaceHost.onStop")
        super.onStop()
    }

    override fun onDestroy() {
        AppLog.lifecycle("DirectSurfaceHost.onDestroy — stopping server if still running")
        if (::surfaceView.isInitialized) {
            surfaceView.removeCallbacks(overlayLayoutPoll)
        }
        com.termux.x11.MainActivity.getInstance().setConnectionChangedListener(null)
        if (::sharedPrefs.isInitialized) {
            sharedPrefs.unregisterOnSharedPreferenceChangeListener(prefListener)
        }
        appVirglHost?.stop()
        appVirglHost = null
        server?.stop()
        server = null
        super.onDestroy()
    }

}

internal data class DirectOverlayLayout(
    val left: Int,
    val top: Int,
    val width: Int,
    val height: Int,
)

internal fun computeDirectOverlayLayout(
    hostWidth: Int,
    hostHeight: Int,
    frameWidth: Int,
    frameHeight: Int,
): DirectOverlayLayout? {
    if (hostWidth <= 0 || hostHeight <= 0 || frameWidth <= 0 || frameHeight <= 0) {
        return null
    }
    val width = frameWidth.coerceAtMost(hostWidth)
    val height = frameHeight.coerceAtMost(hostHeight)
    return DirectOverlayLayout(
        left = 0,
        top = ((hostHeight - height) / 2).coerceAtLeast(0),
        width = width,
        height = height,
    )
}
