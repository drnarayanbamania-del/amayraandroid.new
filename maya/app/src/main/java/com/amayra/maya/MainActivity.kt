package com.amayra.maya

import android.Manifest
import android.media.projection.MediaProjectionManager
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Icon
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Bookmarks
import androidx.compose.material.icons.filled.Chat
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Troubleshoot
import androidx.compose.material.icons.filled.Tune
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import com.amayra.maya.core.MayaCoreService
import com.amayra.maya.core.AssistantState
import com.amayra.maya.core.MayaLog
import com.amayra.maya.core.StateBus
import com.amayra.maya.ui.chat.ChatScreen
import com.amayra.maya.ui.diagnostics.DiagnosticsScreen
import com.amayra.maya.ui.settings.SettingsScreen
import com.amayra.maya.ui.permissions.PermissionsScreen
import com.amayra.maya.ui.scanner.BarcodeScannerScreen
import com.amayra.maya.ui.theme.MayaTheme
import com.amayra.maya.voice.wakeword.WakeWordService

class MainActivity : ComponentActivity() {

    private val notifPermission = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted ->
        MayaLog.i("PERM", "POST_NOTIFICATIONS granted=$granted")
        if (granted) MayaCoreService.start(this)
    }

    private val micPermission = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted ->
        MayaLog.i("PERM", "RECORD_AUDIO granted=$granted")
        maybeStartWakeWord()
    }

    /** MediaProjection consent dialog + token forwarding to the capture service. */
    private val screenShareLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        val data = result.data
        if (result.resultCode == RESULT_OK && data != null) {
            // The token is single-use and short-lived — forward it immediately.
            com.amayra.maya.screen.ScreenCaptureService.start(this, result.resultCode, data)
        } else {
            MayaLog.i("PERM", "Screen share consent denied")
            com.amayra.maya.screen.ScreenShareBus.publishActive(false)
        }
    }

    internal fun launchScreenShareConsent(mpm: MediaProjectionManager) {
        screenShareLauncher.launch(mpm.createScreenCaptureIntent())
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        askPostNotifications()

        setContent {
            MayaTheme {
                MayaApp()
            }
        }
    }

    override fun onResume() {
        super.onResume()
        maybeStartWakeWord()
    }

    private fun maybeStartWakeWord() {
        val on = MayaApplication.get(this).settings.prefsCache?.wakeWordEnabled ?: false
        if (on) {
            val mic = androidx.core.content.ContextCompat.checkSelfPermission(
                this, Manifest.permission.RECORD_AUDIO
            ) == android.content.pm.PackageManager.PERMISSION_GRANTED
            if (mic) WakeWordService.start(this)
            else micPermission.launch(Manifest.permission.RECORD_AUDIO)
        }
    }

    fun requestMic() {
        micPermission.launch(Manifest.permission.RECORD_AUDIO)
    }

    private fun askPostNotifications() {
        if (android.os.Build.VERSION.SDK_INT >= 33) {
            notifPermission.launch(Manifest.permission.POST_NOTIFICATIONS)
        } else {
            MayaCoreService.start(this)
        }
    }
}

private enum class Screen(val route: String, val label: String) {
    CHAT("chat", "Chat"),
    TOOLS("tools", "Tools"),
    MEMORIES("memories", "Memories"),
    SETTINGS("settings", "Settings"),
    DIAG("diag", "Diagnostics"),
    SCANNER("scanner", "Scanner"),
    PERMISSIONS("permissions", "Permissions"),
    SETUP("setup", "Setup")
}

