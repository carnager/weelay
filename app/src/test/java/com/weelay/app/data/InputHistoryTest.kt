package com.weelay.app.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class InputHistoryTest {

    @Test
    fun newestFirst_andPerBuffer() {
        val history = InputHistory()
        history.add("irc.libera.#a", "one")
        history.add("irc.libera.#a", "two")
        history.add("irc.libera.#b", "other")
        assertEquals(listOf("two", "one"), history.forBuffer("irc.libera.#a"))
        assertEquals(listOf("other"), history.forBuffer("irc.libera.#b"))
        assertTrue(history.forBuffer("irc.libera.#c").isEmpty())
    }

    @Test
    fun repeatingAnEntryMovesItToTheTop() {
        val history = InputHistory()
        history.add("b", "/whois x")
        history.add("b", "hello")
        history.add("b", "/whois x")
        assertEquals(listOf("/whois x", "hello"), history.forBuffer("b"))
    }

    @Test
    fun boundedAndIgnoresBlank() {
        val history = InputHistory()
        history.add("b", "   ")
        repeat(InputHistory.MAX_PER_BUFFER + 10) { history.add("b", "m$it") }
        val entries = history.forBuffer("b")
        assertEquals(InputHistory.MAX_PER_BUFFER, entries.size)
        assertEquals("m${InputHistory.MAX_PER_BUFFER + 9}", entries.first())
    }
}
