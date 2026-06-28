package com.weelay.app.relay

import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class RelayMessageTest {

    // Mirrors the parser configuration used by RelayClient.
    private val json = Json {
        ignoreUnknownKeys = true
        isLenient = true
        encodeDefaults = true
    }

    // --- FlexLongSerializer --------------------------------------------------

    @Test
    fun flexLong_parsesPlainInteger() {
        val buffer = json.decodeFromString(RelayBuffer.serializer(), """{"id": 42, "name": "irc.libera.#a"}""")
        assertEquals(42L, buffer.id)
    }

    @Test
    fun flexLong_parsesScientificNotation() {
        // WeeChat can render large ids in scientific notation; 1.77e15 = 1_770_000_000_000_000.
        val buffer = json.decodeFromString(RelayBuffer.serializer(), """{"id": 1.77e15, "name": "x"}""")
        assertEquals(1_770_000_000_000_000L, buffer.id)
    }

    @Test
    fun flexLong_parsesQuotedString() {
        val buffer = json.decodeFromString(RelayBuffer.serializer(), """{"id": "1759000000000000001", "name": "x"}""")
        assertEquals(1_759_000_000_000_000_001L, buffer.id)
    }

    @Test
    fun flexLong_preservesLargePlainIntegerExactly() {
        val line = json.decodeFromString(RelayLine.serializer(), """{"id": 9007199254740993, "buffer_id": 9007199254740993}""")
        // 2^53 + 1 — would be lost if routed through a Double.
        assertEquals(9_007_199_254_740_993L, line.id)
        assertEquals(9_007_199_254_740_993L, line.bufferId)
    }

    // --- RelayBuffer ---------------------------------------------------------

    @Test
    fun relayBuffer_mapsFieldsAndDefaults() {
        val src = """
            {
              "id": 7,
              "name": "irc.libera.#archlinux",
              "short_name": "#archlinux",
              "type": "channel",
              "number": 3,
              "local_variables": {"type": "channel", "nick": "me"},
              "hidden": false
            }
        """.trimIndent()
        val buffer = json.decodeFromString(RelayBuffer.serializer(), src)
        assertEquals(7L, buffer.id)
        assertEquals("irc.libera.#archlinux", buffer.fullName) // fullName aliases name
        assertEquals("#archlinux", buffer.shortName)
        assertEquals("channel", buffer.type)
        assertEquals(3, buffer.number)
        assertEquals("me", buffer.localVariables["nick"])
        assertFalse(buffer.hidden)
        assertTrue(buffer.lines.isEmpty())
    }

    @Test
    fun relayBuffer_nullShortNameAndUnknownKeysIgnored() {
        val src = """{"id": 1, "name": "core.weechat", "some_future_field": [1,2,3]}"""
        val buffer = json.decodeFromString(RelayBuffer.serializer(), src)
        assertNull(buffer.shortName)
        assertEquals("core.weechat", buffer.name)
    }

    // --- RelayLine -----------------------------------------------------------

    @Test
    fun relayLine_mapsRenamedFieldsAndTags() {
        val src = """
            {
              "id": 100,
              "buffer_id": 7,
              "date": "2026-06-28T05:15:00.000Z",
              "date_printed": "2026-06-28T05:15:01.000Z",
              "prefix": "@alice",
              "message": "hello",
              "tags": ["irc_privmsg", "notify_message"],
              "highlight": true,
              "displayed": true,
              "notify_level": 2
            }
        """.trimIndent()
        val line = json.decodeFromString(RelayLine.serializer(), src)
        assertEquals(100L, line.id)
        assertEquals(7L, line.bufferId)
        assertEquals("2026-06-28T05:15:01.000Z", line.datePrinted)
        assertEquals("@alice", line.prefix)
        assertTrue(line.highlight)
        assertEquals(2, line.notifyLevel)
        assertEquals(listOf("irc_privmsg", "notify_message"), line.tags)
    }

    @Test
    fun relayLine_decodesListOfLines() {
        val src = """[{"id": 1, "message": "a"}, {"id": 2, "message": "b"}]"""
        val lines = json.decodeFromString(ListSerializer(RelayLine.serializer()), src)
        assertEquals(2, lines.size)
        assertEquals("b", lines[1].message)
    }

    // --- LinesUpdate ---------------------------------------------------------

    @Test
    fun linesUpdate_destructuringAndHistoryDefault() {
        val update = LinesUpdate("irc.libera.#a", listOf(RelayLine(id = 1)))
        val (name, lines) = update // (bufferName, lines) destructuring must keep working
        assertEquals("irc.libera.#a", name)
        assertEquals(1, lines.size)
        assertFalse(update.isHistory) // defaults to live
    }
}
