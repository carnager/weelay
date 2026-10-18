package com.weelay.app.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.weelay.app.ui.theme.Palette
import com.weelay.app.viewmodel.DisplayMessage

private val TileSize = 34.dp
private val TileGap = 10.dp
private val SidePadding = 12.dp

/**
 * One message in the grouped layout: the first of a run from the same person carries a header
 * with a nick tile, the nick and the time; the rest sit under it, aligned with the text. No
 * bubbles, so it reads like a modern chat app while staying dense enough for a channel.
 */
@Composable
fun GroupedMessageRow(
    message: DisplayMessage,
    nick: String,
    nickColor: Color,
    isFirstInGroup: Boolean,
    searchQuery: String? = null,
    isHighlighted: Boolean = false,
    showLinkPreview: Boolean = true,
    onNickClick: ((String) -> Unit)? = null,
    onLongPress: (() -> Unit)? = null
) {
    val mention = message.isMention && !message.isOwnMessage
    // An action is a sentence about its nick ("* nick waves"), so it never gets a header.
    val text = rememberMessageText(message, nick, nickColor, searchQuery, leadingStar = message.isAction)
    val bodyStyle = MaterialTheme.typography.bodyMedium.copy(
        fontSize = 16.sp,
        lineHeight = 22.sp,
        color = Palette.onSurface
    )

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(top = if (isFirstInGroup) 10.dp else 0.dp)
            .then(if (mention) Modifier.background(Palette.mentionTint) else Modifier)
            .then(if (isHighlighted) Modifier.border(1.dp, Palette.searchMatch) else Modifier)
            .padding(horizontal = SidePadding, vertical = 2.dp)
    ) {
        if (isFirstInGroup && !message.isAction) {
            Box(
                modifier = Modifier
                    .size(TileSize)
                    .background(nickColor.copy(alpha = 0.18f), RoundedCornerShape(9.dp))
                    .then(if (onNickClick != null) Modifier.clickable { onNickClick(nick) } else Modifier),
                contentAlignment = Alignment.Center
            ) {
                Text(
                    text = nick.firstOrNull { it.isLetterOrDigit() }?.uppercase() ?: "?",
                    style = MaterialTheme.typography.titleMedium.copy(fontSize = 16.sp, fontWeight = FontWeight.Bold),
                    color = nickColor
                )
            }
        } else {
            Spacer(modifier = Modifier.width(TileSize))
        }
        Spacer(modifier = Modifier.width(TileGap))
        Column(modifier = Modifier.weight(1f)) {
            if (isFirstInGroup && !message.isAction) {
                Row(
                    verticalAlignment = Alignment.Bottom,
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Text(
                        text = nick,
                        style = MaterialTheme.typography.titleSmall.copy(fontSize = 15.sp, fontWeight = FontWeight.SemiBold),
                        color = if (mention) Palette.accent else nickColor,
                        modifier = if (onNickClick != null) Modifier.clickable { onNickClick(nick) } else Modifier
                    )
                    Text(
                        text = message.time,
                        style = MaterialTheme.typography.labelSmall.copy(fontSize = 12.sp),
                        color = Palette.muted,
                        modifier = Modifier.padding(bottom = 1.dp)
                    )
                }
            }
            MessageText(text = text, style = bodyStyle, onLongPress = onLongPress)
            if (message.isAction) {
                Text(
                    text = message.time,
                    style = MaterialTheme.typography.labelSmall.copy(fontSize = 12.sp),
                    color = Palette.muted
                )
            }
            val previewUrl = remember(message.text) { firstUrl(message.text) }
            if (showLinkPreview) previewUrl?.let { url ->
                LinkPreviewCard(url = url, isOwnMessage = message.isOwnMessage)
            }
        }
    }
}
