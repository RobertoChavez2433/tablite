package com.runelitetablet.directsurface

import android.view.Surface
import com.runelitetablet.logging.AppLog

/**
 * Thin Kotlin wrapper over the librlawtsurface JNI. The native side runs the
 * full server loop (AF_UNIX abstract socket → AHB pool → blit-to-Surface), so
 * Kotlin only needs to manage start/stop lifecycle keyed off SurfaceHolder
 * callbacks.
 *
 * The legacy direct producer uses a Linux abstract-namespace UDS at
 * `\0RLAWT_SURFACE_ABSTRACT_NAME` (default "rlt-rlawt-surface"). Current
 * production SELinux policy denies that Termux-to-app connection, so measured
 * completed-frame experiments use a loopback-only TCP listener selected by
 * [DirectSurfaceHostActivity.EXTRA_TCP_PORT].
 */
class RlawtSurfaceServer private constructor(private val handle: Long) {

    fun frameDimensions(): Pair<Int, Int>? {
        if (handle == 0L) {
            return null
        }
        val packed = nativeFrameDimensions(handle)
        val width = (packed ushr 32).toInt()
        val height = packed.toInt()
        return if (width > 0 && height > 0) width to height else null
    }

    fun stop() {
        if (handle != 0L) {
            AppLog.i("DirectSurface", "RlawtSurfaceServer.stop() handle=0x${handle.toString(16)}")
            nativeStop(handle)
        } else {
            AppLog.w("DirectSurface", "RlawtSurfaceServer.stop() called with handle=0 — ignoring")
        }
    }

    companion object {
        private const val TAG = "DirectSurface"
        private var loaded = false

        fun start(
            surface: Surface,
            abstractName: String? = null,
            tcpPort: Int = 0,
            cpuUpload: Boolean = false,
        ): RlawtSurfaceServer? {
            AppLog.i(
                TAG,
                "RlawtSurfaceServer.start: surface=$surface abstractName=$abstractName " +
                    "tcpPort=$tcpPort cpuUpload=$cpuUpload"
            )
            ensureLoaded()
            val h = try {
                nativeStart(surface, abstractName, tcpPort, cpuUpload)
            } catch (t: Throwable) {
                AppLog.e(TAG, "nativeStart threw", t)
                return null
            }
            if (h == 0L) {
                AppLog.e(TAG, "nativeStart returned 0 (consumer worker did not start)")
                return null
            }
            AppLog.i(TAG, "RlawtSurfaceServer started handle=0x${h.toString(16)}")
            return RlawtSurfaceServer(h)
        }

        /**
         * Starts the completed-frame consumer without owning a second Android
         * Surface. Native code registers its persistent AHB pool with the
         * already-loaded Xlorie renderer, which composites the game canvas,
         * Swing chrome, and cursor into LorieView's single EGL surface.
         */
        fun startCompositor(
            abstractName: String,
            captureId: String,
            cpuUpload: Boolean = true,
        ): RlawtSurfaceServer? {
            AppLog.i(
                TAG,
                "RlawtSurfaceServer.startCompositor abstractName=$abstractName " +
                    "captureId=$captureId cpuUpload=$cpuUpload"
            )
            ensureLoaded()
            val h = try {
                nativeStartCompositor(abstractName, captureId, cpuUpload)
            } catch (t: Throwable) {
                AppLog.e(TAG, "nativeStartCompositor threw", t)
                return null
            }
            if (h == 0L) {
                AppLog.e(TAG, "nativeStartCompositor returned 0")
                return null
            }
            AppLog.i(TAG, "Xlorie compositor server started handle=0x${h.toString(16)}")
            return RlawtSurfaceServer(h)
        }

        @Synchronized
        private fun ensureLoaded() {
            if (loaded) return
            try {
                System.loadLibrary("rlawtsurface")
                AppLog.i(TAG, "librlawtsurface.so loaded")
                loaded = true
            } catch (e: UnsatisfiedLinkError) {
                AppLog.e(TAG, "System.loadLibrary(rlawtsurface) failed", e)
                throw e
            }
        }

        @JvmStatic
        private external fun nativeStart(
            surface: Surface,
            abstractName: String?,
            tcpPort: Int,
            cpuUpload: Boolean,
        ): Long

        @JvmStatic
        private external fun nativeStop(handle: Long)

        @JvmStatic
        private external fun nativeFrameDimensions(handle: Long): Long

        @JvmStatic
        private external fun nativeStartCompositor(
            abstractName: String,
            captureId: String,
            cpuUpload: Boolean,
        ): Long
    }
}
