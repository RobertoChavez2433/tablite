package com.runelitetablet.session

import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.os.IBinder
import com.runelitetablet.RuneLiteTabletApp
import com.runelitetablet.domain.command.CommandRunner
import com.runelitetablet.logging.AppLog
import com.runelitetablet.presentation.PresentationBackend
import com.runelitetablet.termux.TermuxProcessPin
import com.runelitetablet.setup.ScriptManager
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

class RuneLiteSessionService : Service() {

    companion object {
        const val ACTION_START_SESSION = "com.runelitetablet.session.START"
        const val ACTION_STOP_SESSION = "com.runelitetablet.session.STOP"
        const val ACTION_SWITCH_TO_GAME = "com.runelitetablet.session.SWITCH"
        const val ACTION_CHECK_SESSION = "com.runelitetablet.session.CHECK"
        private const val PREFS_NAME = "runelite_session_prefs"
        private const val PREF_SESSION_ACTIVE = "session_active"
        private const val PREF_SESSION_START_TIME = "session_start_time"
        private const val SHUTDOWN_TIMEOUT_MS = 15_000L
        /** S81 Task 13: how often to unbind+rebind to re-assert the /top-app hoist. */
        private const val PIN_REFRESH_INTERVAL_MS = 20_000L

        private val _sessionState = MutableStateFlow<SessionState>(SessionState.Idle)
        val sessionState: StateFlow<SessionState> = _sessionState.asStateFlow()
        private val _sessionStartTime = MutableStateFlow(0L)
        val sessionStartTime: StateFlow<Long> = _sessionStartTime.asStateFlow()
    }

    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private lateinit var healthMonitor: SessionHealthMonitor
    private lateinit var commandRunner: CommandRunner
    private lateinit var scriptManager: ScriptManager
    private lateinit var prefs: SharedPreferences
    private lateinit var notificationHelper: SessionNotificationHelper
    private lateinit var termuxPin: TermuxProcessPin
    private lateinit var presentationBackend: PresentationBackend
    private var pinRefreshJob: Job? = null

    override fun onCreate() {
        super.onCreate()
        val container = (application as RuneLiteTabletApp).container
        commandRunner = container.termux.commandRunner
        scriptManager = container.setup.scriptManager
        presentationBackend = container.presentationBackend
        healthMonitor = SessionHealthMonitor(commandRunner, serviceScope)
        prefs = getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        notificationHelper = SessionNotificationHelper(this)
        termuxPin = container.createTermuxProcessPin()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        AppLog.state("RuneLiteSessionService.onStartCommand: action=${intent?.action} startId=$startId")
        when (intent?.action) {
            ACTION_START_SESSION -> handleStartSession()
            ACTION_STOP_SESSION -> handleStopSession()
            ACTION_SWITCH_TO_GAME -> handleSwitchToGame()
            ACTION_CHECK_SESSION -> handleCheckSession()
            null -> handleCheckSession()
        }
        return START_STICKY
    }

    override fun onBind(intent: Intent?): IBinder? = null

    /**
     * User swiped RLT from recents. Reaper-fire the orphan teardown via Termux's
     * separate-process RUN_COMMAND_SERVICE (non-blocking) before letting Android
     * tear us down. Without this, the launcher shell + JVM + virgl + Xlorie are
     * left running under Termux's UID after the task disappears.
     */
    override fun onTaskRemoved(rootIntent: Intent?) {
        AppLog.state("RuneLiteSessionService.onTaskRemoved: user swiped task — firing orphan teardown")
        super.onTaskRemoved(rootIntent)
        fireOrphanKill("onTaskRemoved")
        _sessionState.value = SessionState.Stopped
        healthMonitor.stopPolling()
        stopPinRefreshLoop()
        termuxPin.unpin()
        clearSessionState()
        stopSelf()
    }

    /**
     * Last-ditch teardown for paths that bypass handleStopSession / onTaskRemoved
     * (Android low-mem kill, Settings → Force Stop, system reboot). serviceScope
     * is cancelled at the bottom so we can't await — but the kill is dispatched
     * to Termux's RunCommandService (separate process) which keeps executing
     * after we exit.
     */
    override fun onDestroy() {
        AppLog.state("RuneLiteSessionService.onDestroy: state=${_sessionState.value} — firing orphan teardown")
        fireOrphanKill("onDestroy")
        super.onDestroy()
        stopPinRefreshLoop()
        termuxPin.unpin()
        healthMonitor.stopPolling(); serviceScope.cancel()
    }

    private fun handleStartSession() {
        val oldState = _sessionState.value
        if (oldState is SessionState.Running || oldState is SessionState.Starting) return
        AppLog.state("RuneLiteSessionService: $oldState -> Starting")
        _sessionState.value = SessionState.Starting
        val startTime = System.currentTimeMillis()
        _sessionStartTime.value = startTime
        prefs.edit().putBoolean(PREF_SESSION_ACTIVE, true).putLong(PREF_SESSION_START_TIME, startTime).apply()
        startForeground(SessionNotificationHelper.NOTIFICATION_ID, notificationHelper.buildNotification("Starting RuneLite..."))
        // S75 Path A: pin Termux to our FGS's cpuset so proot/JVM/virgl inherit
        // /top-app (CPUs 0-7) instead of /background (CPUs 0-3). Must happen
        // AFTER startForeground() so this process is already PROCESS_STATE_TOP.
        termuxPin.pin()
        // S81 Task 13: periodic rebind so Android's AMS re-evaluation window can't
        // demote Termux back to /moderate (observed drift 25s post-launch). Every
        // 20s we unbind + rebind which re-asserts BIND_IMPORTANT +
        // BIND_SCHEDULE_LIKE_TOP_APP. Cancelled on session stop / onDestroy.
        startPinRefreshLoop()
        healthMonitor.startPolling(::handleHealthStateChange, initialDelayMs = 30_000L)
    }

