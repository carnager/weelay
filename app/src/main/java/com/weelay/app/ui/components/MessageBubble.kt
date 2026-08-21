package com.weelay.app.ui.components

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
import androidx.compose.foundation.text.ClickableText
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
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
    isHighlighted: Boolean = false
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

    // WhatsApp-like colors: green tint for own, light grey for others. A message that mentioned
    // the user gets a warmer bubble so it's findable while scrolling, not just via the buffer
    // list badge.
    val bubbleColor = when {
        isOwnMessage -> Color(0xFF005C4B) // WhatsApp dark green
        message.isMention -> Color(0xFF3D3323) // amber-tinted dark for mentions
        else -> Color(0xFF1F2C34) // WhatsApp dark grey bubble
    }
    val mentionAccent = Color(0xFFFFB300)

    val textColor = Color(0xFFE9EDEF) // Light text for dark bubbles
    val timeColor = Color(0xFF8696A0) // Muted grey for timestamps
    val linkColor = Color(0xFF53BDEB) // WhatsApp-style link blue

    val uriHandler = LocalUriHandler.current

    val searchHighlightColor = Color(0xFFFFD54F) // Yellow highlight for search matches

    val annotatedText = remember(message.text, searchQuery, message.isAction, nickColorValue) {
        buildAnnotatedString {
            // An action reads as one sentence — "* nick does something" — so it gets a leading
            // star and the acting nick coloured inline, rather than a nick header above a bubble
            // whose speaker would show as a bare "*".
            if (message.isAction) {
                withStyle(SpanStyle(color = nickColorValue, fontWeight = FontWeight.SemiBold)) {
                    append("* ")
                }
                val text = message.text
                val nickEnd = if (text.startsWith(nick)) nick.length else 0
                if (nickEnd > 0) {
                    withStyle(SpanStyle(color = nickColorValue, fontWeight = FontWeight.SemiBold)) {
                        append(text.substring(0, nickEnd))
                    }
                }
                withStyle(SpanStyle(fontStyle = FontStyle.Italic)) {
                    append(text.substring(nickEnd))
                }
                return@buildAnnotatedString
            }

            val text = message.text
            // Build a list of styled ranges: URLs and search matches
            data class StyledRange(val start: Int, val end: Int, val type: String, val value: String)
            val ranges = mutableListOf<StyledRange>()

            // Find URLs
            urlPattern.findAll(text).forEach { match ->
                ranges.add(StyledRange(match.range.first, match.range.last + 1, "url", match.value))
            }

            // Process text character by character, applying URL and search styling
            var lastIndex = 0
            // Sort URL ranges
            val urlRanges = ranges.sortedBy { it.start }

            for (urlRange in urlRanges) {
                // Text before URL - may contain search highlights
                if (urlRange.start > lastIndex) {
                    appendWithSearchHighlight(text.substring(lastIndex, urlRange.start), searchQuery, searchHighlightColor)
                }
                // URL
                pushStringAnnotation(tag = "URL", annotation = urlRange.value)
                withStyle(SpanStyle(color = linkColor, textDecoration = TextDecoration.Underline)) {
                    append(urlRange.value)
                }
                pop()
                lastIndex = urlRange.end
            }
            // Remaining text after last URL
            if (lastIndex < text.length) {
                appendWithSearchHighlight(text.substring(lastIndex), searchQuery, searchHighlightColor)
            }
        }
    }

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
                        isHighlighted -> Modifier.border(1.5.dp, Color(0xFFFFD54F), bubbleShape)
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
                        color = if (isOwnMessage) Color.White else nickColorValue,
                        modifier = Modifier
                            .padding(bottom = 1.dp)
                            .then(
                                if (onNickClick != null) Modifier.clickable { onNickClick(nick) }
                                else Modifier
                            )
                    )
                }

                // Message text with inline time — WhatsApp style
                Row(
                    verticalAlignment = Alignment.Bottom,
                    modifier = Modifier.width(IntrinsicSize.Max)
                ) {
                    ClickableText(
                        text = annotatedText,
                        style = MaterialTheme.typography.bodyMedium.copy(
                            fontSize = 16.sp,
                            lineHeight = 22.sp,
                            color = textColor
                        ),
                        modifier = Modifier.weight(1f, fill = false),
                        onClick = { offset ->
                            annotatedText.getStringAnnotations(tag = "URL", start = offset, end = offset)
                                .firstOrNull()?.let { annotation ->
                                    uriHandler.openUri(annotation.item)
                                }
                        }
                    )

                    Spacer(modifier = Modifier.width(6.dp))

                    Text(
                        text = message.time,
                        style = MaterialTheme.typography.labelSmall.copy(
                            fontSize = 11.sp
                        ),
                        color = timeColor,
                        textAlign = TextAlign.End,
                        modifier = Modifier.padding(bottom = 1.dp)
                    )
                }

                // Link preview for first URL in message
                val firstUrl = remember(message.text) {
                    urlPattern.find(message.text)?.value
                }
                firstUrl?.let { url ->
                    LinkPreviewCard(url = url, isOwnMessage = isOwnMessage)
                }
            }
        }
    }
}

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
