package com.weelay.app.ui.components

import com.weelay.app.ui.theme.Palette
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.weelay.app.viewmodel.DisplayMessage

private val urlPattern = Regex(
    """https?://[^\s<>"{}|\\^`\[\]]+"""
)

@Composable
fun MessageBubble(
    message: DisplayMessage,
    isFirstInGroup: Boolean,
    isLastInGroup: Boolean,
    isOwnMessage: Boolean,
    onNickClick: ((String) -> Unit)? = null,
    nick: String,
    nickColorValue: Color,
    searchQuery: String? = null,
    isHighlighted: Boolean = false,
    showLinkPreview: Boolean = true,
    onLongPress: (() -> Unit)? = null
) {
    val largeRadius = 18.dp
    val smallRadius = 3.dp

    val bubbleShape = if (isOwnMessage) {
        RoundedCornerShape(
            topStart = largeRadius,
            topEnd = if (isFirstInGroup) largeRadius else smallRadius,
            bottomEnd = if (isLastInGroup) largeRadius else smallRadius,
            bottomStart = largeRadius
        )
    } else {
        RoundedCornerShape(
            topStart = if (isFirstInGroup) largeRadius else smallRadius,
            topEnd = largeRadius,
            bottomEnd = largeRadius,
            bottomStart = if (isLastInGroup) largeRadius else smallRadius
        )
    }

    // Own messages and mentions get their own bubble colours, so a mention is findable while
    // scrolling and not only through the buffer list badge.
    val bubbleColor = when {
        isOwnMessage -> Palette.ownBubble
        message.isMention -> Palette.mentionBubble
        else -> Palette.surface
    }
    val mentionAccent = Palette.accent

    val annotatedText = rememberMessageText(message, nick, nickColorValue, searchQuery, leadingStar = true)

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(
                start = if (isOwnMessage) 48.dp else 0.dp,
                end = if (isOwnMessage) 0.dp else 48.dp,
                top = if (isFirstInGroup) 8.dp else 1.dp
            ),
        horizontalArrangement = if (isOwnMessage) Arrangement.End else Arrangement.Start
    ) {
        Surface(
            shape = bubbleShape,
            color = bubbleColor,
            modifier = Modifier
                .widthIn(min = 0.dp)
                .then(
                    // The jump-target outline (from the highlights sheet) is transient and wins;
                    // the mention outline is a permanent property of the message.
                    when {
                        isHighlighted -> Modifier.border(1.5.dp, Palette.searchMatch, bubbleShape)
                        message.isMention && !isOwnMessage ->
                            Modifier.border(1.dp, mentionAccent, bubbleShape)
                        else -> Modifier
                    }
                )
        ) {
            Column(
                modifier = Modifier.padding(
                    start = 10.dp,
                    end = 10.dp,
                    top = if (isFirstInGroup && !isOwnMessage) 4.dp else 5.dp,
                    bottom = 5.dp
                )
            ) {
                if (isFirstInGroup && !message.isAction) {
                    Text(
                        text = nick,
                        style = MaterialTheme.typography.labelSmall.copy(
                            fontSize = 15.sp,
                            fontWeight = FontWeight.SemiBold
                        ),
                        color = nickColorValue,
                        modifier = Modifier
                            .padding(bottom = 1.dp)
                            .then(
                                if (onNickClick != null) Modifier.clickable { onNickClick(nick) }
                                else Modifier
                            )
                    )
                }

                // Message text with the time inline at its end
                Row(
                    verticalAlignment = Alignment.Bottom,
                    modifier = Modifier.width(IntrinsicSize.Max)
                ) {
                    MessageText(
                        text = annotatedText,
                        style = MaterialTheme.typography.bodyMedium.copy(
                            fontSize = 16.sp,
                            lineHeight = 22.sp,
                            color = Palette.onSurface
                        ),
                        onLongPress = onLongPress,
                        modifier = Modifier.weight(1f, fill = false)
                    )

                    Spacer(modifier = Modifier.width(6.dp))

                    Text(
                        text = message.time,
                        style = MaterialTheme.typography.labelSmall.copy(
                            fontSize = 11.sp
                        ),
                        color = Palette.muted,
                        textAlign = TextAlign.End,
                        modifier = Modifier.padding(bottom = 1.dp)
                    )
                }

                // Link preview for first URL in message
                val previewUrl = remember(message.text) { firstUrl(message.text) }
                if (showLinkPreview) previewUrl?.let { url ->
                    LinkPreviewCard(url = url, isOwnMessage = isOwnMessage)
                }
            }
        }
    }
}

