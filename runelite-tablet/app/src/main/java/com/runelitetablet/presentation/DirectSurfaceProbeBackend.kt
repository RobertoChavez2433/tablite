package com.runelitetablet.presentation

import android.content.Context
import android.content.Intent
import android.os.Build
import com.runelitetablet.domain.installer.PackageChecker
import com.runelitetablet.logging.AppLog
import com.runelitetablet.ui.DisplayPreferences

/**
 * Experimental backend for this branch. It does not launch RuneLite yet; it
 * only gives us an app-owned surface host to validate refresh, pacing, and
 * surface lifecycle independent of Termux:X11.
 */
object DirectSurfaceProbeBackend : PresentationBackend {
    override val id: String = "direct_surface_probe"
    override val displayName: String = "Direct Android Surface Probe"

    init {
        AppLog.d("PRESENTATION", "DirectSurfaceProbeBackend created: id=$id")
    }

    override fun isInstalled(packageChecker: PackageChecker): Boolean {
        val result = Build.VERSION.SDK_INT >= 35
        AppLog.d("PRESENTATION", "DirectSurfaceProbeBackend.isInstalled=$result " +
            "(requires public SurfaceControl buffer APIs on Android 15+)")
        return result
    }

    override fun applyLaunchPreferences(context: Context, displayPreferences: DisplayPreferences) = Unit

    override fun createLaunchIntent(context: Context): Intent? {
        if (Build.VERSION.SDK_INT < 35) return null
        AppLog.d("PRESENTATION", "DirectSurfaceProbeBackend.createLaunchIntent: " +
            "target=${DirectSurfaceProbeActivity::class.java.name}")
        return Intent(context, DirectSurfaceProbeActivity::class.java)
    }
}
