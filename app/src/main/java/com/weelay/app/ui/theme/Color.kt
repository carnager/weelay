package com.weelay.app.ui.theme

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.graphics.Color

/** The two looks the app can wear; see [Palette]. */
enum class AppStyle {
    /** WeeChat-flavoured: neutral dark ground, amber accent, monospace buffer list. */
    RELAY,
    /** Modern chat app without bubbles: slate ground, periwinkle accent, tiled buffer list. */
    THREADS
}

/** One complete set of the app's colours. */
data class ColorSet(
    val background: Color,
    val surface: Color,
    val surfaceRaised: Color,
    val divider: Color,
    val onSurface: Color,
    val muted: Color,
    val accent: Color,
    val onAccent: Color,
    val link: Color,
    val danger: Color,
    /** Background of the user's own messages in the bubble layout. */
    val ownBubble: Color,
    /** Background of a message that mentioned the user (bubbles). */
    val mentionBubble: Color,
    /** Row tint for a mention in the line and grouped layouts. */
    val mentionTint: Color,
    val previewCard: Color,
    val ownPreviewCard: Color,
    /** Search matches and the outline on a message jumped to from the highlights sheet. */
    val searchMatch: Color,
    /** Connected-state dot. */
    val ok: Color,
    val bannerProgress: Color,
    val bannerError: Color
)

private val RelayColors = ColorSet(
    background = Color(0xFF111317),
    surface = Color(0xFF1A1E24),
    surfaceRaised = Color(0xFF22262E),
    divider = Color(0xFF262A31),
    onSurface = Color(0xFFE6E8EB),
    muted = Color(0xFF8B929C),
    accent = Color(0xFFF2B544),
    onAccent = Color(0xFF111317),
    link = Color(0xFF7FB8F0),
    danger = Color(0xFFE57373),
    ownBubble = Color(0xFF263040),
    mentionBubble = Color(0xFF3A3020),
    mentionTint = Color(0x1AF2B544),
    previewCard = Color(0xFF14171B),
    ownPreviewCard = Color(0xFF1E2733),
    searchMatch = Color(0xFFFFD54F),
    ok = Color(0xFF7FD1B9),
    bannerProgress = Color(0xFF6B4F12),
    bannerError = Color(0xFF8C2F2F)
)

private val ThreadsColors = ColorSet(
    background = Color(0xFF15171C),
    surface = Color(0xFF1E2128),
    surfaceRaised = Color(0xFF2A2E37),
    divider = Color(0xFF2A2E37),
    onSurface = Color(0xFFE4E6EB),
    muted = Color(0xFF9097A3),
    accent = Color(0xFF8EA2FF),
    onAccent = Color(0xFF15171C),
    link = Color(0xFFA9B7FF),
    danger = Color(0xFFE57373),
    ownBubble = Color(0xFF2B3150),
    mentionBubble = Color(0xFF2B2F45),
    mentionTint = Color(0x1A8EA2FF),
    previewCard = Color(0xFF181A20),
    ownPreviewCard = Color(0xFF232841),
    searchMatch = Color(0xFFFFD54F),
    ok = Color(0xFF7FD1B9),
    bannerProgress = Color(0xFF3A3F66),
    bannerError = Color(0xFF8C2F2F)
)

/**
 * The app's colours, in one place. Each property reads the active [ColorSet] through Compose
 * state, so switching [style] recolours every screen at once without threading a theme object
 * through the code. Nick colours (below) stay the main source of colour in chat, as on IRC.
 */
object Palette {
    var style by mutableStateOf(AppStyle.RELAY)
    private val c: ColorSet get() = colorsFor(style)

    /** A style's colours regardless of which is active, for previews of the other one. */
    fun colorsFor(style: AppStyle): ColorSet =
        if (style == AppStyle.THREADS) ThreadsColors else RelayColors

    val background get() = c.background
    val surface get() = c.surface
    val surfaceRaised get() = c.surfaceRaised
    val divider get() = c.divider
    val onSurface get() = c.onSurface
    val muted get() = c.muted
    val accent get() = c.accent
    val onAccent get() = c.onAccent
    val link get() = c.link
    val danger get() = c.danger
    val ownBubble get() = c.ownBubble
    val mentionBubble get() = c.mentionBubble
    val mentionTint get() = c.mentionTint
    val previewCard get() = c.previewCard
    val ownPreviewCard get() = c.ownPreviewCard
    val searchMatch get() = c.searchMatch
    val ok get() = c.ok
    val bannerProgress get() = c.bannerProgress
    val bannerError get() = c.bannerError
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
