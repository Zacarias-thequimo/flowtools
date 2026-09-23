package com.flowtools

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigation.compose.*
import com.flowtools.session.ConnectionManager
import com.flowtools.ui.navigation.Routes
import com.flowtools.ui.screens.*
import com.flowtools.ui.theme.FlowToolsTheme

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            FlowToolsTheme {
                val conn = remember { ConnectionManager() }
                val nav = rememberNavController()
                val backStack by nav.currentBackStackEntryAsState()
                val route = backStack?.destination?.route ?: Routes.HOME
                Scaffold(
                    bottomBar = {
                        NavigationBar {
                            NavigationBarItem(
                                selected = route == Routes.HOME,
                                onClick = { nav.navigate(Routes.HOME) { launchSingleTop = true } },
                                icon = { Icon(Icons.Filled.Home, contentDescription = "Início") },
                                label = { Text("Início") },
                            )
                            NavigationBarItem(
                                selected = route == Routes.TOOLS,
                                onClick = { nav.navigate(Routes.TOOLS) { launchSingleTop = true } },
                                icon = { Icon(Icons.Filled.GridView, contentDescription = "Ferramentas") },
                                label = { Text("Ferramentas") },
                            )
                            NavigationBarItem(
                                selected = route == Routes.SETTINGS,
                                onClick = { nav.navigate(Routes.SETTINGS) { launchSingleTop = true } },
                                icon = { Icon(Icons.Filled.Settings, contentDescription = "Definições") },
                                label = { Text("Definições") },
                            )
                        }
                    },
                ) { padding ->
                    val state by conn.state.collectAsState()
                    val pcName by conn.pcName.collectAsState()
                    NavHost(nav, startDestination = Routes.HOME, modifier = Modifier.padding(padding)) {
                        composable(Routes.HOME) {
                            HomeScreen(
                                state, pcName,
                                onOpenRemote = { nav.navigate(Routes.REMOTE) },
                                onOpenText = { nav.navigate(Routes.TEXT) },
                                onOpenFiles = { nav.navigate(Routes.FILES) },
                                onOpenAudio = { nav.navigate(Routes.REMOTE) },
                                onPair = { nav.navigate(Routes.PAIRING) },
                            )
                        }
                        composable(Routes.TOOLS) {
                            var q by remember { mutableStateOf("") }
                            ToolsScreen(onOpen = {
                                nav.navigate(
                                    when (it) {
                                        "browser" -> Routes.BROWSER
                                        "apps" -> Routes.APPS
                                        "text" -> Routes.TEXT
                                        "shortcuts" -> Routes.SHORTCUTS
                                        "files" -> Routes.FILES
                                        else -> Routes.TOOLS
                                    },
                                )
                            }, query = q, onQuery = { q = it })
                        }
                        composable(Routes.REMOTE) {
                            var vol by remember { mutableStateOf(0.5f) }
                            RemoteScreen(
                                state, pcName, vol, onVolume = { vol = it },
                                onCursor = { _, _ -> }, onClick = {}, onScroll = { _, _ -> },
                                onMedia = {}, onLock = {}, onShutdown = {},
                            )
                        }
                        composable(Routes.BROWSER) {
                            var url by remember { mutableStateOf("") }
                            var mode by remember { mutableStateOf("Ajustar ao telemóvel") }
                            BrowserScreen(url, onUrl = { url = it }, onNavigate = {}, viewportMode = mode, onViewportMode = { mode = it }, browserOpen = true, onOpenBrowserOnPc = {})
                        }
                        composable(Routes.APPS) {
                            var q by remember { mutableStateOf("") }
                            var mode by remember { mutableStateOf("Ajustar ao telemóvel") }
                            var collapsed by remember { mutableStateOf(false) }
                            AppsScreen(listOf("Browser", "VLC"), listOf("Browser", "VLC", "VS Code"), q, { q = it }, { _, _ -> }, mode, { mode = it }, collapsed, { collapsed = !collapsed })
                        }
                        composable(Routes.TEXT) {
                            var t by remember { mutableStateOf("") }
                            var target by remember { mutableStateOf("janela ativa") }
                            QuickTextScreen(t, { t = it }, target, { target = it }, {}, emptyList(), {}, {})
                        }
                        composable(Routes.SHORTCUTS) { ShortcutsScreen({}) }
                        composable(Routes.FILES) { FilesScreen(null, {}, {}, null) }
                        composable(Routes.PAIRING) {
                            PairingScreen("123456", "flowtools://pair?…", listOf("Cursor", "Teclado", "Ficheiros"), {})
                        }
                        composable(Routes.SETTINGS) {
                            var h by remember { mutableStateOf(true) }
                            SettingsScreen({}, h, { h = it })
                        }
                    }
                }
            }
        }
    }
}
