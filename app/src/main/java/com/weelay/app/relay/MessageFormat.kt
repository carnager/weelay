package com.weelay.app.relay

/**
 * Shared helpers for interpreting and cleaning relay lines, used by both the chat and
 * buffer-list view models so their filtering/formatting can't drift apart.
 */
object MessageFormat {

    private val nickPrefixChars = charArrayOf('@', '+', '%', '~', '&', '!')

    // Control-C introduces an mIRC color (optionally "fg,bg"); the rest are bold/reset/reverse/italic/underline.
    private val colorCodes = Regex("\u0003\\d{0,2}(,\\d{0,2})?")
    private val styleCodes = Regex("[\u0002\u000F\u0016\u001D\u001F]")

    /** Strip a leading IRC nick-mode prefix (e.g. "@", "+") and surrounding whitespace. */
    fun extractNick(prefix: String): String = prefix.trimStart(*nickPrefixChars).trim()

    /** Remove IRC color and style control codes so they don't render as garbage. */
    fun stripIrcFormatting(text: String): String =
        text.replace(colorCodes, "").replace(styleCodes, "")

    /**
     * True for lines that represent an actual chat message (privmsg/notice/notify-tagged),
     * as opposed to join/part/quit and other system lines.
     */
    fun isRegularMessage(line: RelayLine): Boolean {
        if (!line.displayed || line.message.isBlank()) return false
        if (line.tags.isNotEmpty()) {
            return line.tags.any {
                it == "irc_privmsg" || it == "irc_notice" || it.startsWith("notify_")
            }
        }
        // Fallback when tags are unavailable: reject the common system-line prefixes.
        val trimmedPrefix = line.prefix.trim()
        return trimmedPrefix.isNotEmpty() &&
                trimmedPrefix != "-->" && trimmedPrefix != "<--" &&
                trimmedPrefix != "--" && trimmedPrefix != "==>"
    }
}
