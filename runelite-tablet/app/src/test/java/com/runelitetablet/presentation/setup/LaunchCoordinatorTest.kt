package com.runelitetablet.presentation.setup

import com.runelitetablet.auth.AuthResult
import com.runelitetablet.installer.ApkDownloader
import com.runelitetablet.installer.ApkInstaller
import com.runelitetablet.setup.AppScreen
import com.runelitetablet.setup.AuthCoordinator
import com.runelitetablet.setup.LaunchCoordinator
import com.runelitetablet.setup.LaunchState
import com.runelitetablet.setup.MarkerReconciler
import com.runelitetablet.setup.SetupOrchestrator
import com.runelitetablet.setup.SetupStepRunner
import com.runelitetablet.setup.SetupVerifier
import com.runelitetablet.setup.selectLaunchRoute
import com.runelitetablet.telemetry.RendererBackend
import com.runelitetablet.auth.JagexOAuth2Manager
import com.runelitetablet.domain.command.CommandResult
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
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.Assert.*
import org.junit.Before
import org.junit.Rule
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class LaunchCoordinatorTest {

    @get:Rule val mainDispatcherRule = MainDispatcherRule()

    private lateinit var commandRunner: FakeCommandRunner
    private lateinit var credentialStore: FakeCredentialStore
    private lateinit var packageChecker: FakePackageChecker
    private lateinit var scriptDeployer: FakeScriptDeployer
    private lateinit var coordinator: LaunchCoordinator
    private lateinit var orchestrator: SetupOrchestrator
    private lateinit var authCoordinator: AuthCoordinator
    private val logger = PrintLogger()

    @Before
    fun setup() {
        val testDispatcher = UnconfinedTestDispatcher()
        commandRunner = FakeCommandRunner()
        credentialStore = FakeCredentialStore()
        packageChecker = FakePackageChecker()
        scriptDeployer = FakeScriptDeployer()

        orchestrator = SetupOrchestrator(
            packageChecker = packageChecker,
            stepRunner = SetupStepRunner(commandRunner, packageChecker,
                UnsafeHelper.allocate(ApkDownloader::class.java),
                UnsafeHelper.allocate(ApkInstaller::class.java),
                scriptDeployer, FakeSetupStateStore(), logger),
            verifier = SetupVerifier(commandRunner, logger),
            reconciler = MarkerReconciler(commandRunner, scriptDeployer, logger),
            cleaner = FakeCleaner(),
            stateStore = FakeSetupStateStore(),
            permissionChecker = FakePermissionChecker(),
            apkInstaller = UnsafeHelper.allocate(ApkInstaller::class.java),
            ioDispatcher = testDispatcher, logger = logger
        )

        authCoordinator = AuthCoordinator(
            context = UnsafeHelper.allocate(android.app.Application::class.java),
            credentialStore = credentialStore,
            oAuth2Manager = UnsafeHelper.allocate(JagexOAuth2Manager::class.java),
            orchestrator = orchestrator,
            scope = TestScope(testDispatcher),
            onScreenChange = {},
            onPerformLaunch = {},
            logger = logger, ioDispatcher = testDispatcher
        )

        coordinator = LaunchCoordinator(
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
    }

    @Test
    fun `initial state is Idle`() {
        assertEquals(LaunchState.Idle, coordinator.launchState.value)
    }

    @Test
    fun `health check result is initially null`() {
        assertNull(coordinator.healthStatus.value)
    }

    @Test
    fun `showHealthDialog is initially null`() {
        assertNull(coordinator.showHealthDialog.value)
    }

    @Test
    fun `dismissHealthDialog clears dialog`() {
        coordinator.dismissHealthDialog()
        assertNull(coordinator.showHealthDialog.value)
    }

    @Test
    fun `refreshIfNeeded returns Valid when token not expired`() = runTest {
        credentialStore.storeTokens("access", "refresh", System.currentTimeMillis() / 1000 + 3600)
        val result = authCoordinator.refreshIfNeeded()
        assertTrue(result is AuthResult.Valid)
    }

    @Test
    fun `refreshIfNeeded returns NeedsLogin when no refresh token`() = runTest {
        // Access token expired, no refresh token stored
        credentialStore.storeTokens("access", null, System.currentTimeMillis() / 1000 - 100)
        val result = authCoordinator.refreshIfNeeded()
        assertTrue(result is AuthResult.NeedsLogin)
    }

    @Test
    fun `native route remains native when official repository is ready`() {
        val route = selectLaunchRoute(
            requestedUseNative = true,
            nativeRepositoryReady = true,
            requestedBackend = RendererBackend.NATIVE_X11_VIRGL
        )

        assertTrue(route.useNative)
        assertEquals(RendererBackend.NATIVE_X11_VIRGL, route.rendererBackend)
    }

    @Test
    fun `missing native repository selects labeled proot recovery route`() {
        val route = selectLaunchRoute(
            requestedUseNative = true,
            nativeRepositoryReady = false,
            requestedBackend = RendererBackend.NATIVE_X11_VIRGL
        )

        assertFalse(route.useNative)
        assertEquals(RendererBackend.PROOT_X11_VIRGL, route.rendererBackend)
    }

    @Test
    fun `legacy proot preference is not mislabeled as native telemetry`() {
        val route = selectLaunchRoute(
            requestedUseNative = false,
            nativeRepositoryReady = false,
            requestedBackend = RendererBackend.NATIVE_X11_VIRGL
        )

        assertFalse(route.useNative)
        assertEquals(RendererBackend.PROOT_X11_VIRGL, route.rendererBackend)
    }
}
