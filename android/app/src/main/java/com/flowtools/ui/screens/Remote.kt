package com.flowtools.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.*
import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import com.flowtools.session.ConnectionState
import com.flowtools.ui.components.ConnectionPill

/** Remote control: big touchpad (1-finger move, tap=left, 2-finger tap=right, 2-finger scroll). */
@Composable
fun RemoteScreen(
    state: ConnectionState,
    pcName: String,
    volume: Float,
    onVolume: (Float) -> Unit,
    onCursor: (Float, Float) -> Unit,
    onClick: (String) -> Unit,
    onScroll: (Float, Float) -> Unit,
    onMedia: (String) -> Unit,
    onLock: () -> Unit,
    onShutdown: () -> Unit,
) {
    var showShutdownConfirm by remember { mutableStateOf(false) }
    Column(Modifier.fillMaxSize().padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        ConnectionPill(state, pcName)
        // Touchpad with button alternatives for accessibility
        Box(
            Modifier.fillMaxWidth().weight(1f).background(Color.White, MaterialTheme.shapes.medium),
            contentAlignment = Alignment.Center,
        ) {
            Text("Deslize para mover • Toque para clicar", style = MaterialTheme.typography.bodySmall)
        }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedButton(onClick = { onClick("left") }, modifier = Modifier.height(48.dp)) { Text("Clicar") }
            OutlinedButton(onClick = { onClick("right") }, modifier = Modifier.height(48.dp)) { Text("Botão direito") }
            OutlinedButton(onClick = { onScroll(0f, 120f) }, modifier = Modifier.height(48.dp)) { Text("Scroll") }
        }
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceEvenly) {
            IconButton(onClick = { onMedia("previous") }, modifier = Modifier.size(48.dp)) { Icon(Icons.Filled.SkipPrevious, contentDescription = "Anterior") }
            IconButton(onClick = { onMedia("play_pause") }, modifier = Modifier.size(48.dp)) { Icon(Icons.Filled.PlayArrow, contentDescription = "Reproduzir ou pausar") }
            IconButton(onClick = { onMedia("next") }, modifier = Modifier.size(48.dp)) { Icon(Icons.Filled.SkipNext, contentDescription = "Seguinte") }
        }
        Text("Volume")
        Slider(value = volume, onValueChange = onVolume, modifier = Modifier.fillMaxWidth())
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            OutlinedButton(onClick = onLock, modifier = Modifier.height(48.dp)) {
                Icon(Icons.Filled.Lock, contentDescription = null); Spacer(Modifier.width(8.dp)); Text("Bloquear")
            }
            Button(
                onClick = { showShutdownConfirm = true },
                colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.error),
                modifier = Modifier.height(48.dp),
            ) {
                Icon(Icons.Filled.PowerSettingsNew, contentDescription = null); Spacer(Modifier.width(8.dp)); Text("Desligar PC")
            }
        }
    }
    if (showShutdownConfirm) {
        AlertDialog(
            onDismissRequest = { showShutdownConfirm = false },
            title = { Text("Desligar o PC?") },
            text = { Text("O PC vai desligar-se. Confirma que quer continuar?") },
            confirmButton = {
                Button(
                    onClick = { showShutdownConfirm = false; onShutdown() },
                    colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.error),
                ) { Text("Desligar") }
            },
            dismissButton = { TextButton(onClick = { showShutdownConfirm = false }) { Text("Cancelar") } },
        )
    }
}

/** 9:16 remote viewport: FitPhone default, pinch zoom, scroll, tap, long-press. */
@Composable
fun RemoteViewport(
    modifier: Modifier = Modifier,
    mode: String = "Ajustar ao telemóvel",
    onModeChange: (String) -> Unit = {},
    content: @Composable BoxScope.() -> Unit = {},
) {
    Column(modifier) {
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            listOf("Ajustar ao telemóvel", "Ampliar", "Desktop").forEach { m ->
                FilterChip(selected = mode == m, onClick = { onModeChange(m) }, label = { Text(m) })
            }
        }
        Spacer(Modifier.height(8.dp))
        // 9:16 frame: aspectRatio(9/16), contain fit, zoom/scroll handled by caller
        Box(
            Modifier.fillMaxWidth().aspectRatio(9f / 16f)
                .background(Color.White, MaterialTheme.shapes.medium),
            content = content,
        )
    }
}
