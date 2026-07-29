package com.runelitetablet

import android.app.Application
import android.app.NotificationChannel
import android.app.NotificationManager
import com.runelitetablet.di.AppContainer
import com.runelitetablet.logging.AppLog
import com.runelitetablet.logging.AgentStateRegistry
import com.runelitetablet.logging.PerfSnapshots

class RuneLiteTabletApp : Application() {
    lateinit var container: AppContainer
        private set

    /** Backward-compatible accessor — use container.core.httpClient for new code. */
    val httpClient get() = container.core.httpClient

    override fun onCreate() {
        super.onCreate()
        AgentStateRegistry.install(this)
        container = AppContainer(this)
        AppLog.lifecycle(
            "RuneLiteTabletApp.onCreate: PID=${android.os.Process.myPid()} " +
                "thread=${Thread.currentThread().name} | ${PerfSnapshots.perfSnapshot(this)}"
        )
        createNotificationChannels()
        AppLog.lifecycle("RuneLiteTabletApp.onCreate complete: container created, notification channels created")
    }

    private fun createNotificationChannels() {
        val channel = NotificationChannel(
            CHANNEL_SESSION,
            "RuneLite Session",
            NotificationManager.IMPORTANCE_LOW
        ).apply {
            description = "Shows when RuneLite is running"
            setShowBadge(false)
        }
        val nm = getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(channel)
    }

    companion object {
        const val CHANNEL_SESSION = "runelite_session"
    }
}
