package com.runelitetablet.directsurface

import android.hardware.HardwareBuffer
import com.runelitetablet.logging.AppLog

/**
 * CPU-fills probe buffers once so SurfaceControl pixel correctness can be
 * checked without adding a per-frame consumer-side GLES blit.
 */
object HardwareBufferPatternWriter {
    @Volatile
    private var loaded = false

    fun fill(buffer: HardwareBuffer, slot: Int, generation: Int): Boolean {
        ensureLoaded()
        return nativeFill(buffer, slot, generation)
    }

    @Synchronized
    private fun ensureLoaded() {
        if (loaded) return
        System.loadLibrary("rlawtsurface")
        loaded = true
        AppLog.i("SurfaceControlProbe", "librlawtsurface loaded for HardwareBuffer pattern fill")
    }

    @JvmStatic
    private external fun nativeFill(buffer: HardwareBuffer, slot: Int, generation: Int): Boolean
}
