package com.weelay.app.ui.theme

import androidx.compose.ui.graphics.Color

// IRC nick colors for distinguishing users
val NickColors = listOf(
    Color(0xFFE57373), // Red
    Color(0xFF81C784), // Green
    Color(0xFF64B5F6), // Blue
    Color(0xFFFFB74D), // Orange
    Color(0xFFBA68C8), // Purple
    Color(0xFF4DD0E1), // Cyan
    Color(0xFFFFF176), // Yellow
    Color(0xFFA1887F), // Brown
    Color(0xFF90A4AE), // Grey
    Color(0xFFF06292), // Pink
    Color(0xFFAED581), // Light Green
    Color(0xFF7986CB), // Indigo
    Color(0xFFFF8A65), // Deep Orange
    Color(0xFF4DB6AC), // Teal
    Color(0xFFDCE775), // Lime
)

fun nickColor(nick: String): Color {
    val hash = nick.fold(0) { acc, c -> acc * 31 + c.code }
    return NickColors[Math.abs(hash) % NickColors.size]
}
