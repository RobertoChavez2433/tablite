package com.runelitetablet.logging

import android.app.Activity
import android.app.Application
import android.os.Bundle
import com.runelitetablet.presentation.hybrid.HybridX11Bridge
import com.runelitetablet.session.RuneLiteSessionService
import com.runelitetablet.session.SessionState
import com.runelitetablet.telemetry.CaptureSessionRegistry
import kotlinx.serialization.Serializable
import java.util.concurrent.atomic.AtomicReference

@Serializable
data class AgentUiStateV1(
    val activity: String? = null,
    val lifecycle: String = "unknown",
    val screen: String? = null,
    val sentinel: String? = null
)

@Serializable
data class AgentSetupStateV1(
    val launchState: String = "idle",
    val completedSteps: Int = 0,
    val totalSteps: Int = 0,
    val failedSteps: Int = 0,
    val activeStep: String? = null
)

@Serializable
data class AgentSessionStateV1(
    val state: String,
    val startedAtEpochMs: Long? = null
)

@Serializable
data class AgentRenderStateV1(
    val backend: String? = null,
    val captureRunId: String? = null,
    val telemetryMode: String? = null,
    val targetHz: Int? = null,
    val recordedEvents: Long? = null,
    val telemetryLostEvents: Long? = null,
    val x11BinderAlive: Boolean,
    val x11Generation: Int,
    val x11LastEvent: String
)

@Serializable
data class AgentStateMachineV1(
    val schemaVersion: Int = 1,
    val posture: String,
    val reasons: List<String>,
    val observationReady: Boolean,
    val blockers: List<String>
)

@Serializable
data class AgentStateSnapshotV1(
    val schemaVersion: Int = 1,
    val capturedAtEpochMs: Long,
    val ui: AgentUiStateV1,
    val setup: AgentSetupStateV1,
    val session: AgentSessionStateV1,
    val render: AgentRenderStateV1,
    val stateMachine: AgentStateMachineV1
) {
    fun stableCopy(): AgentStateSnapshotV1 = copy(capturedAtEpochMs = 0)
}

object AgentStateRegistry : Application.ActivityLifecycleCallbacks {
    private val activity = AtomicReference<String?>(null)
    private val lifecycle = AtomicReference("unknown")
    private val screen = AtomicReference<String?>(null)
    private val launchState = AtomicReference("idle")
    private val setup = AtomicReference(AgentSetupStateV1())

    fun install(application: Application) {
        application.registerActivityLifecycleCallbacks(this)
    }

    fun updateScreen(value: String) {
        screen.set(value)
    }

    fun updateSetup(
        launchState: String,
        completedSteps: Int,
        totalSteps: Int,
        failedSteps: Int,
        activeStep: String?
    ) {
        this.launchState.set(launchState)
        setup.set(
            AgentSetupStateV1(
                launchState = launchState,
                completedSteps = completedSteps,
                totalSteps = totalSteps,
                failedSteps = failedSteps,
                activeStep = activeStep
            )
        )
    }

    fun uiSnapshot(): AgentUiStateV1 {
        val activityName = activity.get()
        val screenName = screen.get()
        return AgentUiStateV1(
            activity = activityName,
            lifecycle = lifecycle.get(),
            screen = screenName,
            sentinel = sentinelFor(activityName, screenName)
        )
    }

    fun setupSnapshot(): AgentSetupStateV1 = setup.get()

    private fun sentinelFor(activityName: String?, screenName: String?): String? =
        when {
            activityName?.endsWith("HybridX11HostActivity") == true -> "hybrid_x11_surface"
            activityName?.endsWith("DirectSurfaceHostActivity") == true -> "direct_surface"
            activityName?.endsWith("DirectSurfaceProbeActivity") == true -> "direct_surface_probe"
            activityName?.endsWith("GeckoAuthActivity") == true -> "auth_surface"
            activityName?.endsWith("MainActivity") == true && screenName != null ->
                "${screenName}_screen"
            else -> null
        }

    override fun onActivityCreated(activity: Activity, state: Bundle?) =
        observe(activity, "created")
    override fun onActivityStarted(activity: Activity) = observe(activity, "started")
    override fun onActivityResumed(activity: Activity) = observe(activity, "resumed")
    override fun onActivityPaused(activity: Activity) = observe(activity, "paused")
    override fun onActivityStopped(activity: Activity) = observe(activity, "stopped")
    override fun onActivitySaveInstanceState(activity: Activity, state: Bundle) = Unit
    override fun onActivityDestroyed(activity: Activity) {
        if (this.activity.get() == activity.javaClass.name) {
            lifecycle.set("destroyed")
        }
    }

