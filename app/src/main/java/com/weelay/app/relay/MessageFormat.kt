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
     * WeeChat notify levels that mark a line as something a human said. Deliberately an exact
     * set rather than a "notify_" prefix test: `notify_none` is WeeChat stating the opposite —
     * that the line is *not* a message — and a prefix test matched it, which let system lines
     * such as the logger's "========== End of backlog ==========" marker (tagged
     * `no_highlight,notify_none,logger_backlog_end`) through as chat. After a WeeChat restart
     * that marker is the newest line in every buffer, so it became the preview for all of them.
     */
    private val messageNotifyTags = setOf("notify_message", "notify_private", "notify_highlight")

    /**
     * True for lines that represent an actual chat message (privmsg/notice/notify-tagged),
     * as opposed to join/part/quit and other system lines.
     */
    fun isRegularMessage(line: RelayLine): Boolean {
        if (!line.displayed || line.message.isBlank()) return false

        // The logger's "========== End of backlog ==========" marker is decoration, never content.
        if (line.tags.contains(TAG_BACKLOG_END)) return false

        // Lines restored from the log file when WeeChat starts. WeeChat re-prints them as plain
        // text and tags the whole batch notify_none — that means "don't re-notify me for history
        // I've already seen", not "these aren't messages". Restoring also loses the original
        // per-line tags, so chat and join/part are indistinguishable except by prefix; fall back
        // to the same heuristic used for untagged lines.
        if (line.tags.contains(TAG_BACKLOG)) return looksLikeChatPrefix(line.prefix)

        if (line.tags.isNotEmpty()) {
            return line.tags.any {
                it == "irc_privmsg" || it == "irc_notice" || it in messageNotifyTags
            }
        }
        // Fallback when tags are unavailable: reject the common system-line prefixes.
        return looksLikeChatPrefix(line.prefix)
    }

    /**
     * A matcher for [nick] appearing as a whole word.
     *
     * IRC nicks may contain `[ ] { } \ ` | ^ -`, which `\b` treats as word boundaries, so the
     * usual word-boundary shorthand would match "rasi" inside "rasi|away". The explicit character
     * class avoids that, while still keeping "rasi" from matching inside "rasika".
     *
     * Compiling this is not free — callers that check many lines should build it once per nick.
     */
    fun nickMentionRegex(nick: String): Regex {
        val boundary = "\\w\\[\\]{}\\\\`|^-"
        return Regex(
            "(?<![$boundary])" + Regex.escape(nick) + "(?![$boundary])",
            RegexOption.IGNORE_CASE
        )
    }

    /** True if [text] mentions [nick] as a whole word. */
    fun mentionsNick(text: String, nick: String): Boolean {
        if (nick.isEmpty()) return false
        return nickMentionRegex(nick).containsMatchIn(text)
    }

    /**
     * True for a /me action. WeeChat prints these with prefix "*" and the acting nick as the first
     * word of the message, so they can't be rendered like an ordinary "nick said X" line.
     *
     * The prefix check is the fallback for lines restored from the log, which have lost their tags.
     */
    fun isAction(line: RelayLine): Boolean =
        line.tags.contains("irc_action") || line.prefix.trim() == "*"

    /**
     * The nick behind an action. Prefers the `nick_<name>` tag; falls back to the first word of
     * [text], which is where WeeChat puts it — that's all restored backlog lines have left.
     */
    fun actionNick(line: RelayLine, text: String): String {
        line.tags.firstOrNull { it.startsWith("nick_") }
            ?.removePrefix("nick_")
            ?.let { if (it.isNotEmpty()) return it }
        return text.trimStart().substringBefore(' ').trim()
    }

    /** True if [prefix] looks like a nick rather than one of WeeChat's system-line markers. */
    private fun looksLikeChatPrefix(prefix: String): Boolean {
        val trimmed = prefix.trim()
        return trimmed.isNotEmpty() && trimmed !in systemPrefixes
    }

    private const val TAG_BACKLOG = "logger_backlog"
    private const val TAG_BACKLOG_END = "logger_backlog_end"

    // "*" is deliberately absent: that's an action's prefix, which is real content. Actions are
    // identified by isAction and rendered specially rather than filtered out.
    private val systemPrefixes = setOf("-->", "<--", "--", "==>", "===", "=!=")
}
