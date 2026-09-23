package com.flowtools.ui.screens

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.grid.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.flowtools.session.ConnectionState
import com.flowtools.ui.components.*

@Composable
fun HomeScreen(
    state: ConnectionState,
    pcName: String,
    onOpenRemote: () -> Unit,
    onOpenText: () -> Unit,
    onOpenFiles: () -> Unit,
    onOpenAudio: () -> Unit,
    onPair: () -> Unit,
) {
    Column(
        Modifier.fillMaxSize().padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        Text("FlowTools", style = MaterialTheme.typography.headlineSmall)
        Text("O que quer fazer?", style = MaterialTheme.typography.headlineMedium)
        Text(
            "Controle o seu PC de forma simples enquanto vê os seus vídeos.",
            style = MaterialTheme.typography.bodyMedium,
        )
        ConnectionCard(state, pcName, onAction = { if (state == ConnectionState.Connected) onOpenRemote() else onPair() })
        // Quick action grid, 2 columns (one-tap to remote)
        LazyVerticalGrid(columns = GridCells.Fixed(2), verticalArrangement = Arrangement.spacedBy(12.dp), horizontalArrangement = Arrangement.spacedBy(12.dp), modifier = Modifier.weight(1f)) {
            item { QuickActionTile(Icons.Filled.Mouse, "Controlo remoto", "Rato, multimédia e mais.", onOpenRemote) }
            item { QuickActionTile(Icons.Filled.TextFields, "Texto rápido", "Envie texto para o seu PC.", onOpenText) }
            item { QuickActionTile(Icons.Filled.Folder, "Ficheiros", "Transfira e gestione ficheiros.", onOpenFiles) }
            item { QuickActionTile(Icons.Filled.VolumeUp, "Áudio", "Volume e reprodução.", onOpenAudio) }
        }
    }
}

@Composable
fun ToolsScreen(onOpen: (String) -> Unit, query: String = "", onQuery: (String) -> Unit = {}) {
    val tools = listOf(
        Triple("browser", "Browser remoto", "Navegue e controle páginas numa vista vertical."),
        Triple("apps", "Aplicações do PC", "Abra, alterne e controle apps instaladas."),
        Triple("text", "Enviar texto", "Escreva no telemóvel e cole no PC."),
        Triple("shortcuts", "Atalhos", "Execute combinações de teclado com um toque."),
        Triple("capture", "Captura de ecrã", "Tire e consulte capturas do PC."),
        Triple("files", "Transferir ficheiro", "Envie e receba ficheiros entre dispositivos."),
        Triple("notifications", "Notificações do PC", "Consulte notificações sem mudar de ecrã."),
    ).filter { query.isBlank() || it.second.contains(query, true) || it.third.contains(query, true) }
    Column(Modifier.fillMaxSize().padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Text("Ferramentas", style = MaterialTheme.typography.headlineMedium)
        OutlinedTextField(
            value = query, onValueChange = onQuery,
            placeholder = { Text("Pesquisar ferramentas") },
            modifier = Modifier.fillMaxWidth(),
        )
        tools.forEach { (id, name, desc) ->
            Card(onClick = { onOpen(id) }, modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp)) {
                Row(Modifier.padding(16.dp)) {
                    Icon(Icons.Filled.ChevronRight, contentDescription = "Abrir $name")
                    Spacer(Modifier.width(12.dp))
                    Column {
                        Text(name, style = MaterialTheme.typography.titleSmall)
                        Text(desc, style = MaterialTheme.typography.bodySmall)
                    }
                }
            }
        }
    }
}