    private fun observe(value: Activity, state: String) {
        activity.set(value.javaClass.name)
        lifecycle.set(state)
    }
}

object AgentStateProjection {
    fun snapshot(nowEpochMs: Long = System.currentTimeMillis()): AgentStateSnapshotV1 {
        val ui = AgentStateRegistry.uiSnapshot()
        val setup = AgentStateRegistry.setupSnapshot()
        val sessionValue = RuneLiteSessionService.sessionState.value
        val session = AgentSessionStateV1(
            state = sessionValue.safeName(),
            startedAtEpochMs = RuneLiteSessionService.sessionStartTime.value
                .takeIf { it > 0L }
        )
        val capture = CaptureSessionRegistry.active
        val captureSummary = capture?.recorder?.snapshotSummary()
        val x11 = HybridX11Bridge.state.value
        val render = AgentRenderStateV1(
            backend = capture?.manifest?.backend,
            captureRunId = capture?.manifest?.runId,
            telemetryMode = capture?.manifest?.telemetryMode,
            targetHz = capture?.manifest?.targetHz,
            recordedEvents = captureSummary?.recordedEvents,
            telemetryLostEvents = captureSummary?.lostEvents,
            x11BinderAlive = x11.binderAlive,
            x11Generation = x11.generation,
            x11LastEvent = x11.lastEvent
        )
        return AgentStateSnapshotV1(
            capturedAtEpochMs = nowEpochMs,
            ui = ui,
            setup = setup,
            session = session,
            render = render,
            stateMachine = deriveStateMachine(ui, setup, sessionValue, render)
        )
    }

    private fun deriveStateMachine(
        ui: AgentUiStateV1,
        setup: AgentSetupStateV1,
        session: SessionState,
        render: AgentRenderStateV1
    ): AgentStateMachineV1 {
        val reasons = mutableListOf<String>()
        val posture = when {
            session is SessionState.Error || setup.failedSteps > 0 ||
                setup.launchState == "failed" -> {
                reasons += "explicit_error"
                "errored"
            }
            ui.activity == null -> {
                reasons += "activity_unavailable"
                "booting"
            }
            ui.sentinel == "direct_surface" && session is SessionState.Running -> {
                reasons += "direct_surface_visible"
                "running_direct"
            }
            session is SessionState.Running && render.x11BinderAlive -> {
                reasons += "runelite_running_with_x11_binder"
                "running_x11"
            }
            session is SessionState.Starting || setup.launchState in ACTIVE_LAUNCH_STATES -> {
                reasons += "launch_transition_active"
                "launching"
            }
            session is SessionState.Stopped -> {
                reasons += "session_stopped"
                "stopped"
            }
            setup.totalSteps > 0 && setup.completedSteps < setup.totalSteps -> {
                reasons += "setup_incomplete"
                "setup_required"
            }
            ui.screen == "login" || ui.screen == "character_select" -> {
                reasons += "authentication_ui_visible"
                "awaiting_auth"
            }
            ui.screen == "launch" -> {
                reasons += "launch_ui_visible"
                "ready_to_launch"
            }
            else -> {
                reasons += "stable_tablite_ui"
                "idle"
            }
        }
        val blockers = buildList {
            if (ui.sentinel == null) add("visible_sentinel_missing")
            if (posture == "errored") add("explicit_error")
            if ((render.telemetryLostEvents ?: 0L) > 0L) add("telemetry_loss")
        }
        return AgentStateMachineV1(
            posture = posture,
            reasons = reasons,
            observationReady = blockers.isEmpty(),
            blockers = blockers
        )
    }

    private fun SessionState.safeName(): String = when (this) {
        SessionState.Idle -> "idle"
        SessionState.Starting -> "starting"
        SessionState.Running -> "running"
        SessionState.Stopped -> "stopped"
        is SessionState.Error -> "error"
    }

    private val ACTIVE_LAUNCH_STATES = setOf(
        "checking_update", "updating", "checking_health", "refreshing_tokens",
        "validating_session", "launching"
    )
}
