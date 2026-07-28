package com.runelitetablet.ui.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Circle
import androidx.compose.material.icons.filled.Person
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.runelitetablet.setup.AppScreen
import com.runelitetablet.BuildConfig
import com.runelitetablet.session.SessionState
import com.runelitetablet.setup.HealthCheckResult
import com.runelitetablet.setup.LaunchState
import com.runelitetablet.setup.PermissionPhase
import com.runelitetablet.setup.SetupViewModel
import com.runelitetablet.setup.StepStatus
import com.runelitetablet.ui.components.StepItem

/**
 * Top-level screen router. Observes [SetupViewModel.currentScreen] and delegates
 * to the appropriate screen composable.
 */
@Composable
fun SetupScreen(viewModel: SetupViewModel) {
    val currentScreen by viewModel.currentScreen.collectAsState()

    val launchState by viewModel.launchState.collectAsState()
    val healthStatus by viewModel.healthStatus.collectAsState()
    val showHealthDialog by viewModel.showHealthDialog.collectAsState()
    val sessionState by viewModel.sessionState.collectAsState()
    val sessionStartTime by viewModel.sessionStartTime.collectAsState()

    when (val screen = currentScreen) {
        is AppScreen.Setup -> SetupWizardContent(viewModel)
        is AppScreen.Login -> LoginScreen(
            onLogin = {
                viewModel.recordUiAction("SetupScreen: Login button clicked")
                viewModel.startLogin()
            },
            onSkip = {
                viewModel.recordUiAction("SetupScreen: Skip login clicked")
                viewModel.skipLogin()
            }
        )
        is AppScreen.CharacterSelect -> CharacterSelectScreen(
            characters = screen.characters,
            onCharacterSelected = { character ->
                viewModel.recordUiAction("SetupScreen: character selected displayName=***")
                viewModel.onCharacterSelected(character)
            }
        )
        is AppScreen.Launch -> LaunchScreen(
            displayName = viewModel.getDisplayName(),
            healthStatus = healthStatus,
            launchState = launchState,
            sessionState = sessionState,
            sessionStartTime = sessionStartTime,
            onLaunch = {
                viewModel.recordUiAction("SetupScreen: Launch RuneLite button clicked")
                viewModel.launchRuneLite()
            },
            onSwitchToGame = {
                viewModel.recordUiAction("SetupScreen: Switch to Game button clicked")
                viewModel.switchToGame()
            },
            onStopSession = {
                viewModel.recordUiAction("SetupScreen: Stop Game button clicked")
                viewModel.stopSession()
            },
            onOpenDirectSurfaceProbe = {
                viewModel.recordUiAction("SetupScreen: Direct Surface Probe button clicked")
                viewModel.openDirectSurfaceProbe()
            },
            onSettings = {
                viewModel.recordUiAction("SetupScreen: Settings button clicked")
                viewModel.navigateToSettings()
            },
            onViewLogs = {
                viewModel.recordUiAction("SetupScreen: View Logs button clicked")
                viewModel.navigateToLogViewer()
            }
        )
        is AppScreen.AuthError -> AuthErrorScreen(
            message = screen.message,
            onRetry = {
                viewModel.recordUiAction("SetupScreen: AuthError retry clicked")
                viewModel.startLogin()
            },
            onSkip = {
                viewModel.recordUiAction("SetupScreen: AuthError skip clicked")
                viewModel.skipLogin()
            }
        )
        is AppScreen.Settings -> SettingsScreen(
            displayName = viewModel.getDisplayName(),
            appVersion = BuildConfig.VERSION_NAME,
            onSignOut = {
                viewModel.recordUiAction("SetupScreen: Sign out clicked")
                viewModel.signOut()
            },
            onResetSetup = {
                viewModel.recordUiAction("SetupScreen: Reset setup clicked")
                viewModel.resetSetup()
            },
            onViewLogs = {
                viewModel.recordUiAction("SetupScreen: View logs from settings clicked")
                viewModel.navigateToLogViewer()
            },
            onOpenDirectSurfaceProbe = {
                viewModel.recordUiAction("SetupScreen: Open direct surface probe clicked")
                viewModel.openDirectSurfaceProbe()
            },
            onBack = {
                viewModel.recordUiAction("SetupScreen: Settings back clicked")
                viewModel.navigateBackToLaunch()
            }
        )
        is AppScreen.LogViewer -> LogViewerScreen(
            onBack = {
                viewModel.recordUiAction("SetupScreen: LogViewer back clicked")
                viewModel.navigateBackToLaunch()
            }
        )
    }

    // Health dialog
    if (showHealthDialog != null) {
        HealthCheckDialog(
            failures = showHealthDialog ?: emptyList(),
            onRunSetup = {
                viewModel.recordUiAction("SetupScreen: Health dialog -> Run Setup")
                viewModel.runSetupForHealth()
            },
            onLaunchAnyway = {
                viewModel.recordUiAction("SetupScreen: Health dialog -> Launch Anyway")
                viewModel.launchAnyway()
            },
            onDismiss = {
                viewModel.dismissHealthDialog()
            }
        )
    }
}

