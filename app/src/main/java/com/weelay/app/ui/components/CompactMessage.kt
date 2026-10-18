package com.weelay.app.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.weelay.app.ui.theme.Palette
import com.weelay.app.viewmodel.DisplayMessage

/**
 * One message as an IRC line: time, the nick right-aligned in its colour, then the text, with
 * wrapped lines indented under the text column. The nick is shown once per run of messages from
 * the same person; a /me action shows "*" in the nick column and the acting nick inline.
 */
@Composable
fun CompactMessageRow(
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
    val mono = FontFamily.Monospace
    val text = rememberMessageText(message, nick, nickColor, searchQuery, leadingStar = false)
    val mention = message.isMention && !message.isOwnMessage

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(top = if (isFirstInGroup) 4.dp else 0.dp)
            .then(if (mention) Modifier.background(Palette.mentionTint) else Modifier)
            .then(
                if (isHighlighted) Modifier.border(1.dp, Palette.searchMatch) else Modifier
            )
            .padding(horizontal = 6.dp, vertical = 2.dp)
    ) {
        Text(
            text = message.time,
            style = MaterialTheme.typography.labelSmall.copy(fontSize = 12.sp, fontFamily = mono),
            color = Palette.muted,
            modifier = Modifier
                .width(42.dp)
                .padding(top = 4.dp)
        )
        val nickLabel = when {
            message.isAction -> "*"
            isFirstInGroup -> nick
            else -> ""
        }
        Text(
            text = nickLabel,
            style = MaterialTheme.typography.bodyMedium.copy(
                fontSize = 14.sp,
                lineHeight = 22.sp,
                fontFamily = mono,
                fontWeight = if (mention) FontWeight.Bold else FontWeight.Normal
            ),
            color = when {
                message.isAction -> Palette.muted
                mention -> Palette.accent
                else -> nickColor
            },
            textAlign = TextAlign.End,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier
                .width(88.dp)
                .then(
                    if (onNickClick != null && nickLabel.isNotEmpty() && !message.isAction) {
                        Modifier.clickable { onNickClick(nick) }
                    } else Modifier
                )
        )
        Column(
            modifier = Modifier
                .weight(1f)
                .padding(start = 8.dp)
        ) {
            MessageText(
                text = text,
                style = MaterialTheme.typography.bodyMedium.copy(
                    fontSize = 16.sp,
                    lineHeight = 22.sp,
                    color = Palette.onSurface
                ),
                onLongPress = onLongPress
            )
            val previewUrl = remember(message.text) { firstUrl(message.text) }
            if (showLinkPreview) previewUrl?.let { url ->
                LinkPreviewCard(url = url, isOwnMessage = message.isOwnMessage, compact = true)
            }
        }
    }
}
