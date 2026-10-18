package com.weelay.app.viewmodel

import org.junit.Assert.assertEquals
import org.junit.Test

class NickRankTest {

    @Test
    fun modesSortHighestFirst_unprefixedLast() {
        val prefixes = listOf(" ", "+", "@", "~", "%", "&", "")
        assertEquals(listOf("~", "&", "@", "%", "+", " ", ""), prefixes.sortedBy { nickRank(it) })
    }
}
