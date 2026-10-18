package com.weelay.app.ui.theme

import androidx.compose.ui.graphics.Color

/**
 * The app's colours, in one place. "Relay": a neutral dark ground with one amber accent, which
 * also marks mentions and the read marker. Nick colours (below) stay the main source of colour,
 * as on IRC.
 */
object Palette {
    val background = Color(0xFF111317)
    val surface = Color(0xFF1A1E24)
    val surfaceRaised = Color(0xFF22262E)
    val divider = Color(0xFF262A31)
    val onSurface = Color(0xFFE6E8EB)
    val muted = Color(0xFF8B929C)

    val accent = Color(0xFFF2B544)
    val onAccent = Color(0xFF111317)
    val link = Color(0xFF7FB8F0)
    val danger = Color(0xFFE57373)

    /** Background of the user's own messages in the bubble layout. */
    val ownBubble = Color(0xFF263040)
    /** Background of a message that mentioned the user. */
    val mentionBubble = Color(0xFF3A3020)
    /** Row tint for a mention in the compact layout. */
    val mentionTint = Color(0x1AF2B544)

    val previewCard = Color(0xFF14171B)
    val ownPreviewCard = Color(0xFF1E2733)

    /** Search matches and the outline on a message jumped to from the highlights sheet. */
    val searchMatch = Color(0xFFFFD54F)

    val bannerProgress = Color(0xFF6B4F12)
    val bannerError = Color(0xFF8C2F2F)
}

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