// =============================================================================
// Setup wizard content (moved from the old SetupScreen body)
// =============================================================================

@Composable
private fun SetupWizardContent(viewModel: SetupViewModel) {
    val steps by viewModel.steps.collectAsState()
    val canLaunch by viewModel.canLaunch.collectAsState()
    val currentOutput by viewModel.currentOutput.collectAsState()
    val isPermissionStepActive by viewModel.isPermissionStepActive.collectAsState()
    val permissionPhase by viewModel.permissionPhase.collectAsState()

    val hasFailed = steps.any { it.status is StepStatus.Failed }

    LaunchedEffect(Unit) {
        viewModel.startSetup()
    }

    Scaffold { paddingValues ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(paddingValues)
                .padding(16.dp)
        ) {
            Text(
                text = "RuneLite for Tablet",
                style = MaterialTheme.typography.headlineMedium,
                color = MaterialTheme.colorScheme.primary,
                modifier = Modifier.padding(bottom = 24.dp)
            )

            steps.forEachIndexed { index, stepState ->
                key(index) {
                    StepItem(
                        label = stepState.step.label,
                        status = stepState.status
                    )
                }
            }

            // Phased permission UI — shown inline when the permissions step is active
            if (isPermissionStepActive) {
                Spacer(modifier = Modifier.height(16.dp))
                PermissionPhaseContent(
                    phase = permissionPhase,
                    viewModel = viewModel
                )
            }

            currentOutput?.let { output ->
                Spacer(modifier = Modifier.height(16.dp))
                OutputCard(
                    text = output,
                    isError = hasFailed
                )
            }

            Spacer(modifier = Modifier.weight(1f))

            Row(modifier = Modifier.fillMaxWidth()) {
                if (hasFailed && !isPermissionStepActive) {
                    OutlinedButton(onClick = {
                        viewModel.recordUiAction("SetupWizardContent: Retry button clicked hasFailed=$hasFailed")
                        viewModel.retry()
                    }) {
                        Text("Retry")
                    }
                }
                Spacer(modifier = Modifier.weight(1f))
                Button(
                    onClick = {
                        viewModel.recordUiAction("SetupWizardContent: Launch RuneLite button clicked canLaunch=$canLaunch")
                        viewModel.launchRuneLite()
                    },
                    enabled = canLaunch
                ) {
                    Text("Launch RuneLite")
                }
            }
        }
    }
}

// =============================================================================
// Login screen
// =============================================================================

