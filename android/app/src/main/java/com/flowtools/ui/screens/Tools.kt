package com.flowtools.ui.screens

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp

@Composable
fun BrowserScreen(
    url: String,
    onUrl: (String) -> Unit,
    onNavigate: (String) -> Unit,
    viewportMode: String,
    onViewportMode: (String) -> Unit,
    browserOpen: Boolean,
    onOpenBrowserOnPc: () -> Unit,
    frame: android.graphics.Bitmap? = null,
) {
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Text("Browser remoto", style = MaterialTheme.typography.headlineSmall)
        // Address bar: back / forward / reload / field (collapsible in full impl)
        Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
            IconButton(onClick = { onNavigate("back") }, modifier = Modifier.size(48.dp)) { Icon(Icons.Filled.ArrowBack, contentDescription = "Voltar") }
            IconButton(onClick = { onNavigate("forward") }, modifier = Modifier.size(48.dp)) { Icon(Icons.Filled.ArrowForward, contentDescription = "Avançar") }
            IconButton(onClick = { onNavigate("reload") }, modifier = Modifier.size(48.dp)) { Icon(Icons.Filled.Refresh, contentDescription = "Recarregar") }
            OutlinedTextField(
                value = url, onValueChange = onUrl,
                placeholder = { Text("Pesquisar ou inserir endereço") },
                modifier = Modifier.weight(1f),
            )
        }
        if (!browserOpen) {
            Button(onClick = onOpenBrowserOnPc, modifier = Modifier.height(48.dp)) { Text("Abrir browser no PC") }
        } else {
            val mode = when (viewportMode) {
                "Ampliar" -> com.flowtools.session.ViewportMode.Zoom
                "Desktop" -> com.flowtools.session.ViewportMode.Desktop
                else -> com.flowtools.session.ViewportMode.FitPhone
            }
            com.flowtools.remoteview.FrameView(bitmap = frame, mode = mode)
        }
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceEvenly) {
            IconButton(onClick = {}, modifier = Modifier.size(48.dp)) { Icon(Icons.Filled.Tab, contentDescription = "Separadores") }
            IconButton(onClick = {}, modifier = Modifier.size(48.dp)) { Icon(Icons.Filled.Home, contentDescription = "Início") }
            IconButton(onClick = {}, modifier = Modifier.size(48.dp)) { Icon(Icons.Filled.Menu, contentDescription = "Menu do browser") }
        }
    }
}

@Composable
fun AppsScreen(
    running: List<String>,
    allowed: List<String>,
    query: String,
    onQuery: (String) -> Unit,
    onAction: (String, String) -> Unit,
    viewportMode: String,
    onViewportMode: (String) -> Unit,
    toolbarCollapsed: Boolean,
    onToggleToolbar: () -> Unit,
) {
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Text("Aplicações do PC", style = MaterialTheme.typography.headlineSmall)
        OutlinedTextField(value = query, onValueChange = onQuery, placeholder = { Text("Pesquisar aplicações") }, modifier = Modifier.fillMaxWidth())
        Text("Abertas agora", style = MaterialTheme.typography.titleSmall)
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            running.forEach { app ->
                FilterChip(selected = false, onClick = { onAction(app, "focus") }, label = { Text(app) })
            }
        }
        Text("As suas aplicações (autorizadas)", style = MaterialTheme.typography.titleSmall)
        allowed.filter { query.isBlank() || it.contains(query, true) }.forEach { app ->
            Card(onClick = { onAction(app, "focus") }, modifier = Modifier.fillMaxWidth()) {
                Row(Modifier.padding(16.dp), horizontalArrangement = Arrangement.SpaceBetween) {
                    Text(app)
                    Row {
                        TextButton(onClick = { onAction(app, "launch") }) { Text("Abrir") }
                        TextButton(onClick = { onAction(app, "close") }) { Text("Fechar") }
                    }
                }
            }
        }
        RemoteViewport(mode = viewportMode, onModeChange = onViewportMode) {
            Text("App em foco — 9:16", style = MaterialTheme.typography.bodySmall)
        }
        if (!toolbarCollapsed) {
            Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                listOf("Touchpad" to Icons.Filled.Mouse, "Teclado" to Icons.Filled.Keyboard, "Voltar" to Icons.Filled.ArrowBack, "Início" to Icons.Filled.Home, "Recentes" to Icons.Filled.Apps).forEach { (label, icon) ->
                    IconButton(onClick = {}, modifier = Modifier.size(48.dp)) { Icon(icon, contentDescription = label) }
                }
                TextButton(onClick = onToggleToolbar) { Text("Recolher") }
            }
        } else {
            TextButton(onClick = onToggleToolbar) { Text("Mostrar barra") }
        }
    }
}

