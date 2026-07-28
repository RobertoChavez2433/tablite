package com.runelitetablet.di

import android.app.Activity
import android.content.Context
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import com.runelitetablet.RuneLiteTabletApp
import com.runelitetablet.logging.AppLog
import com.runelitetablet.setup.AppScreen
import com.runelitetablet.setup.AuthCoordinator
import com.runelitetablet.setup.LaunchCoordinator
import com.runelitetablet.setup.SetupViewModel
import kotlinx.coroutines.MainScope
import kotlinx.coroutines.flow.MutableStateFlow

class SetupViewModelFactory(activity: Activity) : ViewModelProvider.Factory {
    private val appContext: Context = activity.applicationContext
    private val container = (activity.application as RuneLiteTabletApp).container

    @Suppress("UNCHECKED_CAST")
    override fun <T : ViewModel> create(modelClass: Class<T>): T {
        AppLog.lifecycle("SetupViewModelFactory.create: building ViewModel with dependencies")
        val orchestrator = container.setup.orchestrator
        val credentialStore = container.auth.credentialStore
        val logger = container.core.logger
        val screenFlow = MutableStateFlow<AppScreen>(AppScreen.Setup)

        val launchPerformRef = object { var fn: (suspend () -> Unit) = {} }

        val authCoordinator = AuthCoordinator(
            context = appContext,
            credentialStore = credentialStore,
            oAuth2Manager = container.auth.oauthManager,
            orchestrator = orchestrator,
            scope = MainScope(),
            onScreenChange = { screenFlow.value = it },
            onPerformLaunch = { launchPerformRef.fn() },
            logger = logger
        )

        val launchCoordinator = LaunchCoordinator(
            context = appContext,
            commandRunner = container.termux.commandRunner,
            packageChecker = container.termux.packageChecker,
            scriptDeployer = container.setup.scriptManager,
            credentialStore = credentialStore,
            oAuth2Manager = container.auth.oauthManager,
            orchestrator = orchestrator,
            displayPreferences = container.displayPreferences,
            launchPreferences = container.launchPreferences,
            presentationBackend = container.presentationBackend,
            captureController = container.captureController,
            authCoordinator = authCoordinator,
            scope = MainScope(),
            logger = logger
        )

        launchPerformRef.fn = { launchCoordinator.performLaunch() }

        return SetupViewModel(
            context = appContext,
            orchestrator = orchestrator,
            credentialStore = credentialStore,
            stateStore = container.setup.stateStore,
            scriptDeployer = container.setup.scriptManager,
            presentationBackend = container.presentationBackend,
            authCoordinator = authCoordinator,
            launchCoordinator = launchCoordinator,
            _currentScreen = screenFlow,
            logger = logger
        ) as T
    }
}
