package com.runelitetablet.presentation.setup

import com.runelitetablet.installer.ApkDownloader
import com.runelitetablet.installer.ApkInstaller
import com.runelitetablet.setup.AppScreen
import com.runelitetablet.setup.AuthCoordinator
import com.runelitetablet.setup.LaunchCoordinator
import com.runelitetablet.setup.MarkerReconciler
import com.runelitetablet.setup.SetupOrchestrator
import com.runelitetablet.setup.SetupStepRunner
import com.runelitetablet.setup.SetupVerifier
import com.runelitetablet.setup.SetupViewModel
import com.runelitetablet.auth.JagexOAuth2Manager
import com.runelitetablet.testutil.FakeCleaner
import com.runelitetablet.testutil.FakeCommandRunner
import com.runelitetablet.testutil.FakeCredentialStore
import com.runelitetablet.testutil.FakeCaptureController
import com.runelitetablet.testutil.FakePackageChecker
import com.runelitetablet.testutil.FakePermissionChecker
import com.runelitetablet.testutil.FakeScriptDeployer
import com.runelitetablet.testutil.FakeSetupStateStore
import com.runelitetablet.testutil.MainDispatcherRule
import com.runelitetablet.testutil.PrintLogger
import com.runelitetablet.testutil.UnsafeHelper
import com.runelitetablet.ui.DisplayPreferences
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import org.junit.Assert.*
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import android.app.Application

@OptIn(ExperimentalCoroutinesApi::class)
class SetupViewModelTest {

    @get:Rule val mainDispatcherRule = MainDispatcherRule()

    private lateinit var viewModel: SetupViewModel
    private lateinit var credentialStore: FakeCredentialStore
    private lateinit var stateStore: FakeSetupStateStore
    private lateinit var orchestrator: SetupOrchestrator
    private val logger = PrintLogger()
    private val screenFlow = MutableStateFlow<AppScreen>(AppScreen.Setup)

    @Before
    fun setup() {
        val testDispatcher = UnconfinedTestDispatcher()
        credentialStore = FakeCredentialStore()
        stateStore = FakeSetupStateStore()
        val commandRunner = FakeCommandRunner()
        val packageChecker = FakePackageChecker()
        val scriptDeployer = FakeScriptDeployer()

        orchestrator = SetupOrchestrator(
            packageChecker = packageChecker,
            stepRunner = SetupStepRunner(commandRunner, packageChecker,
                UnsafeHelper.allocate(ApkDownloader::class.java),
                UnsafeHelper.allocate(ApkInstaller::class.java),
                scriptDeployer, stateStore, logger),
            verifier = SetupVerifier(commandRunner, logger),
            reconciler = MarkerReconciler(commandRunner, scriptDeployer, logger),
            cleaner = FakeCleaner(),
            stateStore = stateStore,
            permissionChecker = FakePermissionChecker(),
            apkInstaller = UnsafeHelper.allocate(ApkInstaller::class.java),
            ioDispatcher = testDispatcher, logger = logger
        )

        val authCoordinator = AuthCoordinator(
            context = UnsafeHelper.allocate(android.app.Application::class.java),
            credentialStore = credentialStore,
            oAuth2Manager = UnsafeHelper.allocate(JagexOAuth2Manager::class.java),
            orchestrator = orchestrator,
            scope = TestScope(testDispatcher),
            onScreenChange = { screenFlow.value = it },
            onPerformLaunch = {},
            logger = logger, ioDispatcher = testDispatcher
        )

        val launchCoordinator = LaunchCoordinator(
            context = UnsafeHelper.allocate(android.app.Application::class.java),
            commandRunner = commandRunner,
            packageChecker = packageChecker,
            scriptDeployer = scriptDeployer,
            credentialStore = credentialStore,
            oAuth2Manager = UnsafeHelper.allocate(JagexOAuth2Manager::class.java),
            orchestrator = orchestrator,
            displayPreferences = UnsafeHelper.allocate(DisplayPreferences::class.java),
            launchPreferences = UnsafeHelper.allocate(com.runelitetablet.ui.LaunchPreferences::class.java),
            presentationBackend = com.runelitetablet.presentation.PresentationBackends.stable,
            captureController = FakeCaptureController(),
            authCoordinator = authCoordinator,
            scope = TestScope(testDispatcher),
            logger = logger, ioDispatcher = testDispatcher
        )

        viewModel = SetupViewModel(
            context = UnsafeHelper.allocate(android.app.Application::class.java),
            orchestrator = orchestrator,
            credentialStore = credentialStore,
            stateStore = stateStore,
            scriptDeployer = scriptDeployer,
            presentationBackend = com.runelitetablet.presentation.PresentationBackends.stable,
            authCoordinator = authCoordinator,
            launchCoordinator = launchCoordinator,
            _currentScreen = screenFlow,
            logger = logger, ioDispatcher = testDispatcher
        )
    }

    @Test
    fun `initial screen is Setup`() {
        assertEquals(AppScreen.Setup, viewModel.currentScreen.value)
    }

    @Test
    fun `canLaunch is initially false`() {
        assertFalse(viewModel.canLaunch.value)
    }

    @Test
    fun `navigateToSettings changes screen`() {
        viewModel.navigateToSettings()
        assertEquals(AppScreen.Settings, viewModel.currentScreen.value)
    }

    @Test
    fun `navigateToLogViewer changes screen`() {
        viewModel.navigateToLogViewer()
        assertEquals(AppScreen.LogViewer, viewModel.currentScreen.value)
    }

    @Test
    fun `navigateBackToLaunch changes screen`() {
        viewModel.navigateToSettings()
        viewModel.navigateBackToLaunch()
        assertEquals(AppScreen.Launch, viewModel.currentScreen.value)
    }

    @Test
    fun `getDisplayName returns null when no credentials`() {
        assertNull(viewModel.getDisplayName())
    }

    @Test
    fun `getDisplayName returns stored name`() {
        credentialStore.storeGameSession("sid", "cid", "TestPlayer")
        assertEquals("TestPlayer", viewModel.getDisplayName())
    }

    @Test
    fun `resetSetup returns to setup screen`() = runTest {
        viewModel.navigateToSettings()
        viewModel.resetSetup()
        assertEquals(AppScreen.Setup, viewModel.currentScreen.value)
    }

    @Test
    fun `startSetup is idempotent`() {
        viewModel.startSetup()
        viewModel.startSetup() // second call should be no-op
        // No assertion — just verifying no crash
    }
}