@Composable
fun MayaApp() {
    val nav = rememberNavController()
    val backStack by nav.currentBackStackEntryAsState()
    val current = backStack?.destination?.route ?: Screen.CHAT.route
    val app = MayaApplication.get(androidx.compose.ui.platform.LocalContext.current)
    val activity = androidx.compose.ui.platform.LocalContext.current as? MainActivity

    // First-run gate: fresh installs land on setup until it's completed.
    val prefs by app.settings.prefs.collectAsState(initial = null)
    LaunchedEffect(prefs?.setupComplete) {
        if (prefs != null && prefs?.setupComplete == false && current != Screen.SETUP.route) {
            nav.navigate(Screen.SETUP.route) {
                popUpTo(0) { inclusive = true }
                launchSingleTop = true
            }
        }
    }

    // Screen-share consent: StateBus request -> MediaProjection dialog.
    LaunchedEffect(Unit) {
        StateBus.screenShareRequest.collect {
            if (activity != null) {
                val mpm = activity.getSystemService(MediaProjectionManager::class.java) ?: return@collect
                activity.launchScreenShareConsent(mpm)
            }
        }
    }

    // Barcode tool -> navigate to the scanner screen.
    LaunchedEffect(Unit) {
        StateBus.scanRequest.collect {
            nav.navigate(Screen.SCANNER.route) { launchSingleTop = true }
        }
    }

    // Chat error banner "Fix" action -> AI settings screen.
    LaunchedEffect(Unit) {
        StateBus.settingsRequest.collect {
            nav.navigate(Screen.SETTINGS.route) { launchSingleTop = true }
        }
    }

    // Engine-status dialog "Diagnostics" action -> diagnostics screen.
    LaunchedEffect(Unit) {
        StateBus.diagRequest.collect {
            nav.navigate(Screen.DIAG.route) { launchSingleTop = true }
        }
    }

    Scaffold(
        containerColor = com.amayra.maya.ui.theme.Bg,
        bottomBar = {
            if (current != Screen.SCANNER.route && current != Screen.SETUP.route) {
                NavigationBar(containerColor = com.amayra.maya.ui.theme.Surface) {
                    Screen.entries.filter { it != Screen.SCANNER && it != Screen.PERMISSIONS }.forEach { s ->
                        NavigationBarItem(
                            selected = current == s.route,
                            onClick = {
                                nav.navigate(s.route) { popUpTo(nav.graph.startDestinationId) { saveState = true }; launchSingleTop = true; restoreState = true }
                            },
                            icon = {
                                Icon(
                                    imageVector = when (s) {
                                        Screen.CHAT -> Icons.Filled.Chat
                                        Screen.TOOLS -> Icons.Filled.Tune
                                        Screen.MEMORIES -> Icons.Filled.Bookmarks
                                        Screen.SETTINGS -> Icons.Filled.Settings
                                        Screen.DIAG -> Icons.Filled.Troubleshoot
                                        Screen.SCANNER -> Icons.Filled.Tune
                                        Screen.PERMISSIONS -> Icons.Filled.Tune
                                        Screen.SETUP -> Icons.Filled.Tune
                                    },
                                    contentDescription = s.label
                                )
                            },
                            label = { Text(s.label) }
                        )
                    }
                }
            }
        }
    ) { padding ->
        Box(Modifier.fillMaxSize().padding(padding)) {
            NavHost(nav, startDestination = Screen.CHAT.route) {
                composable(Screen.CHAT.route) { com.amayra.maya.ui.home.MyraHomeScreen(app) }
                composable(Screen.TOOLS.route) { ToolsScreen(app) }
                composable(Screen.MEMORIES.route) { com.amayra.maya.ui.memories.RecollectionsScreen(app) }
                composable(Screen.SETTINGS.route) { SettingsScreen(app, onOpenPermissions = { nav.navigate(Screen.PERMISSIONS.route) { launchSingleTop = true } }) }
                composable(Screen.PERMISSIONS.route) { PermissionsScreen() }
                composable(Screen.SETUP.route) {
                    com.amayra.maya.ui.setup.SetupGateScreen(app) { nav.navigate(Screen.CHAT.route) { popUpTo(0) { inclusive = true } } }
                }
                composable(Screen.DIAG.route) { DiagnosticsScreen(app) }
                composable(Screen.SCANNER.route) {
                    BarcodeScannerScreen(onClose = { nav.popBackStack() })
                }
            }
        }
    }
}
