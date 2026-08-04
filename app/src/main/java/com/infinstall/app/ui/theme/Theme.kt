package com.infinstall.app.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp

val MinTouch = 48.dp
val ContentMaxWidth = 840.dp
val TabletBreakpoint = 600.dp

private val LightColors = lightColorScheme(
    primary = Color(0xFF1565C0),
    onPrimary = Color.White,
    primaryContainer = Color(0xFFD1E4FF),
    secondary = Color(0xFF00695C),
    secondaryContainer = Color(0xFFB2DFDB),
    background = Color(0xFFF7F9FC),
    surface = Color.White,
    error = Color(0xFFB3261E),
)

private val DarkColors = darkColorScheme(
    primary = Color(0xFF90CAF9),
    onPrimary = Color(0xFF003258),
    primaryContainer = Color(0xFF0D47A1),
    secondary = Color(0xFF80CBC4),
    secondaryContainer = Color(0xFF004D40),
    background = Color(0xFF101418),
    surface = Color(0xFF1A1F24),
    error = Color(0xFFF2B8B5),
)

@Composable
fun InfinstallTheme(content: @Composable () -> Unit) {
    val dark = isSystemInDarkTheme()
    MaterialTheme(
        colorScheme = if (dark) DarkColors else LightColors,
        typography = InfinstallTypography,
        content = content,
    )
}
