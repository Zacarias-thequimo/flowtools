package com.flowtools

import android.net.Uri
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigation.compose.*
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import com.flowtools.files.FilesViewModel
import com.flowtools.pairing.PairingFlow
import com.flowtools.pairing.PairingViewModel
import com.flowtools.pairing.capabilityWireName
import com.flowtools.pairing.wireToCapability
import com.flowtools.secure.SessionStore
import com.flowtools.session.*
import com.flowtools.ui.navigation.Routes
import com.flowtools.ui.screens.*
import com.flowtools.ui.theme.FlowToolsTheme

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            FlowToolsTheme {
                val activity = LocalContext.current as ComponentActivity
                val conn = remember { ConnectionManager() }
                val store = remember { SessionStore(applicationContext).also { it.loadToken() } }
                // Activity-scoped: one socket shared by remote/browser/text/apps.
                val sharedVm: RemoteViewModel = viewModel(viewModelStoreOwner = activity)
                val nav = rememberNavController()
                val ioScope = remember { CoroutineScope(Dispatchers.IO) }

                // Resume persisted session once (token rotation keeps it fresh).
                val savedToken by store.token.collectAsState()
                val savedServer by store.serverUrl.collectAsState(initial = null)
                val savedPcName by store.pcName.collectAsState(initial = null)
                var attached by remember { mutableStateOf(false) }
                LaunchedEffect(savedToken, savedServer) {
                    val tok = savedToken
                    val srv = savedServer
                    if (!attached && tok != null && srv != null) {
                        attached = true
                        sharedVm.attach(conn, srv, tok)
                    }
                }

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
                                state, if (state == ConnectionState.Unpaired) savedPcName ?: pcName else pcName,
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
                            val vol by sharedVm.volume.collectAsState()
                            RemoteScreen(
                                state, pcName, vol, onVolume = sharedVm::setVolume,
                                onCursor = sharedVm::cursor,
                                onClick = { sharedVm.click() },
                                onScroll = sharedVm::scroll,
                                onMedia = {
                                    when (it) {
                                        "previous" -> sharedVm.media(MediaAction.Previous)
                                        "next" -> sharedVm.media(MediaAction.Next)
                                        else -> sharedVm.media(MediaAction.PlayPause)
                                    }
                                },
                                onLock = sharedVm::lock,
                                onShutdown = { sharedVm.shutdown(true) },
                            )
                        }
                        composable(Routes.BROWSER) {
                            var url by remember { mutableStateOf("") }
                            var mode by remember { mutableStateOf("Ajustar ao telemóvel") }
                            val frame by sharedVm.frame.collectAsState()
                            BrowserScreen(
                                url, onUrl = { url = it },
                                onNavigate = {
                                    when (it) {
                                        "back" -> sharedVm.browserAction(BrowserAction.Back)
                                        "forward" -> sharedVm.browserAction(BrowserAction.Forward)
                                        "reload" -> sharedVm.browserAction(BrowserAction.Reload)
                                        else -> if (url.isNotBlank()) sharedVm.browserNavigate(url)
                                    }
                                },
                                viewportMode = mode,
                                onViewportMode = {
                                    mode = it
                                    sharedVm.setViewport(
                                        when (it) {
                                            "Ampliar" -> ViewportMode.Zoom
                                            "Desktop" -> ViewportMode.Desktop
                                            else -> ViewportMode.FitPhone
                                        },
                                    )
                                },
                                browserOpen = true,
                                onOpenBrowserOnPc = { sharedVm.browserAction(BrowserAction.OpenBrowser) },
                                frame = frame,
                            )
                        }
                        composable(Routes.APPS) {
                            var q by remember { mutableStateOf("") }
                            var mode by remember { mutableStateOf("Ajustar ao telemóvel") }
                            var collapsed by remember { mutableStateOf(false) }
                            val frame by sharedVm.frame.collectAsState()
                            AppsScreen(
                                listOf("Browser", "VLC"), listOf("Browser", "VLC", "VS Code"),
                                q, { q = it },
                                { app, action ->
                                    val id = when (app) {
                                        "Browser" -> "browser"
                                        "VLC" -> "vlc"
                                        else -> "vscode"
                                    }
                                    sharedVm.appAction(id, when (action) {
                                        "launch" -> AppAction.Launch
                                        "close" -> AppAction.Close
                                        else -> AppAction.Focus
                                    })
                                },
                                mode,
                                {
                                    mode = it
                                    sharedVm.setViewport(
                                        when (it) {
                                            "Ampliar" -> ViewportMode.Zoom
                                            "Desktop" -> ViewportMode.Desktop
                                            else -> ViewportMode.FitPhone
                                        },
                                    )
                                },
                                collapsed, { collapsed = !collapsed },
                            )
                        }
                        composable(Routes.TEXT) {
                            var t by remember { mutableStateOf("") }
                            var target by remember { mutableStateOf("janela ativa") }
                            val retain by store.retainText.collectAsState(initial = false)
                            var history by remember { mutableStateOf(listOf<String>()) }
                            QuickTextScreen(
                                t, { t = it }, target, { target = it },
                                onSend = {
                                    sharedVm.sendText(
                                        when (target) {
                                            "browser" -> TextTarget.Browser
                                            "clipboard" -> TextTarget.Clipboard
                                            else -> TextTarget.ActiveWindow
                                        },
                                        t,
                                    )
                                    if (retain && t.isNotBlank()) history = (listOf(t) + history).take(10)
                                    t = ""
                                },
                                history = if (retain) history else emptyList(),
                                onReuse = { t = it },
                                onDelete = { h -> history = history - h },
                            )
                        }
                        composable(Routes.SHORTCUTS) { ShortcutsScreen(onRun = sharedVm::shortcut) }
                        composable(Routes.FILES) {
                            val filesVm: FilesViewModel = viewModel()
                            val ui by filesVm.ui.collectAsState()
                            val ctx = LocalContext.current
                            val picker = rememberLauncherForActivityResult(ActivityResultContracts.GetContent()) { uri: Uri? ->
                                val tok = savedToken
                                val srv = savedServer
                                if (uri != null && tok != null && srv != null) {
                                    val name = uri.lastPathSegment?.substringAfterLast('/') ?: "ficheiro"
                                    val bytes = ctx.contentResolver.openInputStream(uri)?.readBytes()
                                    if (bytes != null) filesVm.upload(srv, tok, name, bytes)
                                }
                            }
                            FilesScreen(
                                progress = ui.progress,
                                onSend = { picker.launch("*/*") },
                                onCancel = filesVm::cancel,
                                error = ui.error,
                                destination = ui.destination,
                            )
                        }
                        composable(Routes.PAIRING) {
                            val pairVm: PairingViewModel = viewModel()
                            PairingFlow(
                                pairVm,
                                onDone = { nav.navigate(Routes.HOME) { popUpTo(Routes.HOME) } },
                                onClaim = {
                                    pairVm.claim(store) { server, token ->
                                        val granted = pairVm.approved.value
                                            .mapNotNull { wireToCapability(capabilityWireName(it)) }
                                            .toSet()
                                        conn.setPaired("PC de trabalho", granted, token)
                                        attached = true
                                        sharedVm.attach(conn, server, token)
                                    }
                                },
                            )
                        }
                        composable(Routes.SETTINGS) {
                            val haptics by store.haptics.collectAsState(initial = true)
                            SettingsScreen(
                                onRevoke = {
                                    sharedVm.detach()
                                    conn.revoke()
                                    attached = false
                                    // Clear persisted session off the UI thread.
                                    ioScope.launch { store.clearSession() }
                                },
                                haptics = haptics,
                                onHaptics = { ioScope.launch { store.setHaptics(it) } },
                            )
                        }
                    }
                }
            }
        }
    }
}