    private fun startPinRefreshLoop() {
        pinRefreshJob?.cancel()
        pinRefreshJob = serviceScope.launch {
            while (isActive) {
                delay(PIN_REFRESH_INTERVAL_MS)
                if (!isActive) break
                termuxPin.refreshPin()
            }
        }
    }

    private fun stopPinRefreshLoop() {
        pinRefreshJob?.cancel()
        pinRefreshJob = null
    }

    private fun handleStopSession() {
        AppLog.state("RuneLiteSessionService: ${_sessionState.value} -> Stopped")
        _sessionState.value = SessionState.Stopped; healthMonitor.stopPolling(); stopPinRefreshLoop(); termuxPin.unpin()
        serviceScope.launch {
            try {
                if (scriptManager.deployScripts()) {
                    val result = commandRunner.execute(commandPath = scriptManager.getScriptPath("shutdown-session.sh"), background = true, timeoutMs = SHUTDOWN_TIMEOUT_MS)
                    if (result.stdout?.contains("SHUTDOWN_COMPLETE") != true) aggressiveShutdown()
                } else aggressiveShutdown()
            } catch (e: CancellationException) { throw e }
            catch (e: Exception) { aggressiveShutdown() }
            finally { clearSessionState(); stopSelf() }
        }
    }

    private fun handleSwitchToGame() {
        val intent = presentationBackend.createSwitchIntent(this)
        if (intent != null) startActivity(intent)
    }

    private fun handleCheckSession() {
        startForeground(SessionNotificationHelper.NOTIFICATION_ID, notificationHelper.buildNotification("Checking session..."))
        serviceScope.launch {
            when (healthMonitor.checkHealth()) {
                is SessionState.Running -> {
                    _sessionState.value = SessionState.Running
                    if (_sessionStartTime.value == 0L) _sessionStartTime.value = prefs.getLong(PREF_SESSION_START_TIME, System.currentTimeMillis())
                    notificationHelper.updateIfChanged("RuneLite is running")
                    healthMonitor.startPolling(::handleHealthStateChange)
                }
                else -> { _sessionState.value = SessionState.Idle; clearSessionState(); stopSelf() }
            }
        }
    }

    private fun handleHealthStateChange(state: SessionState) {
        AppLog.state("RuneLiteSessionService.handleHealthStateChange: ${_sessionState.value} -> $state")
        when (state) {
            is SessionState.Running -> {
                _sessionState.value = SessionState.Running
                notificationHelper.updateIfChanged("Playing for ${formatElapsedTime()}")
            }
            is SessionState.Stopped, is SessionState.Error -> {
                _sessionState.value = state
                healthMonitor.stopPolling(); termuxPin.unpin(); clearSessionState(); stopSelf()
            }
            else -> {}
        }
    }

    private fun clearSessionState() {
        prefs.edit().putBoolean(PREF_SESSION_ACTIVE, false).remove(PREF_SESSION_START_TIME).apply()
        _sessionStartTime.value = 0L
    }

    private fun formatElapsedTime(): String {
        val s = _sessionStartTime.value; if (s == 0L) return "0 min"
        val m = ((System.currentTimeMillis() - s) / 60_000).toInt()
        return if (m < 60) "$m min" else "${m / 60}h ${m % 60}m"
    }

    private suspend fun aggressiveShutdown() {
        try {
            commandRunner.execute(
                commandPath = "${CommandRunner.TERMUX_BIN_PATH}/bash",
                arguments = arrayOf("-c", ORPHAN_KILL_BASH),
                background = true, timeoutMs = 10_000L
            )
        } catch (e: CancellationException) { throw e }
        catch (e: Exception) { AppLog.e("SESSION", "aggressiveShutdown: ${e.message}", e) }
    }

    /**
     * Fire-and-forget variant for onTaskRemoved / onDestroy where serviceScope
     * is about to be cancelled and we can't await. Dispatches to Termux's
     * separate RUN_COMMAND_SERVICE process; the kill executes to completion
     * regardless of our process state.
     */
    private fun fireOrphanKill(label: String) {
        val ok = commandRunner.launchBackground(
            commandPath = "${CommandRunner.TERMUX_BIN_PATH}/bash",
            arguments = arrayOf("-c", ORPHAN_KILL_BASH)
        )
        AppLog.state("RuneLiteSessionService.fireOrphanKill[$label]: launchBackground=$ok")
    }
}

/**
 * Shared kill-list used by both the awaited graceful path (aggressiveShutdown)
 * and the fire-and-forget orphan-reaper path (fireOrphanKill). Killing the
 * launcher shell itself (`launch-runelite`) is critical — its EXIT trap then
 * fires, which cascades to any children we don't pkill by name above.
 */
private val ORPHAN_KILL_BASH = """
    pkill -f 'net.runelite.client.RuneLite' 2>/dev/null || true
    pkill -f 'RuneLite.jar' 2>/dev/null || true
    pkill -f 'openbox' 2>/dev/null || true
    pkill -f 'launch-runelite' 2>/dev/null || true
    pkill -f 'proot-distro' 2>/dev/null || true
    pkill -f 'proot --' 2>/dev/null || true
    pulseaudio --kill 2>/dev/null || true
    pkill -f 'virgl_test_server' 2>/dev/null || true
    pkill -f 'termux-x11' 2>/dev/null || true
    am broadcast -a com.termux.x11.ACTION_STOP --user 0 2>/dev/null || true
    rm -f "${'$'}HOME/.rlt-session.pid" "${'$'}PREFIX/tmp/.rlt-session-alive" "${'$'}HOME/.rlt-launch-env.sh" 2>/dev/null || true
""".trimIndent()
