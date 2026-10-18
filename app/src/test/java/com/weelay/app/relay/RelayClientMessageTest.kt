package com.weelay.app.relay

import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.take
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.yield
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Drives [RelayClient.handleMessage] with real relay-api frames.
 *
 * These exercise the protocol layer without a socket: the client only sends outbound requests
 * through an open connection, so with no connection the handler's observable effects are entirely
 * in its state flows.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class RelayClientMessageTest {

    private fun client() = RelayClient()

    private fun buffersFrame(id: Long, name: String, type: String = "channel") = """
        {"code":200,"body_type":"buffers","body":[
          {"id":$id,"name":"$name","short_name":"#chan","type":"formatted","number":1,
           "local_variables":{"type":"$type"},"lines":[]}
        ]}
    """.trimIndent()

    // --- buffers -------------------------------------------------------------

    @Test
    fun buffersResponse_populatesBufferList() = runTest {
        val c = client()
        c.handleMessage(buffersFrame(7, "irc.libera.#archlinux"))

        assertEquals(1, c.buffers.value.size)
        assertEquals("irc.libera.#archlinux", c.buffers.value.first().fullName)
    }

    // --- errors --------------------------------------------------------------

    @Test
    fun authFailure_isFatalRatherThanRetried() = runTest {
        val c = client()
        // 401/403 mean the credentials are wrong, so the client must surface ERROR instead of
        // spinning in RECONNECTING with the same password forever.
        c.handleMessage("""{"code":401,"body":{"error":"Invalid password"}}""")

        assertEquals(ConnectionState.ERROR, c.connectionState.value)
        assertEquals("Invalid password", c.errorMessage.value)
    }

    @Test
    fun nonAuthError_reportsMessageWithoutGoingFatal() = runTest {
        val c = client()
        c.handleMessage("""{"code":404,"body":{"error":"No such buffer"}}""")

        assertEquals("No such buffer", c.errorMessage.value)
        // Recoverable: the connection state is left alone so the reconnect logic still applies.
        assertEquals(ConnectionState.DISCONNECTED, c.connectionState.value)
    }

    @Test
    fun malformedFrame_isReportedRatherThanThrown() = runTest {
        val c = client()
        c.handleMessage("""{"code":200,"body_type":"buffers","body":"not-an-array"}""")

        assertTrue(c.errorMessage.value?.startsWith("Parse error") == true)
    }

    // --- hotlist -------------------------------------------------------------

    @Test
    fun hotlistResponse_isKeyedByBufferIdWithLevelCounts() = runTest {
        val c = client()
        c.handleMessage(
            """
            {"code":200,"body_type":"hotlist","body":[
              {"priority":3,"date":"2026-08-21T10:00:00.000Z","buffer_id":7,"count":[5,3,1,2]}
            ]}
            """.trimIndent()
        )

        val entry = c.hotlist.value[7L]!!
        // count = [low, message, private, highlight]; low-level noise is deliberately excluded.
        assertEquals(4, entry.messageCount)
        assertEquals(2, entry.highlightCount)
        assertEquals(6, entry.totalCount)
    }

    @Test
    fun hotlistEntryWithShortCountArray_doesNotThrow() = runTest {
        val c = client()
        c.handleMessage(
            """{"code":200,"body_type":"hotlist","body":[{"buffer_id":7,"count":[1]}]}"""
        )

        val entry = c.hotlist.value[7L]!!
        assertEquals(0, entry.messageCount)
        assertEquals(0, entry.highlightCount)
    }

    @Test
    fun liveLine_incrementsHotlistForInactiveBuffer() = runTest {
        val c = client()
        c.handleMessage(buffersFrame(7, "irc.libera.#archlinux"))

        c.handleMessage(lineFrame(bufferId = 7, message = "hi", highlight = false))
        c.handleMessage(lineFrame(bufferId = 7, message = "there", highlight = true))

        val entry = c.hotlist.value[7L]!!
        assertEquals(1, entry.messageCount)
        assertEquals(1, entry.highlightCount)
    }

    @Test
    fun liveLine_doesNotCountTowardsHotlistForTheBufferOnScreen() = runTest {
        val c = client()
        c.handleMessage(buffersFrame(7, "irc.libera.#archlinux"))
        c.setActiveBuffer("irc.libera.#archlinux")

        c.handleMessage(lineFrame(bufferId = 7, message = "hi", highlight = false))

        // WeeChat doesn't add the buffer you're reading to its hotlist; neither do we.
        assertNull(c.hotlist.value[7L])
    }

    @Test
    fun ownMessage_doesNotCountTowardsHotlist() = runTest {
        val c = client()
        c.handleMessage(buffersFrame(7, "irc.libera.#archlinux"))

        c.handleMessage(
            lineFrame(bufferId = 7, message = "my own words", tags = """"irc_privmsg","self_msg"""")
        )

        assertNull(c.hotlist.value[7L])
    }

    @Test
    fun markBufferRead_clearsTheBuffersUnreadState() = runTest {
        val c = client()
        c.handleMessage(buffersFrame(7, "irc.libera.#archlinux"))
        c.handleMessage(lineFrame(bufferId = 7, message = "hi"))
        assertTrue(c.hotlist.value.containsKey(7L))

        c.markBufferRead("irc.libera.#archlinux")

        assertNull(c.hotlist.value[7L])
    }

    // --- lines ---------------------------------------------------------------

    @Test
    fun fetchedBacklog_isFlaggedAsHistory() = runTest {
        val c = client()
        val received = async { c.lines.first() }
        // Give the collector a chance to subscribe before the frame is handled.
        yield()

        c.handleMessage(
            """
            {"code":200,"body_type":"lines","request":"GET /api/buffers/irc.libera.%23archlinux/lines",
             "body":[{"id":1,"prefix":"nick","message":"old","tags":["irc_privmsg"]}]}
            """.trimIndent()
        )

        val update = received.await()
        assertTrue(update.isHistory)
        assertEquals("irc.libera.#archlinux", update.bufferName)
    }

    @Test
    fun highlightsAreCollectedFromBothHistoryAndLiveLines() = runTest {
        val c = client()
        c.handleMessage(buffersFrame(7, "irc.libera.#archlinux"))

        c.handleMessage(
            """
            {"code":200,"body_type":"lines","request":"GET /api/buffers/irc.libera.%23archlinux/lines",
             "body":[{"id":1,"prefix":"nick","message":"old ping","tags":["irc_privmsg"],
                      "highlight":true}]}
            """.trimIndent()
        )
        c.handleMessage(lineFrame(bufferId = 7, message = "new ping", highlight = true))

        assertEquals(2, c.highlightLines.size)
        assertEquals("old ping", c.highlightLines[0].second.message)
        assertEquals("new ping", c.highlightLines[1].second.message)
    }

    // --- sync events ---------------------------------------------------------

    @Test
    fun bufferClosedEvent_isHandledDespiteNullBodyType() = runTest {
        val c = client()
        c.handleMessage(buffersFrame(7, "irc.libera.#archlinux"))

        // buffer_closed carries no body at all, so it can only be recognised by event_name.
        // Previously this fell through the body_type dispatch and did nothing.
        c.handleMessage(
            """{"code":0,"message":"Event","event_name":"buffer_closed","buffer_id":7,"body_type":null,"body":null}"""
        )

        assertNull(c.errorMessage.value)
    }

    @Test
    fun bufferOpenedEvent_addsTheBufferWithoutDisturbingExistingOnes() = runTest {
        val c = client()
        c.handleMessage(buffersFrame(7, "irc.libera.#archlinux"))

        // Shares body_type "buffer" with ordinary updates, where the handler only patches buffers
        // it already knows — so routing on event_name is what lets a brand new one through.
        c.handleMessage(
            """
            {"code":0,"message":"Event","event_name":"buffer_opened","buffer_id":9,"body_type":"buffer",
             "body":{"id":9,"name":"irc.libera.#new","local_variables":{"type":"channel"},"lines":[]}}
            """.trimIndent()
        )

        assertEquals(
            listOf("irc.libera.#archlinux", "irc.libera.#new"),
            c.buffers.value.map { it.fullName }
        )
        assertNull(c.errorMessage.value)
    }

    @Test
    fun bufferOpenedEvent_appliesTheBufferImmediately() = runTest {
        val c = client()
        c.handleMessage(
            """
            {"code":0,"message":"Event","event_name":"buffer_opened","buffer_id":9,"body_type":"buffer",
             "body":{"id":9,"name":"irc.libera.#new","local_variables":{"type":"channel"},"lines":[]}}
            """.trimIndent()
        )

        // Available right away, without waiting for the GET /api/buffers round trip.
        assertEquals("irc.libera.#new", c.buffers.value.single().fullName)
    }

    @Test
    fun lineForUnknownBuffer_isReplayedOnceTheBufferIsKnown() = runTest {
        val c = client()

        // A highlight in a channel joined moments ago can beat the buffer list. This used to be
        // dropped outright, costing the message its notification.
        c.handleMessage(lineFrame(bufferId = 9, message = "ping you", highlight = true))
        assertTrue(c.highlightLines.isEmpty())

        val received = async { c.lines.first() }
        yield()
        c.handleMessage(buffersFrame(9, "irc.libera.#new"))

        val update = received.await()
        assertEquals("irc.libera.#new", update.bufferName)
        assertEquals("ping you", update.lines.single().message)
        assertTrue(!update.isHistory)
        // And it counts towards unread, exactly as it would have if it hadn't raced.
        assertEquals(1, c.hotlist.value[9L]?.highlightCount)
        assertEquals(1, c.highlightLines.size)
    }

    @Test
    fun emittedLinesCarryTheirBufferId() = runTest {
        // Real line objects have no buffer_id: a fetch names the buffer only in the request path
        // and a sync event only at the top level. Consumers key previews by RelayLine.bufferId,
        // so the client has to fill it in — without it the buffer list dropped every such line.
        val c = client()
        c.handleMessage(buffersFrame(7, "irc.libera.#archlinux"))
        val received = async { c.lines.take(2).toList() }
        yield()

        c.handleMessage(
            """
            {"code":200,"body_type":"lines","request":"GET /api/buffers/irc.libera.%23archlinux/lines",
             "body":[{"id":1,"prefix":"nick","message":"old","tags":["irc_privmsg"]}]}
            """.trimIndent()
        )
        c.handleMessage(lineFrame(bufferId = 7, message = "new"))

        val (history, live) = received.await()
        assertEquals(7L, history.lines.single().bufferId)
        assertEquals(7L, live.lines.single().bufferId)
    }

    private fun lineFrame(
        bufferId: Long,
        message: String,
        highlight: Boolean = false,
        tags: String = """"irc_privmsg""""
    ) = """
        {"code":0,"message":"Event","event_name":"buffer_line_added","buffer_id":$bufferId,
         "body_type":"line","body":{"id":${message.hashCode().toLong()},
         "date":"2026-08-21T10:00:00.000Z","prefix":"someone","message":"$message",
         "tags":[$tags],"highlight":$highlight,"displayed":true}}
    """.trimIndent()
}
