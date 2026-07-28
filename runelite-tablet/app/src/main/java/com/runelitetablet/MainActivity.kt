package com.runelitetablet

import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.ActivityResultLauncher
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import com.runelitetablet.logging.AppLog
import com.runelitetablet.di.SetupViewModelFactory
import com.runelitetablet.setup.SetupViewModel
import com.runelitetablet.ui.screens.SetupScreen
import com.runelitetablet.ui.theme.RuneLiteTabletTheme

class MainActivity : ComponentActivity() {
    private val viewModel: SetupViewModel by viewModels {
        SetupViewModelFactory(this)
    }

    /**
     * ActivityResultLauncher for the GeckoView auth flow.
     * Must be registered during Activity creation (before onStart).
     * Result is forwarded to ViewModel.handleAuthResult().
     */
    private val authLauncher: ActivityResultLauncher<Intent> = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        AppLog.step("auth", "MainActivity: auth result received — resultCode=${result.resultCode}")
        viewModel.handleAuthResult(result)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()

        val savedState = if (savedInstanceState == null) "null (fresh start)" else "present (restored)"
        AppLog.lifecycle(
            "MainActivity.onCreate: savedInstanceState=$savedState " +
                "PID=${android.os.Process.myPid()} thread=${Thread.currentThread().name}"
        )
        AppLog.perf("onCreate: ${com.runelitetablet.logging.PerfSnapshots.perfSnapshot(applicationContext)}")

        setContent {
            RuneLiteTabletTheme {
                SetupScreen(viewModel = viewModel)
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        val uri = intent.data
        AppLog.lifecycle("MainActivity.onNewIntent: scheme=${uri?.scheme} host=${uri?.host}")
        // Keep jagex: intent filter as safety net — in production, GeckoView intercepts this
        if (uri?.scheme == "jagex") {
            AppLog.step("auth", "Captured jagex: URI via intent filter (safety net)")
        }
        setIntent(intent)
    }

    override fun onResume() {
        super.onResume()
        AppLog.lifecycle("MainActivity.onResume: bindActions + recheckPermissions + checkSession triggered")
        AppLog.perf("onResume: ${com.runelitetablet.logging.PerfSnapshots.perfSnapshot(applicationContext)}")
        viewModel.bindActions(this, authLauncher)
        viewModel.recheckPermissions()
        viewModel.checkSession()
    }

    override fun onPause() {
        super.onPause()
        AppLog.lifecycle("MainActivity.onPause: unbindActions called")
        AppLog.perf("onPause: ${com.runelitetablet.logging.PerfSnapshots.perfSnapshot(applicationContext)}")
        viewModel.unbindActions()
    }

}
