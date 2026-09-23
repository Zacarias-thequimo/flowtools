package com.flowtools.ui.components

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import com.flowtools.session.ConnectionState
import com.flowtools.ui.theme.Sage

/** Connection pill: icon + text, never color-only. */
@Composable
fun ConnectionPill(state: ConnectionState, pcName: String, modifier: Modifier = Modifier) {
    val (icon, label) = when (state) {
        ConnectionState.Connected -> Icons.Filled.CheckCircle to "$pcName · Ligado"
        ConnectionState.Pairing -> Icons.Filled.Refresh to "A ligar ao PC…"
        ConnectionState.Reconnecting -> Icons.Filled.Refresh to "A reconectar…"
        ConnectionState.Offline -> Icons.Filled.Warning to "Offline — PC indisponível"
        ConnectionState.Unpaired -> Icons.Filled.Info to "Não emparelhado"
        ConnectionState.PermissionRequired -> Icons.Filled.Lock to "Permissão necessária"
    }
    Surface(
        modifier = modifier.semantics { contentDescription = label },
        shape = RoundedCornerShape(16.dp),
        color = if (state == ConnectionState.Connected) Sage.copy(alpha = 0.15f)
        else MaterialTheme.colorScheme.surfaceVariant,
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(icon, contentDescription = null, modifier = Modifier.size(20.dp))
            Spacer(Modifier.width(8.dp))
            Text(label, style = MaterialTheme.typography.labelLarge)
        }
    }
}

@Composable
fun ConnectionCard(
    state: ConnectionState,
    pcName: String,
    onAction: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Card(
        modifier = modifier.fillMaxWidth(),
        shape = RoundedCornerShape(20.dp),
    ) {
        Column(Modifier.padding(20.dp)) {
            Text(pcName, style = MaterialTheme.typography.titleMedium)
            Spacer(Modifier.height(8.dp))
            ConnectionPill(state, pcName)
            Spacer(Modifier.height(4.dp))
            Text(state.label, style = MaterialTheme.typography.bodyMedium)
            Spacer(Modifier.height(12.dp))
            Button(
                onClick = onAction,
                modifier = Modifier.heightIn(min = 48.dp),
                shape = RoundedCornerShape(16.dp),
            ) {
                Text(if (state == ConnectionState.Connected) "Usar ferramentas" else "Emparelhar PC")
            }
        }
    }
}

@Composable
fun QuickActionTile(
    icon: ImageVector,
    title: String,
    description: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Card(
        onClick = onClick,
        modifier = modifier
            .fillMaxWidth()
            .heightIn(min = 48.dp),
        shape = RoundedCornerShape(20.dp),
    ) {
        Row(Modifier.padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
            Icon(icon, contentDescription = null, modifier = Modifier.size(28.dp))
            Spacer(Modifier.width(12.dp))
            Column {
                Text(title, style = MaterialTheme.typography.titleSmall)
                Text(description, style = MaterialTheme.typography.bodySmall)
            }
        }
    }
}