@Composable
fun QuickTextScreen(text: String, onText: (String) -> Unit, target: String, onTarget: (String) -> Unit, onSend: () -> Unit, history: List<String>, onReuse: (String) -> Unit, onDelete: (String) -> Unit) {
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Text("Texto rápido", style = MaterialTheme.typography.headlineSmall)
        OutlinedTextField(value = text, onValueChange = onText, placeholder = { Text("Escreva ou cole aqui") }, minLines = 4, modifier = Modifier.fillMaxWidth())
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            listOf("janela ativa", "browser", "clipboard").forEach { t ->
                FilterChip(selected = target == t, onClick = { onTarget(t) }, label = { Text(t) })
            }
        }
        Button(onClick = onSend, modifier = Modifier.height(48.dp)) { Text("Enviar para o PC") }
        Text("Recentes", style = MaterialTheme.typography.titleSmall)
        history.forEach { h ->
            Card(Modifier.fillMaxWidth()) {
                Row(Modifier.padding(12.dp), horizontalArrangement = Arrangement.SpaceBetween) {
                    Text(h.take(40), modifier = Modifier.weight(1f))
                    TextButton(onClick = { onReuse(h) }) { Text("Reutilizar") }
                    TextButton(onClick = { onDelete(h) }) { Text("Apagar") }
                }
            }
        }
    }
}

@Composable
fun ShortcutsScreen(onRun: (String) -> Unit) {
    val cats = mapOf(
        "Multimédia" to listOf("play_pause", "next", "prev", "mute"),
        "Janelas" to listOf("alt_tab", "win_d", "alt_f4"),
        "Browser" to listOf("ctrl_t", "ctrl_w", "ctrl_r"),
        "Edição" to listOf("ctrl_c", "ctrl_v", "ctrl_z"),
        "Sistema" to listOf("lock", "print"),
    )
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Text("Atalhos", style = MaterialTheme.typography.headlineSmall)
        cats.forEach { (cat, items) ->
            Text(cat, style = MaterialTheme.typography.titleSmall)
            items.forEach { id ->
                Card(onClick = { onRun(id) }, modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp)) {
                    Row(Modifier.padding(16.dp)) { Text(id); Spacer(Modifier.weight(1f)); Icon(Icons.Filled.PlayArrow, contentDescription = "Executar $id") }
                }
            }
        }
    }
}

@Composable
fun FilesScreen(progress: Float?, onSend: () -> Unit, onCancel: () -> Unit, error: String?, destination: String? = null) {
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Text("Ficheiros", style = MaterialTheme.typography.headlineSmall)
        Button(onClick = onSend, modifier = Modifier.height(48.dp)) { Text("Enviar ficheiro para o PC") }
        if (progress != null) {
            LinearProgressIndicator(progress = { progress }, modifier = Modifier.fillMaxWidth())
            Text("A enviar… ${(progress * 100).toInt()}%")
            OutlinedButton(onClick = onCancel, modifier = Modifier.height(48.dp)) { Text("Cancelar transferência") }
        }
        destination?.let { Text(it, style = MaterialTheme.typography.bodyMedium) }
        error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
    }
}

@Composable
fun PairingScreen(code: String, qr: String, permissions: List<String>, onApprove: () -> Unit) {
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Text("Emparelhar PC", style = MaterialTheme.typography.headlineSmall)
        Text("QR: $qr", style = MaterialTheme.typography.bodyMedium)
        Text("Código temporário: $code", style = MaterialTheme.typography.headlineMedium)
        Text("Permissões solicitadas (autorize só as que quiser):")
        permissions.forEach { Text("• $it") }
        Button(onClick = onApprove, modifier = Modifier.height(48.dp)) { Text("Autorizar selecionadas") }
    }
}

@Composable
fun SettingsScreen(onRevoke: () -> Unit, haptics: Boolean, onHaptics: (Boolean) -> Unit) {
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Text("Definições", style = MaterialTheme.typography.headlineSmall)
        Text("Ligação", style = MaterialTheme.typography.titleSmall)
        Text("Vista remota: 9:16, Ajustar ao telemóvel (predefinição)")
        Text("Segurança", style = MaterialTheme.typography.titleSmall)
        Row { Text("Resposta háptica"); Spacer(Modifier.weight(1f)); Switch(checked = haptics, onCheckedChange = onHaptics) }
        Button(onClick = onRevoke, colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.error), modifier = Modifier.height(48.dp)) { Text("Revogar dispositivo") }
    }
}
