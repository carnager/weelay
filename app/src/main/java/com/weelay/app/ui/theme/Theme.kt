package com.weelay.app.ui.theme

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable

// Material components that aren't styled by hand (dialogs, text fields, menus, switches) take
// their colours from here, so it mirrors Palette rather than following Material You.
// Built inside the composable so it follows Palette.style.
@Composable
private fun weelayColorScheme() = darkColorScheme(
    primary = Palette.accent,
    onPrimary = Palette.onAccent,
    secondary = Palette.link,
    background = Palette.background,
    onBackground = Palette.onSurface,
    surface = Palette.background,
    onSurface = Palette.onSurface,
    surfaceVariant = Palette.surface,
    onSurfaceVariant = Palette.muted,
    surfaceContainer = Palette.surface,
    surfaceContainerHigh = Palette.surface,
    outline = Palette.muted,
    outlineVariant = Palette.divider,
    error = Palette.danger
)

@Composable
fun WeelayTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = weelayColorScheme(),
        typography = Typography,
        content = content
    )
}