@Composable
private fun LoginScreen(
    onLogin: () -> Unit,
    onSkip: () -> Unit
) {
    Scaffold { paddingValues ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(paddingValues)
                .padding(32.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center
        ) {
            Text(
                text = "RuneLite for Tablet",
                style = MaterialTheme.typography.headlineMedium,
                color = MaterialTheme.colorScheme.primary,
                modifier = Modifier.padding(bottom = 8.dp)
            )

            Text(
                text = "Sign in to play with your Jagex account",
                style = MaterialTheme.typography.bodyLarge,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center,
                modifier = Modifier.padding(bottom = 48.dp)
            )

            Button(
                onClick = onLogin,
                modifier = Modifier
                    .fillMaxWidth()
                    .height(56.dp)
            ) {
                Text(
                    text = "Sign in with Jagex",
                    style = MaterialTheme.typography.labelLarge
                )
            }

            Spacer(modifier = Modifier.height(16.dp))

            TextButton(onClick = onSkip) {
                Text(
                    text = "Skip (play without account)",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
    }
}

// =============================================================================
// Launch screen
// =============================================================================

@Composable
private fun LaunchScreen(
    displayName: String?,
    healthStatus: HealthCheckResult?,
    launchState: LaunchState,
    sessionState: SessionState,
    sessionStartTime: Long,
    onLaunch: () -> Unit,
    onSwitchToGame: () -> Unit,
    onStopSession: () -> Unit,
    onOpenDirectSurfaceProbe: () -> Unit,
    onSettings: () -> Unit,
    onViewLogs: () -> Unit
) {
    Scaffold { paddingValues ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(paddingValues)
                .padding(24.dp)
        ) {
            // Top bar with title and settings icon
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = "RuneLite for Tablet",
                    style = MaterialTheme.typography.headlineMedium,
                    color = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.weight(1f)
                )
                IconButton(onClick = onSettings) {
                    Icon(
                        Icons.Default.Settings,
                        contentDescription = "Settings",
                        tint = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }

            Spacer(modifier = Modifier.height(16.dp))

            // Status card
            Card(
                colors = CardDefaults.cardColors(
                    containerColor = MaterialTheme.colorScheme.surfaceVariant
                ),
                modifier = Modifier.fillMaxWidth()
            ) {
                Column(modifier = Modifier.padding(16.dp)) {
                    // Account row
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Icon(
                            Icons.Default.Person,
                            contentDescription = null,
                            modifier = Modifier.size(20.dp),
                            tint = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        Spacer(modifier = Modifier.width(8.dp))
                        Text(
                            text = displayName ?: "Not signed in",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurface,
                            modifier = Modifier.weight(1f)
                        )
                        if (displayName != null) {
                            Text(
                                text = "Session active",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.primary
                            )
                        }
                    }

                    Spacer(modifier = Modifier.height(8.dp))

                    // Environment health row
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        val (healthColor, healthText) = when (healthStatus) {
                            is HealthCheckResult.Healthy -> Color(0xFF4CAF50) to "Environment OK"
                            is HealthCheckResult.Degraded -> Color(0xFFF44336) to "Issues detected"
                            is HealthCheckResult.Inconclusive -> Color(0xFFFFC107) to "Not checked"
                            null -> Color.Gray to "Not checked"
                        }
                        Icon(
                            Icons.Default.Circle,
                            contentDescription = null,
                            modifier = Modifier.size(12.dp),
                            tint = healthColor
                        )
                        Spacer(modifier = Modifier.width(12.dp))
                        Text(
                            text = healthText,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
            }

            // Session status card (shown when session is running)
            if (sessionState is SessionState.Running) {
                Spacer(modifier = Modifier.height(16.dp))
                Card(
                    colors = CardDefaults.cardColors(
                        containerColor = Color(0xFF1B5E20).copy(alpha = 0.15f)
                    ),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier.padding(16.dp)
                    ) {
                        Icon(
                            Icons.Default.Circle,
                            contentDescription = null,
                            modifier = Modifier.size(12.dp),
                            tint = Color(0xFF4CAF50)
                        )
                        Spacer(modifier = Modifier.width(12.dp))
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                text = "RuneLite is running",
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onSurface
                            )
                            if (sessionStartTime > 0L) {
                                val elapsedMin = ((System.currentTimeMillis() - sessionStartTime) / 60_000).toInt()
                                val timeText = if (elapsedMin < 60) "$elapsedMin min"
                                    else "${elapsedMin / 60}h ${elapsedMin % 60}m"
                                Text(
                                    text = "Playing for $timeText",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                        }
                    }
                }
            }

            if (sessionState is SessionState.Stopped) {
                Spacer(modifier = Modifier.height(8.dp))
                Text(
                    text = "Last session ended",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }

            if (sessionState is SessionState.Error) {
                Spacer(modifier = Modifier.height(8.dp))
                Text(
                    text = (sessionState as SessionState.Error).message,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.error
                )
            }

            Spacer(modifier = Modifier.weight(1f))

            // Launch button, session controls, or progress
            when {
                // Session running: show Switch + Stop buttons
                sessionState is SessionState.Running -> {
                    Row(modifier = Modifier.fillMaxWidth()) {
                        Button(
                            onClick = onSwitchToGame,
                            modifier = Modifier.weight(1f).height(56.dp)
                        ) {
                            Text(
                                text = "Switch to Game",
                                style = MaterialTheme.typography.labelLarge
                            )
                        }
                        Spacer(modifier = Modifier.width(8.dp))
                        OutlinedButton(
                            onClick = onStopSession,
                            modifier = Modifier.weight(1f).height(56.dp)
                        ) {
                            Text(
                                text = "Stop Game",
                                style = MaterialTheme.typography.labelLarge
                            )
                        }
                    }
                }
                // Session starting: show spinner
                sessionState is SessionState.Starting -> {
                    LaunchProgress("Starting RuneLite...")
                }
                // Launch state is active (checking updates, etc.)
                launchState !is LaunchState.Idle && launchState !is LaunchState.Failed -> {
                    when (val state = launchState) {
                        is LaunchState.CheckingUpdate -> LaunchProgress("Checking for updates...")
                        is LaunchState.Updating -> LaunchProgress(
                            "Updating RuneLite (${state.fromVersion} -> ${state.toVersion})..."
                        )
                        is LaunchState.CheckingHealth -> LaunchProgress("Verifying environment...")
                        is LaunchState.RefreshingTokens -> LaunchProgress("Refreshing session...")
                        is LaunchState.ValidatingSession -> LaunchProgress("Checking session...")
                        is LaunchState.Launching -> LaunchProgress("Launching RuneLite...")
                        else -> {} // Idle and Failed handled elsewhere
                    }
                }
                // Launch failed
                launchState is LaunchState.Failed -> {
                    val failedState = launchState as LaunchState.Failed
                    Text(
                        text = failedState.message,
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.error,
                        modifier = Modifier.padding(bottom = 16.dp)
                    )
                    Row(modifier = Modifier.fillMaxWidth()) {
                        OutlinedButton(
                            onClick = onViewLogs,
                            modifier = Modifier.weight(1f)
                        ) {
                            Text("View Logs")
                        }
                        Spacer(modifier = Modifier.width(8.dp))
                        Button(
                            onClick = onLaunch,
                            modifier = Modifier.weight(1f)
                        ) {
                            Text("Try Again")
                        }
                    }
                }
                // Default: show Launch button
                else -> {
                    Column(modifier = Modifier.fillMaxWidth()) {
                        Button(
                            onClick = onLaunch,
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(56.dp)
                        ) {
                            Text(
                                text = "Launch RuneLite",
                                style = MaterialTheme.typography.labelLarge
                            )
                        }

                        Spacer(modifier = Modifier.height(12.dp))

                        OutlinedButton(
                            onClick = onOpenDirectSurfaceProbe,
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Text("Open Direct Surface Probe")
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun LaunchProgress(message: String) {
    Column(
        modifier = Modifier.fillMaxWidth(),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        CircularProgressIndicator(
            modifier = Modifier.padding(bottom = 16.dp)
        )
        Text(
            text = message,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
}

@Composable
private fun HealthCheckDialog(
    failures: List<String>,
    onRunSetup: () -> Unit,
    onLaunchAnyway: () -> Unit,
    onDismiss: () -> Unit
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = {
            Text(
                text = "Environment Issue Detected",
                style = MaterialTheme.typography.titleLarge
            )
        },
        text = {
            Column {
                Text(
                    text = "The following components need repair:",
                    style = MaterialTheme.typography.bodyMedium,
                    modifier = Modifier.padding(bottom = 8.dp)
                )
                failures.forEach { failure ->
                    Text(
                        text = "- $failure",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.error
                    )
                }
            }
        },
        confirmButton = {
            Button(onClick = onRunSetup) {
                Text("Run Setup Again")
            }
        },
        dismissButton = {
            TextButton(onClick = onLaunchAnyway) {
                Text("Launch Anyway")
            }
        }
    )
}

// =============================================================================
// Auth error screen
// =============================================================================

@Composable
private fun AuthErrorScreen(
    message: String,
    onRetry: () -> Unit,
    onSkip: () -> Unit
) {
    Scaffold { paddingValues ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(paddingValues)
                .padding(32.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center
        ) {
            Text(
                text = "Sign-in Failed",
                style = MaterialTheme.typography.headlineSmall,
                color = MaterialTheme.colorScheme.error,
                modifier = Modifier.padding(bottom = 16.dp)
            )

            Card(
                colors = CardDefaults.cardColors(
                    containerColor = MaterialTheme.colorScheme.errorContainer.copy(alpha = 0.3f)
                ),
                shape = RoundedCornerShape(8.dp),
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(bottom = 32.dp)
            ) {
                Text(
                    text = message,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.error,
                    textAlign = TextAlign.Center,
                    modifier = Modifier.padding(16.dp)
                )
            }

            Button(
                onClick = onRetry,
                modifier = Modifier
                    .fillMaxWidth()
                    .height(56.dp)
            ) {
                Text(
                    text = "Try Again",
                    style = MaterialTheme.typography.labelLarge
                )
            }

            Spacer(modifier = Modifier.height(16.dp))

            TextButton(onClick = onSkip) {
                Text(
                    text = "Skip (play without account)",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
    }
}

// =============================================================================
// Shared composables
// =============================================================================

@Composable
private fun OutputCard(text: String, isError: Boolean) {
    Card(
        colors = CardDefaults.cardColors(
            containerColor = if (isError) {
                MaterialTheme.colorScheme.errorContainer.copy(alpha = 0.3f)
            } else {
                MaterialTheme.colorScheme.surfaceVariant
            }
        ),
        shape = RoundedCornerShape(8.dp),
        modifier = Modifier.fillMaxWidth()
    ) {
        Text(
            text = text,
            style = MaterialTheme.typography.bodySmall,
            color = if (isError) {
                MaterialTheme.colorScheme.error
            } else {
                MaterialTheme.colorScheme.onSurfaceVariant
            },
            modifier = Modifier
                .padding(12.dp)
                .verticalScroll(rememberScrollState())
        )
    }
}

// =============================================================================
// Phased permission content — replaces the old bottom sheet
// =============================================================================

/**
 * Shows phase-specific content for the multi-phase permission setup.
 * Displayed inline below the step list when the permissions step is active.
 */
@Composable
private fun PermissionPhaseContent(
    phase: PermissionPhase,
    viewModel: SetupViewModel
) {
    val commandCopied by viewModel.commandCopied.collectAsState()

    Card(
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceVariant
        ),
        shape = RoundedCornerShape(12.dp),
        modifier = Modifier.fillMaxWidth()
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            when (phase) {
                is PermissionPhase.TermuxConfig -> {
                    Text(
                        text = "Step 1 of 3: Enable Termux Commands",
                        style = MaterialTheme.typography.titleMedium,
                        modifier = Modifier.padding(bottom = 8.dp)
                    )
                    Text(
                        text = "We need to configure Termux to accept commands from our app " +
                            "and add helpful terminal shortcut keys.",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(bottom = 12.dp)
                    )

                    // Numbered instruction list
                    val instructions = listOf(
                        "Tap \"Copy & Open Termux\" below",
                        "Long-press anywhere in Termux",
                        "Tap \"Paste\"",
                        "Tap Enter on your keyboard",
                        "Come back here -- we'll check automatically!"
                    )
                    instructions.forEachIndexed { index, instruction ->
                        Text(
                            text = "${index + 1}. $instruction",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurface,
                            modifier = Modifier.padding(start = 8.dp, bottom = 4.dp)
                        )
                    }

                    Spacer(modifier = Modifier.height(12.dp))

                    Button(
                        onClick = {
                            viewModel.recordUiAction("PermissionPhaseContent: Copy & Open Termux clicked")
                            viewModel.copyConfigAndOpenTermux()
                        },
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Text("Copy & Open Termux")
                    }

                    Spacer(modifier = Modifier.height(8.dp))

                    OutlinedButton(
                        onClick = {
                            viewModel.recordUiAction("PermissionPhaseContent: Done - Check Now clicked")
                            viewModel.checkPermissionPhase()
                        },
                        modifier = Modifier.fillMaxWidth(),
                        enabled = commandCopied
                    ) {
                        Text("Done -- Check Now")
                    }
                }

                is PermissionPhase.RuntimePermission -> {
                    Text(
                        text = "Step 2 of 3: Grant Command Permission",
                        style = MaterialTheme.typography.titleMedium,
                        modifier = Modifier.padding(bottom = 8.dp)
                    )
                    Text(
                        text = "Android needs your permission for this app to send commands to Termux. " +
                            "Tap the button below and select \"Allow\" in the dialog.",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(bottom = 12.dp)
                    )

                    Row(modifier = Modifier.fillMaxWidth()) {
                        Button(
                            onClick = {
                                viewModel.recordUiAction("PermissionPhaseContent: Grant Permission clicked")
                                viewModel.checkPermissionPhase()
                            },
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Text("Grant Permission")
                        }
                    }
                }

                is PermissionPhase.BatteryOptimization -> {
                    Text(
                        text = "Step 3 of 3: Allow Background Activity",
                        style = MaterialTheme.typography.titleMedium,
                        modifier = Modifier.padding(bottom = 8.dp)
                    )
                    Text(
                        text = "Both Termux and Termux:X11 must be allowed to run without Samsung " +
                            "putting them to sleep. On this tablet, the button may open App info " +
                            "first. If it does, tap Battery, then choose Unrestricted, then come back here.",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(bottom = 12.dp)
                    )

                    Button(
                        onClick = {
                            viewModel.recordUiAction("PermissionPhaseContent: Allow Background clicked")
                            viewModel.requestBatteryOptimization()
                        },
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Text("Open Battery Settings")
                    }

                    Spacer(modifier = Modifier.height(8.dp))

                    TextButton(
                        onClick = {
                            viewModel.recordUiAction("PermissionPhaseContent: Check battery optimization clicked")
                            viewModel.checkPermissionPhase()
                        },
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Text("I've changed it - check now")
                    }
                }

                is PermissionPhase.Complete -> {
                    // This state is transient — once Complete, isPermissionStepActive becomes false
                    // and this composable is not shown. Include for exhaustive when.
                    Text(
                        text = "All permissions configured!",
                        style = MaterialTheme.typography.titleMedium,
                        color = Color(0xFF4CAF50)
                    )
                }
            }
        }
    }
}
