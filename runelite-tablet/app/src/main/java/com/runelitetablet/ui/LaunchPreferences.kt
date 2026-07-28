package com.runelitetablet.ui

import android.content.Context
import com.runelitetablet.telemetry.RendererBackend
import com.runelitetablet.telemetry.TelemetryMode

/**
 * Persistent launch preferences backed by SharedPreferences.
 * Controls which RuneLite launch path is used (proot vs native-Termux).
 * Mirrors the DisplayPreferences pattern.
 */
class LaunchPreferences(context: Context) {
    private val prefs = context.getSharedPreferences("launch_prefs", Context.MODE_PRIVATE)

    /**
     * When true, launches RuneLite directly under Termux-native openjdk-21 via
     * `launch-runelite-native.sh`, bypassing proot-distro entirely. Requires the
     * Bionic rlawt jar to have been deployed to `$HOME/.rlt/rlawt-1.8-bionic.jar`
     * by ScriptManager.deployJars().
     *
     * Default: false (the proven proot path). Opt in explicitly to test the
     * native path; flip back to false if the native path regresses.
     */
    var useNativeTermux: Boolean
        get() = prefs.getBoolean("use_native_termux", false)
        set(value) = prefs.edit().putBoolean("use_native_termux", value).apply()

    /**
     * Single renderer selection used by the app and both launch scripts.
     *
     * New launches communicate exclusively through RLT_RENDERER_BACKEND.
     * Stale prototype marker files are intentionally ignored.
     */
    var rendererBackend: RendererBackend
        get() {
            val stored = prefs.getString("renderer_backend", null)
            return if (stored != null) {
                RendererBackend.fromEnv(stored)
            } else {
                RendererBackend.NATIVE_X11_VIRGL
            }
        }
        set(value) {
            prefs.edit().putString("renderer_backend", value.envValue).apply()
        }

    val useDirectSurface: Boolean
        get() = rendererBackend == RendererBackend.DIRECT_VIRGL_AHB ||
            rendererBackend == RendererBackend.DIRECT_ZINK_AHB

    var telemetryMode: TelemetryMode
        get() = TelemetryMode.fromEnv(prefs.getString("telemetry_mode", TelemetryMode.SUMMARY.envValue))
        set(value) = prefs.edit().putString("telemetry_mode", value.envValue).apply()

    var targetHz: Int
        get() = prefs.getInt("target_hz", 120).coerceIn(30, 240)
        set(value) = prefs.edit().putInt("target_hz", value.coerceIn(30, 240)).apply()
}