/**
 * A message's text, styled: links underlined and tappable, search matches highlighted. An action
 * is one sentence with the acting nick coloured inline; [leadingStar] prefixes it with "* " for
 * layouts that have no separate column to show the star in.
 */
@Composable
fun rememberMessageText(
    message: DisplayMessage,
    nick: String,
    nickColor: Color,
    searchQuery: String?,
    leadingStar: Boolean
): AnnotatedString = remember(message.text, searchQuery, message.isAction, nickColor, leadingStar) {
    buildAnnotatedString {
        if (message.isAction) {
            if (leadingStar) {
                withStyle(SpanStyle(color = nickColor, fontWeight = FontWeight.SemiBold)) { append("* ") }
            }
            val text = message.text
            val nickEnd = if (text.startsWith(nick)) nick.length else 0
            if (nickEnd > 0) {
                withStyle(SpanStyle(color = nickColor, fontWeight = FontWeight.SemiBold)) {
                    append(text.substring(0, nickEnd))
                }
            }
            withStyle(SpanStyle(fontStyle = FontStyle.Italic)) { append(text.substring(nickEnd)) }
            return@buildAnnotatedString
        }

        val text = message.text
        var lastIndex = 0
        for (match in urlPattern.findAll(text)) {
            if (match.range.first > lastIndex) {
                appendWithSearchHighlight(text.substring(lastIndex, match.range.first), searchQuery, Palette.searchMatch)
            }
            pushStringAnnotation(tag = "URL", annotation = match.value)
            withStyle(SpanStyle(color = Palette.link, textDecoration = TextDecoration.Underline)) {
                append(match.value)
            }
            pop()
            lastIndex = match.range.last + 1
        }
        if (lastIndex < text.length) {
            appendWithSearchHighlight(text.substring(lastIndex), searchQuery, Palette.searchMatch)
        }
    }
}

/**
 * Message text that opens a tapped link and reports a long press. A plain Text with its own
 * gesture handling rather than ClickableText, which can't report a long press.
 */
@Composable
fun MessageText(
    text: AnnotatedString,
    style: TextStyle,
    onLongPress: (() -> Unit)?,
    modifier: Modifier = Modifier
) {
    val uriHandler = LocalUriHandler.current
    var textLayout by remember { mutableStateOf<TextLayoutResult?>(null) }
    // The caller passes a fresh lambda on every recomposition; read it through state so that
    // doesn't restart the gesture detector each time.
    val currentOnLongPress by rememberUpdatedState(onLongPress)
    Text(
        text = text,
        style = style,
        onTextLayout = { textLayout = it },
        modifier = modifier.pointerInput(text, onLongPress != null) {
            detectTapGestures(
                onTap = { position ->
                    val offset = textLayout?.getOffsetForPosition(position) ?: return@detectTapGestures
                    text.getStringAnnotations(tag = "URL", start = offset, end = offset)
                        .firstOrNull()
                        ?.let { uriHandler.openUri(it.item) }
                },
                onLongPress = if (onLongPress != null) {
                    { currentOnLongPress?.invoke() }
                } else null
            )
        }
    )
}

/** The first link in a message, which gets the preview card. */
fun firstUrl(text: String): String? = urlPattern.find(text)?.value

private fun AnnotatedString.Builder.appendWithSearchHighlight(
    text: String,
    searchQuery: String?,
    highlightColor: Color
) {
    if (searchQuery.isNullOrEmpty() || searchQuery.length < 2) {
        append(text)
        return
    }
    var lastIndex = 0
    val lowerText = text.lowercase()
    val lowerQuery = searchQuery.lowercase()
    var searchFrom = 0
    while (searchFrom < lowerText.length) {
        val idx = lowerText.indexOf(lowerQuery, searchFrom)
        if (idx == -1) break
        // Text before match
        if (idx > lastIndex) {
            append(text.substring(lastIndex, idx))
        }
        // Highlighted match
        withStyle(SpanStyle(background = highlightColor, color = Color.Black)) {
            append(text.substring(idx, idx + searchQuery.length))
        }
        lastIndex = idx + searchQuery.length
        searchFrom = lastIndex
    }
    if (lastIndex < text.length) {
        append(text.substring(lastIndex))
    }
}
