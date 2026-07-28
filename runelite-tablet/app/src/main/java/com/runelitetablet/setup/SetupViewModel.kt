package com.runelitetablet.setup

import android.app.Activity
import android.content.Context
import android.content.Intent
import androidx.activity.result.ActivityResult
import androidx.activity.result.ActivityResultLauncher
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.runelitetablet.auth.GameCharacter
import com.runelitetablet.domain.auth.CredentialStore
import com.runelitetablet.domain.logging.Logger
import com.runelitetablet.domain.setup.SetupStateStore
import com.runelitetablet.presentation.PresentationBackend
import com.runelitetablet.presentation.PresentationBackends
import com.runelitetablet.session.RuneLiteSessionService
import com.runelitetablet.session.SessionState
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.concurrent.atomic.AtomicBoolean

class SetupViewModel(
    private val context: Context,
    private val orchestrator: SetupOrchestrator,
    private val credentialStore: CredentialStore,
    private val stateStore: SetupStateStore,
    private val scriptDeployer: com.runelitetablet.domain.setup.ScriptDeployer,
    private val presentationBackend: PresentationBackend,
    val authCoordinator: AuthCoordinator,
    val launchCoordinator: LaunchCoordinator,
    internal val _currentScreen: MutableStateFlow<AppScreen> = MutableStateFlow(AppScreen.Setup),
    private val logger: Logger? = null,
    private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO
) : ViewModel() {

    val steps: StateFlow<List<StepState>> = orchestrator.steps
    val currentOutput: StateFlow<String?> = orchestrator.currentOutput
    val currentScreen: StateFlow<AppScreen> = _currentScreen.asStateFlow()
    val canLaunch: StateFlow<Boolean> = orchestrator.steps
        .map { steps -> steps.all { it.status is StepStatus.Completed } }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), false)
    val sessionState: StateFlow<SessionState> = RuneLiteSessionService.sessionState
    val sessionStartTime: StateFlow<Long> = RuneLiteSessionService.sessionStartTime
    val permissionPhase: StateFlow<PermissionPhase> = orchestrator.permissionPhase
    val isPermissionStepActive: StateFlow<Boolean> = combine(
        orchestrator.steps, orchestrator.awaitingPermissionCompletion
    ) { steps, awaiting ->
        val permStep = steps.find { it.step == SetupStep.EnablePermissions }
        permStep?.status is StepStatus.InProgress && awaiting
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), false)
    private val _commandCopied = MutableStateFlow(false)
    val commandCopied: StateFlow<Boolean> = _commandCopied.asStateFlow()
    val launchState: StateFlow<LaunchState> = launchCoordinator.launchState
    val healthStatus: StateFlow<HealthCheckResult?> = launchCoordinator.healthStatus
    val showHealthDialog: StateFlow<List<String>?> = launchCoordinator.showHealthDialog

    private val setupStarted = AtomicBoolean(false)
    private val isRetrying = AtomicBoolean(false)

    fun getDisplayName(): String? = authCoordinator.getDisplayName()
    fun recordUiAction(message: String) = logger?.ui(message)
    override fun onCleared() { super.onCleared(); authCoordinator.clearState() }

    fun startSetup() {
        if (!setupStarted.compareAndSet(false, true)) return
        logger?.state("SetupViewModel.startSetup: launching setup coroutine")
        viewModelScope.launch {
            orchestrator.runSetup()
            val allDone = orchestrator.steps.value.all { it.status is StepStatus.Completed }
            if (allDone) {
                val hasCreds = withContext(ioDispatcher) { credentialStore.hasCredentials() }
                val nextScreen = if (hasCreds) AppScreen.Launch else AppScreen.Login
                logger?.state("SetupViewModel: setup complete, navigating to $nextScreen hasCreds=$hasCreds")
                _currentScreen.value = nextScreen
            }
        }
    }

    fun retry() {
        if (!isRetrying.compareAndSet(false, true)) return
        viewModelScope.launch {
            try { orchestrator.retryCurrentStep() }
            finally { isRetrying.set(false) }
        }
    }

    fun startLogin() = authCoordinator.startLogin()
    fun handleAuthResult(result: ActivityResult) = authCoordinator.handleAuthResult(result)
    fun onCharacterSelected(character: GameCharacter) = authCoordinator.onCharacterSelected(character)
    fun skipLogin() = authCoordinator.skipLogin()
    fun signOut() = authCoordinator.signOut()
    fun launchRuneLite() = launchCoordinator.launchRuneLite()
    fun stopSession() = launchCoordinator.stopSession()
    fun switchToGame() = launchCoordinator.switchToGame()
    fun checkSession() = launchCoordinator.checkSession()
    fun dismissHealthDialog() = launchCoordinator.dismissHealthDialog()
    fun launchAnyway() = launchCoordinator.launchAnyway()
    fun navigateToSettings() { _currentScreen.value = AppScreen.Settings }
    fun navigateToLogViewer() { _currentScreen.value = AppScreen.LogViewer }
    fun navigateBackToLaunch() { _currentScreen.value = AppScreen.Launch }

    fun resetSetup() {
        logger?.state("SetupViewModel.resetSetup: clearing all state")
        viewModelScope.launch {
            stateStore.clearAll()
            scriptDeployer.invalidateDeployCache()
            orchestrator.resetState()
            setupStarted.set(false)
            _currentScreen.value = AppScreen.Setup
        }
    }

    fun runSetupForHealth() {
        launchCoordinator.dismissHealthDialog()
        setupStarted.set(false)
        _currentScreen.value = AppScreen.Setup
    }

    val termuxConfigCommand: String =
        "mkdir -p ~/.termux && echo allow-external-apps=true >> ~/.termux/termux.properties && " +
            "echo \"extra-keys = [['ESC','CTRL','ALT','LEFT','DOWN','UP','RIGHT','TAB']," +
            "['~','/','-','|','HOME','END','PGUP','PGDN']]\" >> ~/.termux/termux.properties && " +
            "termux-reload-settings && echo Done && am start -n ${context.packageName}/.MainActivity"

    fun copyConfigAndOpenTermux() {
        logger?.ui("SetupViewModel.copyConfigAndOpenTermux: copying config to clipboard and launching Termux")
        val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as android.content.ClipboardManager
        clipboard.setPrimaryClip(android.content.ClipData.newPlainText("Termux Config", termuxConfigCommand))
        _commandCopied.value = true
        val intent = context.packageManager.getLaunchIntentForPackage(PermissionHandler.TERMUX_PACKAGE)
        if (intent != null) orchestrator.actions?.launchIntent(intent)
    }

    fun checkPermissionPhase() {
        if (!isRetrying.compareAndSet(false, true)) return
        viewModelScope.launch {
            try {
                if (!orchestrator.awaitingPermissionCompletion.value) return@launch
                orchestrator.advancePermissionPhase()
            } finally { isRetrying.set(false) }
        }
    }

    fun requestBatteryOptimization() {
        logger?.ui("SetupViewModel.requestBatteryOptimization: requesting battery exemptions")
        orchestrator.requestBatteryOptimization()
        orchestrator.requestOwnBatteryOptimization()
    }

    fun openDirectSurfaceProbe() {
        logger?.ui("SetupViewModel.openDirectSurfaceProbe: launching surface probe activity")
        val intent = PresentationBackends.directSurfaceProbe.createLaunchIntent(context)
        if (intent != null) orchestrator.actions?.launchIntent(intent)
    }

    fun recheckPermissions() {
        if (!isRetrying.compareAndSet(false, true)) return
        viewModelScope.launch {
            try {
                if (orchestrator.awaitingPermissionCompletion.value) {
                    orchestrator.advancePermissionPhase()
                    return@launch
                }
                val hasFailedInstall = orchestrator.steps.value.any {
                    (it.step == SetupStep.InstallTermux || it.step == SetupStep.InstallTermuxX11) &&
                        it.status is StepStatus.Failed
                }
                if (hasFailedInstall) orchestrator.retryCurrentStep()
            } finally { isRetrying.set(false) }
        }
    }

    fun bindActions(activity: Activity, authLauncher: ActivityResultLauncher<Intent>) {
        orchestrator.actions = SetupActionsImpl(activity, authLauncher)
    }

    fun unbindActions() { orchestrator.actions = null }

    companion object {
        const val REQUEST_TERMUX_PERMISSION = 1001
        const val REQUEST_NOTIFICATION_PERMISSION = 1002
    }
}
