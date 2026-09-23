package com.flowtools.ui.theme

import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp

// FlowTools palette (approved visual direction)
val Canvas = Color(0xFFF8F7F3)
val SurfaceWhite = Color(0xFFFFFFFF)
val SurfaceTinted = Color(0xFFEEF5F1)
val BrandDark = Color(0xFF0E2D4A)
val TextPrimary = Color(0xFF17324D)
val TextSecondary = Color(0xFF66717B)
val Sage = Color(0xFF6B9B8A)
val SoftBlue = Color(0xFFBFD8F2)
val Danger = Color(0xFFB3261E)
val Divider = Color(0xFFE4E7E5)

private val LightColors = lightColorScheme(
    background = Canvas,
    surface = SurfaceWhite,
    primary = BrandDark,
    onPrimary = Color.White,
    secondary = Sage,
    onSecondary = Color.White,
    tertiary = SoftBlue,
    error = Danger,
    onBackground = TextPrimary,
    onSurface = TextPrimary,
)

private val CardShape = RoundedCornerShape(20.dp)
private val FieldShape = RoundedCornerShape(16.dp)

@Composable
fun FlowToolsTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = LightColors,
        shapes = Shapes(medium = CardShape, small = FieldShape),
        content = content,
    )
}
