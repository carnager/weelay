package com.weelay.app.data

import javax.inject.Inject
import javax.inject.Singleton

/**
 * Messages and commands the user sent, per buffer, newest first.
 *
 * WeeChat keeps its own input history but the relay api doesn't expose it, so this is the app's
 * own. It lives only in memory: the foreground service keeps the process alive across a normal
 * session, and not writing what you typed to disk is the safer default for a chat client.
 */
@Singleton
class InputHistory @Inject constructor() {

    private val entries = mutableMapOf<String, ArrayDeque<String>>()

    /** Record [text] as the latest input for [buffer]; an earlier identical entry moves to the top. */
    @Synchronized
    fun add(buffer: String, text: String) {
        if (text.isBlank()) return
        val list = entries.getOrPut(buffer) { ArrayDeque() }
        list.remove(text)
        list.addFirst(text)
        while (list.size > MAX_PER_BUFFER) list.removeLast()
    }

    /** History for [buffer], newest first. */
    @Synchronized
    fun forBuffer(buffer: String): List<String> = entries[buffer]?.toList() ?: emptyList()

    companion object {
        const val MAX_PER_BUFFER = 50
    }
}
