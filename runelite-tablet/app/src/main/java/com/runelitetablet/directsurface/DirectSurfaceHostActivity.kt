package com.runelitetablet.directsurface

import android.os.Build
import android.os.Bundle
import android.view.Surface
import android.view.SurfaceHolder
import android.view.SurfaceView
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.enableEdgeToEdge
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import com.runelitetablet.logging.AppLog

/**
 * Hosts a SurfaceView for the rlawt direct-Android-surface path.
 *
 * On surfaceCreated, spawns the librlawtsurface worker thread which binds
 * an abstract-namespace UDS, accepts the rlawt-JVM producer, hands it an
 * AHB pool, and blits each FRAME_READY onto this SurfaceView via EGL.
 *
 * Gated behind feature flag `EXTRA_FORCE_DIRECT_SURFACE`; the rlawt JVM-side
 * librlawt.so must be built with -DRLAWT_DIRECT_SURFACE=ON for the producer
 * to attempt connection. Until we ship that .so via the launcher, this
 * activity is reachable only via adb-launched intent for validation runs.
 */
class DirectSurfaceHostActivity : ComponentActivity() {

    companion object {
        const val EXTRA_ABSTRACT_NAME = "rlt.abstract_name"
        private const val TAG = "DirectSurface"
    }

    private lateinit var surfaceView: SurfaceView
    private var server: RlawtSurfaceServer? = null
    private var abstractName: String? = null

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
        AppLog.lifecycle("DirectSurfaceHost.onCreate intent=$intent abstractName=$abstractName")

        surfaceView = SurfaceView(this).apply {
            holder.addCallback(object : SurfaceHolder.Callback {
                override fun surfaceCreated(holder: SurfaceHolder) {
                    val s = holder.surface
                    AppLog.lifecycle("DirectSurfaceHost.surfaceCreated holder=$holder surface=$s isValid=${s.isValid}")
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                        runCatching {
                            s.setFrameRate(120f, Surface.FRAME_RATE_COMPATIBILITY_FIXED_SOURCE)
                        }.onSuccess {
                            AppLog.i(TAG, "DirectSurfaceHost.surfaceCreated setFrameRate(120Hz) OK")
                        }.onFailure {
                            AppLog.w(TAG, "DirectSurfaceHost.surfaceCreated setFrameRate(120Hz) failed: ${it.message}")
                        }
                    }
                    val srv = RlawtSurfaceServer.start(s, abstractName)
                    if (srv == null) {
                        AppLog.e(TAG, "RlawtSurfaceServer.start returned null in surfaceCreated")
                    } else {
                        AppLog.i(TAG, "RlawtSurfaceServer running")
                    }
                    server = srv
                }

                override fun surfaceChanged(holder: SurfaceHolder, format: Int, width: Int, height: Int) {
                    AppLog.lifecycle("DirectSurfaceHost.surfaceChanged ${width}x$height format=$format holder=$holder")
                }

                override fun surfaceDestroyed(holder: SurfaceHolder) {
                    AppLog.lifecycle("DirectSurfaceHost.surfaceDestroyed holder=$holder — stopping server")
                    server?.stop()
                    server = null
                }
            })
        }
        setContentView(surfaceView)
    }

    override fun onNewIntent(intent: android.content.Intent) {
        super.onNewIntent(intent)
        val nextName = intent.getStringExtra(EXTRA_ABSTRACT_NAME)
        AppLog.lifecycle("DirectSurfaceHost.onNewIntent intent=$intent abstractName=$nextName serverRunning=${server != null}")
        if (!nextName.isNullOrBlank()) {
            abstractName = nextName
        }
    }

    override fun onStart() {
        super.onStart()
        AppLog.lifecycle("DirectSurfaceHost.onStart")
    }

    override fun onResume() {
        super.onResume()
        AppLog.lifecycle("DirectSurfaceHost.onResume")
    }

    override fun onPause() {
        AppLog.lifecycle("DirectSurfaceHost.onPause")
        super.onPause()
    }

    override fun onStop() {
        AppLog.lifecycle("DirectSurfaceHost.onStop")
        super.onStop()
    }

    override fun onDestroy() {
        AppLog.lifecycle("DirectSurfaceHost.onDestroy — stopping server if still running")
        server?.stop()
        server = null
        super.onDestroy()
    }
}
