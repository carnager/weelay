package com.weelay.app.relay

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class MessageFormatTest {

    @Test
    fun isRegularMessage_rejectsLoggerBacklogMarker() {
        // WeeChat's logger prints this into every buffer on restart. It is displayed, so the only
        // thing distinguishing it from chat is notify_none — which a "notify_" prefix test used to
        // accept, making "End of backlog" the last-message preview for every channel at once.
        val line = RelayLine(
            prefix = "===",
            message = "========== End of backlog (20 lines) ==========",
            tags = listOf("no_highlight", "notify_none", "logger_backlog_end"),
            displayed = true
        )
        assertFalse(MessageFormat.isRegularMessage(line))
    }

    @Test
    fun isAction_detectedByTagAndByPrefix() {
        val live = RelayLine(
            prefix = "*",
            message = "tribly schiebt den regen zu demonicmaniac1",
            tags = listOf("irc_privmsg", "irc_action", "notify_message", "nick_tribly"),
            displayed = true
        )
        assertTrue(MessageFormat.isAction(live))
        // Restored from the log, tags gone — only the prefix is left to go on.
        val restored = live.copy(tags = listOf("logger_backlog", "no_highlight", "notify_none"))
        assertTrue(MessageFormat.isAction(restored))
    }

    @Test
    fun actionsSurviveTheSystemLineFilter() {
        // "*" must not be treated as a system prefix, or actions vanish from restored backlog.
        val restored = RelayLine(
            prefix = "*",
            message = "tribly waves",
            tags = listOf("logger_backlog", "no_highlight", "notify_none"),
            displayed = true
        )
        assertTrue(MessageFormat.isRegularMessage(restored))
    }

    @Test
    fun actionNick_prefersTagAndFallsBackToLeadingWord() {
        val tagged = RelayLine(
            prefix = "*",
            message = "tribly waves",
            tags = listOf("irc_action", "nick_tribly"),
            displayed = true
        )
        assertEquals("tribly", MessageFormat.actionNick(tagged, tagged.message))

        val untagged = tagged.copy(tags = listOf("logger_backlog"))
        assertEquals("tribly", MessageFormat.actionNick(untagged, untagged.message))
    }

    @Test
    fun mentionsNick_matchesWholeWordOnly() {
        assertTrue(MessageFormat.mentionsNick("rasi: ping", "rasi"))
        assertTrue(MessageFormat.mentionsNick("thanks rasi", "rasi"))
        assertTrue(MessageFormat.mentionsNick("hey RASI", "rasi"))
        assertTrue(MessageFormat.mentionsNick("(rasi)", "rasi"))
        // Substrings of longer nicks or words must not count.
        assertFalse(MessageFormat.mentionsNick("rasika said hi", "rasi"))
        assertFalse(MessageFormat.mentionsNick("brasilia", "rasi"))
    }

    @Test
    fun mentionsNick_doesNotMatchInsideAnIrcNickWithSpecialChars() {
        // '|' and '-' are legal in nicks, so "rasi" must not fire on someone else's "rasi|away".
        assertFalse(MessageFormat.mentionsNick("rasi|away has left", "rasi"))
        assertFalse(MessageFormat.mentionsNick("talk to rasi-bot", "rasi"))
    }

    @Test
    fun mentionsNick_handlesNicksContainingRegexMetacharacters() {
        // Nicks may contain [ ] { } \ ` | ^ — these must be escaped, not treated as a pattern.
        assertTrue(MessageFormat.mentionsNick("hi [afk]user", "[afk]user"))
        assertFalse(MessageFormat.mentionsNick("hi someone", "[afk]user"))
    }

    @Test
    fun isRegularMessage_acceptsChatRestoredFromTheLog() {
        // WeeChat tags restored backlog notify_none so that reloading history doesn't re-notify.
        // These are real conversation and must still display, or a buffer whose contents are all
        // restored backlog (i.e. every buffer, just after a WeeChat restart) renders empty.
        val line = RelayLine(
            prefix = "someone",
            message = "a message from before the restart",
            tags = listOf("logger_backlog", "no_highlight", "notify_none"),
            displayed = true
        )
        assertTrue(MessageFormat.isRegularMessage(line))
    }

    @Test
    fun isRegularMessage_rejectsJoinPartRestoredFromTheLog() {
        // Restoring loses the original tags, so within backlog only the prefix distinguishes
        // chat from join/part noise.
        val line = RelayLine(
            prefix = "-->",
            message = "someone (~someone@example.org) has joined #chan",
            tags = listOf("logger_backlog", "no_highlight", "notify_none"),
            displayed = true
        )
        assertFalse(MessageFormat.isRegularMessage(line))
    }

    @Test
    fun isRegularMessage_rejectsAnyNotifyNoneLine() {
        val line = RelayLine(
            prefix = "--",
            message = "Mode #chan [+o someone] by ChanServ",
            tags = listOf("irc_mode", "notify_none"),
            displayed = true
        )
        assertFalse(MessageFormat.isRegularMessage(line))
    }

    @Test
    fun isRegularMessage_acceptsRealNotifyLevels() {
        for (tag in listOf("notify_message", "notify_private", "notify_highlight")) {
            val line = RelayLine(
                prefix = "nick",
                message = "hello",
                tags = listOf(tag),
                displayed = true
            )
            assertTrue("expected $tag to count as a message", MessageFormat.isRegularMessage(line))
        }
    }

    // IRC control characters, named for readability.
    private val color = "\u0003"      // mIRC color introducer, optionally "fg,bg"
    private val bold = "\u0002"
    private val reset = "\u000F"
    private val reverse = "\u0016"
    private val italic = "\u001D"
    private val underline = "\u001F"

    // --- extractNick ---------------------------------------------------------

    @Test
    fun extractNick_stripsLeadingModePrefixes() {
        assertEquals("alice", MessageFormat.extractNick("@alice"))
        assertEquals("bob", MessageFormat.extractNick("+bob"))
        assertEquals("carol", MessageFormat.extractNick("%carol"))
        assertEquals("dave", MessageFormat.extractNick("~dave"))
        assertEquals("erin", MessageFormat.extractNick("&erin"))
        assertEquals("frank", MessageFormat.extractNick("!frank"))
    }

    @Test
    fun extractNick_trimsWhitespaceAndLeavesPlainNicks() {
        assertEquals("plain", MessageFormat.extractNick("plain"))
        assertEquals("spaced", MessageFormat.extractNick("  spaced  "))
        assertEquals("", MessageFormat.extractNick(""))
    }

    @Test
    fun extractNick_stripsMultipleStackedPrefixes() {
        assertEquals("op", MessageFormat.extractNick("@+op"))
    }

    // --- stripIrcFormatting --------------------------------------------------

    @Test
    fun stripIrcFormatting_removesColorCodes() {
        assertEquals("red text", MessageFormat.stripIrcFormatting("${color}04red text"))
        assertEquals("fg and bg", MessageFormat.stripIrcFormatting("${color}04,01fg and bg"))
        assertEquals("bare", MessageFormat.stripIrcFormatting("${color}bare"))
    }

    @Test
    fun stripIrcFormatting_removesStyleCodes() {
        assertEquals("bold", MessageFormat.stripIrcFormatting("${bold}bold${bold}"))
        assertEquals(
            "styled",
            MessageFormat.stripIrcFormatting("${italic}st${underline}yl${reverse}ed${reset}")
        )
    }

    @Test
    fun stripIrcFormatting_leavesPlainTextUntouched() {
        assertEquals("hello world", MessageFormat.stripIrcFormatting("hello world"))
        assertEquals("https://x.test/a", MessageFormat.stripIrcFormatting("https://x.test/a"))
    }

    // --- isRegularMessage ----------------------------------------------------

    @Test
    fun isRegularMessage_trueForPrivmsgAndNoticeTags() {
        assertTrue(MessageFormat.isRegularMessage(line(tags = listOf("irc_privmsg"))))
        assertTrue(MessageFormat.isRegularMessage(line(tags = listOf("irc_notice"))))
        assertTrue(MessageFormat.isRegularMessage(line(tags = listOf("notify_message"))))
    }

    @Test
    fun isRegularMessage_falseForNonMessageTags() {
        // Tagged but not a message kind (e.g. join/quit) -> not a regular message
        assertFalse(MessageFormat.isRegularMessage(line(tags = listOf("irc_join"))))
        assertFalse(MessageFormat.isRegularMessage(line(tags = listOf("irc_quit"))))
    }

    @Test
    fun isRegularMessage_falseForNonDisplayedOrBlank() {
        assertFalse(MessageFormat.isRegularMessage(line(tags = listOf("irc_privmsg"), displayed = false)))
        assertFalse(MessageFormat.isRegularMessage(line(message = "   ", tags = listOf("irc_privmsg"))))
    }

    @Test
    fun isRegularMessage_fallbackRejectsSystemPrefixesWhenUntagged() {
        assertFalse(MessageFormat.isRegularMessage(line(prefix = "-->", tags = emptyList())))
        assertFalse(MessageFormat.isRegularMessage(line(prefix = "<--", tags = emptyList())))
        assertFalse(MessageFormat.isRegularMessage(line(prefix = "--", tags = emptyList())))
        assertFalse(MessageFormat.isRegularMessage(line(prefix = "==>", tags = emptyList())))
    }

    @Test
    fun isRegularMessage_fallbackAcceptsRealNickWhenUntagged() {
        assertTrue(MessageFormat.isRegularMessage(line(prefix = "alice", tags = emptyList())))
    }

    private fun line(
        message: String = "hi",
        prefix: String = "alice",
        tags: List<String> = emptyList(),
        displayed: Boolean = true,
    ) = RelayLine(
        id = 1L,
        message = message,
        prefix = prefix,
        tags = tags,
        displayed = displayed,
    )
}
