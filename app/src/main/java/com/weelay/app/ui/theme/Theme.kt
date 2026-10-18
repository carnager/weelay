package com.weelay.app.ui.theme

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

// The screens are built around one fixed dark palette, so the theme supplies the same colours.
// Following the system light theme or Material You left the stock-coloured parts (the connect
// screen, dialogs, text fields) clashing with everything that hard-codes this palette.
private val WeelayColorScheme = darkColorScheme(
    primary = Color(0xFF00A884),
    onPrimary = Color(0xFF0B141A),
    secondary = Color(0xFF53BDEB),
    background = Color(0xFF0B141A),
    onBackground = Color(0xFFE9EDEF),
    surface = Color(0xFF0B141A),
    onSurface = Color(0xFFE9EDEF),
    surfaceVariant = Color(0xFF1F2C34),
    onSurfaceVariant = Color(0xFF8696A0),
    surfaceContainerHigh = Color(0xFF1F2C34),
    outline = Color(0xFF8696A0),
    error = Color(0xFFE57373)
)

@Composable
fun WeelayTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = WeelayColorScheme,
        typography = Typography,
        content = content
    )
}
