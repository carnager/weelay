package com.weelay.app.viewmodel

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class ReadMarkerTest {

    private fun msg(id: Long, own: Boolean = false) = DisplayMessage(
        id = id, text = "m$id", time = "", rawDate = "", nick = if (own) "me" else "them",
        isOwnMessage = own
    )

    @Test
    fun noUnread_noMarker() {
        assertNull(readMarkerId(listOf(msg(1), msg(2)), 0))
    }

    @Test
    fun markerSitsOnOldestUnread() {
        val messages = listOf(msg(1), msg(2), msg(3), msg(4))
        assertEquals(3L, readMarkerId(messages, 2))
    }

    @Test
    fun ownMessagesAreNotCounted() {
        // WeeChat never adds your own lines to the hotlist, so two unread means the last two
        // lines from other people, with your reply in between not counting towards it.
        val messages = listOf(msg(1), msg(2), msg(3), msg(4, own = true), msg(5))
        assertEquals(3L, readMarkerId(messages, 2))
    }

    @Test
    fun moreUnreadThanLoaded_marksOldestLoaded() {
        val messages = listOf(msg(10, own = true), msg(11), msg(12))
        assertEquals(10L, readMarkerId(messages, 50))
    }
}
