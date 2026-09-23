package com.flowtools.remoteview

import android.graphics.Bitmap
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.rememberTransformableState
import androidx.compose.foundation.gestures.transformable
import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Remove
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.unit.dp
import com.flowtools.session.ViewportMode

/**
 * 9:16 remote viewport. FitPhone (default) fits the whole frame;
 * Zoom enables pinch + pan; zoom buttons are the accessible alternative.
 */
@Composable
fun FrameView(
    bitmap: Bitmap?,
    mode: ViewportMode,
    modifier: Modifier = Modifier,
) {
    var scale by remember { mutableFloatStateOf(1f) }
    var offset by remember { mutableStateOf(Offset.Zero) }
    val transform = rememberTransformableState { zoomChange, panChange, _ ->
        if (mode == ViewportMode.Zoom) {
            scale = (scale * zoomChange).coerceIn(1f, 5f)
            offset += panChange
        }
    }
    LaunchedEffect(mode) {
        if (mode != ViewportMode.Zoom) {
            scale = 1f
            offset = Offset.Zero
        }
    }
    Column(modifier) {
        Box(
            Modifier
                .fillMaxWidth()
                .aspectRatio(9f / 16f)
                .background(MaterialTheme.colorScheme.surface, MaterialTheme.shapes.medium)
                .transformable(transform),
            contentAlignment = Alignment.Center,
        ) {
            if (bitmap != null) {
                androidx.compose.foundation.Image(
                    bitmap = bitmap.asImageBitmap(),
                    contentDescription = "Vista remota do PC",
                    contentScale = ContentScale.Fit,
                    modifier = Modifier.fillMaxSize().graphicsLayer(
                        scaleX = scale,
                        scaleY = scale,
                        translationX = offset.x,
                        translationY = offset.y,
                    ),
                )
            } else {
                Text(
                    "Vista remota 9:16 — Ajustar ao telemóvel",
                    style = MaterialTheme.typography.bodySmall,
                )
            }
        }
        if (mode == ViewportMode.Zoom) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                IconButton(onClick = { scale = (scale - 0.5f).coerceAtLeast(1f) }, modifier = Modifier.size(48.dp)) {
                    Icon(Icons.Filled.Remove, contentDescription = "Reduzir zoom")
                }
                IconButton(onClick = { scale = (scale + 0.5f).coerceAtMost(5f) }, modifier = Modifier.size(48.dp)) {
                    Icon(Icons.Filled.Add, contentDescription = "Aumentar zoom")
                }
                Text("Zoom ${"%.1f".format(scale)}×", style = MaterialTheme.typography.bodySmall)
            }
        }
    }
}
