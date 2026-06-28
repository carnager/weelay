package com.weelay.app.relay

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class MessageFormatTest {

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
