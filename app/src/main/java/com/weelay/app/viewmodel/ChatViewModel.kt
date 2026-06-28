package com.weelay.app.viewmodel

import androidx.compose.ui.graphics.Color
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.weelay.app.relay.ConnectionState
import com.weelay.app.relay.MessageFormat
import com.weelay.app.relay.RelayClient
import com.weelay.app.relay.RelayLine
import com.weelay.app.relay.RelayNicklistEntry
import com.weelay.app.ui.theme.nickColor
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.temporal.ChronoUnit
import javax.inject.Inject

data class DisplayMessage(
    val id: Long,
    val text: String,
    val time: String,
    val rawDate: String,
    val nick: String,
    val isOwnMessage: Boolean
)

data class MessageGroup(
    /** Stable identity: the first message's line id. Used as the LazyColumn key. */
    val id: Long,
    val nick: String,
    val nickColor: Color,
    val isOwnMessage: Boolean,
    val messages: List<DisplayMessage>,
    /** Epoch millis of the group's first message (0 if unparseable). */
    val timestamp: Long,
    val formattedTimestamp: String,
    val showTimestamp: Boolean
)

@HiltViewModel
class ChatViewModel @Inject constructor(
    private val relayClient: RelayClient
) : ViewModel() {

    val connectionState: StateFlow<ConnectionState> = relayClient.connectionState

    private val _messageGroups = MutableStateFlow<List<MessageGroup>>(emptyList())
    val messageGroups: StateFlow<List<MessageGroup>> = _messageGroups.asStateFlow()

    private val _bufferName = MutableStateFlow("")
    val bufferName: StateFlow<String> = _bufferName.asStateFlow()

    private val _nicklist = MutableStateFlow<List<RelayNicklistEntry>>(emptyList())
    val nicklist: StateFlow<List<RelayNicklistEntry>> = _nicklist.asStateFlow()

    private val _urls = MutableStateFlow<List<Pair<String, String>>>(emptyList()) // url to nick
    val urls: StateFlow<List<Pair<String, String>>> = _urls.asStateFlow()

    private var currentBufferFullName: String = ""
    private var currentBufferId: Long = -1
    private var ownNick: String = ""
    private val allLines = mutableListOf<RelayLine>()

    // Incrementally-maintained derived state, kept in sync with allLines so that a single
    // live line costs O(1) instead of re-grouping the entire history on every message.
    private val displayMessages = mutableListOf<DisplayMessage>()
    private val groups = mutableListOf<MessageGroup>()
    private val collectedUrls = mutableListOf<Pair<String, String>>() // url to nick, chronological
    private var lastMessageTimestamp: Instant? = null

    // Live sync keeps appending lines; cap retention so memory stays bounded. When the cap is
    // exceeded we drop the oldest lines and rebuild once, amortising the cost over many messages.
    private val maxRetainedLines = 3000
    private val retainAfterTrim = 2500
    private val groupGapMinutes = 5L

    private var currentLineCount = 500
    private val targetMessageCount = 50
    private val _initialLoadComplete = MutableStateFlow(false)
    val initialLoadComplete: StateFlow<Boolean> = _initialLoadComplete.asStateFlow()
    private var autoFetchingMore = false
    private var previousMessageCount = 0

    private val _loadedMoreHistory = MutableStateFlow(false)
    val loadedMoreHistory: StateFlow<Boolean> = _loadedMoreHistory.asStateFlow()

    private val _highlights = MutableStateFlow<List<HighlightItem>>(emptyList())
    val highlights: StateFlow<List<HighlightItem>> = _highlights.asStateFlow()

    private val urlPattern = Regex("""https?://[^\s<>"{}|\\^`\[\]]+""")

    private val timeFormatter = DateTimeFormatter.ofPattern("HH:mm")
    private val dateTimeFormatter = DateTimeFormatter.ofPattern("MMM d, HH:mm")

    // Collectors started by loadBuffer. Cancelled before relaunching so switching buffers
    // (or reloading the same one) doesn't stack duplicate infinite collectors on this VM.
    private val bufferJobs = mutableListOf<Job>()

    fun loadBuffer(bufferFullName: String) {
        bufferJobs.forEach { it.cancel() }
        bufferJobs.clear()

        currentBufferFullName = bufferFullName
        allLines.clear()
        displayMessages.clear()
        groups.clear()
        collectedUrls.clear()
        lastMessageTimestamp = null
        _initialLoadComplete.value = false
        autoFetchingMore = false
        currentLineCount = 500

        val buffer = relayClient.buffers.value.find { it.fullName == bufferFullName }
        if (buffer != null) {
            currentBufferId = buffer.id
            _bufferName.value = buffer.shortName ?: buffer.name
            ownNick = buffer.localVariables["nick"] ?: ""
        } else {
            _bufferName.value = bufferFullName.substringAfterLast(".")
        }

        // Watch for incoming lines (history + real-time) — start collecting BEFORE requesting
        bufferJobs += viewModelScope.launch {
            relayClient.lines.collect { update ->
                if (update.bufferName != currentBufferFullName) return@collect
                val displayed = update.lines.filter { it.displayed }
                if (update.isHistory) {
                    // A history batch replaces the whole buffer.
                    allLines.clear()
                    allLines.addAll(displayed)
                    rebuildDerivedState(allowAutoFetch = true)
                } else {
                    // Live lines append incrementally.
                    allLines.addAll(displayed)
                    if (allLines.size > maxRetainedLines) {
                        // Trim oldest lines and rebuild once (amortised across many messages).
                        val drop = allLines.size - retainAfterTrim
                        repeat(drop) { allLines.removeAt(0) }
                        rebuildDerivedState(allowAutoFetch = false)
                    } else {
                        for (line in displayed) appendLine(line)
                        publish()
                    }
                }
            }
        }

        // Watch for nicklist updates
        bufferJobs += viewModelScope.launch {
            relayClient.nicklist.collect { (bufferName, nicks) ->
                if (bufferName == currentBufferFullName) {
                    _nicklist.value = nicks
                        .filter { it.visible }
                        .sortedBy { it.name.lowercase() }
                }
            }
        }

        // Request history and nicklist after collectors are active
        // Small delay to ensure coroutine collectors are subscribed
        bufferJobs += viewModelScope.launch {
            delay(50)
            relayClient.requestLines(bufferFullName, currentLineCount)
            relayClient.requestNicklist(bufferFullName)
        }

        // Re-request data when connection recovers (e.g. after phone idle)
        bufferJobs += viewModelScope.launch {
            var wasDisconnected = false
            relayClient.connectionState.collect { state ->
                if (state != ConnectionState.CONNECTED) {
                    wasDisconnected = true
                } else if (wasDisconnected && currentBufferFullName.isNotEmpty()) {
                    wasDisconnected = false
                    delay(100)
                    relayClient.requestLines(currentBufferFullName, currentLineCount)
                    relayClient.requestNicklist(currentBufferFullName)
                }
            }
        }
    }

    /** Returns true if the message reached an open connection, false if it was dropped. */
    fun sendMessage(text: String): Boolean {
        return relayClient.sendInput(currentBufferFullName, text)
    }

    /** Called when this buffer becomes visible — marks it active so its unread count is cleared. */
    fun onBufferVisible(fullName: String) {
        relayClient.setActiveBuffer(fullName)
    }

    /** Called when leaving this buffer — clears the active marker if it's still this buffer. */
    fun onBufferHidden(fullName: String) {
        if (relayClient.activeBuffer.value == fullName) {
            relayClient.setActiveBuffer(null)
        }
    }

    fun loadMoreHistory() {
        previousMessageCount = allLines.size
        currentLineCount += 100
        _loadedMoreHistory.value = true
        relayClient.requestLines(currentBufferFullName, currentLineCount)
    }

    fun onHistoryScrollHandled() {
        _loadedMoreHistory.value = false
    }

    /**
     * Returns the network prefix for the current buffer, e.g. "irc.libera." from "irc.libera.#archlinux"
     */
    fun getNetworkPrefix(): String {
        // fullName is like "irc.libera.#channel" or "irc.libera.nick"
        // Network prefix is everything up to and including the second dot
        val parts = currentBufferFullName.split(".")
        return if (parts.size >= 3) {
            parts.take(2).joinToString(".") + "."
        } else {
            ""
        }
    }

    /**
     * Send /query to create a buffer, then return the expected buffer full name
     */
    fun queryNick(nick: String): String {
        relayClient.sendInput(currentBufferFullName, "/query $nick")
        return getNetworkPrefix() + nick
    }

    /**
     * Open a query with NickServ and send INFO nick there
     */
    fun nickservInfo(nick: String): String {
        relayClient.sendInput(currentBufferFullName, "/query NickServ INFO $nick")
        return getNetworkPrefix() + "NickServ"
    }

    /** Map a relay line to a display message (caller has already filtered with isRegularMessage). */
    private fun toDisplayMessage(line: RelayLine): DisplayMessage {
        val nick = extractNick(line.prefix)
        val isOwn = nick == ownNick || line.tags.any { it == "nick_$ownNick" }
        return DisplayMessage(
            id = line.id,
            text = MessageFormat.stripIrcFormatting(line.message),
            time = formatMessageTime(line.date),
            rawDate = line.date,
            nick = nick,
            isOwnMessage = isOwn
        )
    }

    /**
     * Append a single line to the derived state (display messages, groups, URLs) without
     * touching the rest of the history. Either extends the last group or opens a new one.
     */
    private fun appendLine(line: RelayLine) {
        if (!isRegularMessage(line)) return
        val msg = toDisplayMessage(line)
        displayMessages.add(msg)

        val msgTimestamp = parseIsoTimestamp(msg.rawDate)
        val last = groups.lastOrNull()
        val timeDiffMinutes = if (last != null && lastMessageTimestamp != null && msgTimestamp != null) {
            ChronoUnit.MINUTES.between(lastMessageTimestamp, msgTimestamp)
        } else 0L
        val showTimeSeparator = timeDiffMinutes > groupGapMinutes

        if (last != null && msg.nick == last.nick && !showTimeSeparator) {
            // Extend the current group.
            groups[groups.size - 1] = last.copy(messages = last.messages + msg)
        } else {
            groups.add(
                MessageGroup(
                    id = msg.id,
                    nick = msg.nick,
                    nickColor = nickColor(msg.nick),
                    isOwnMessage = msg.isOwnMessage,
                    messages = listOf(msg),
                    timestamp = msgTimestamp?.toEpochMilli() ?: 0L,
                    formattedTimestamp = formatGroupTimestamp(msgTimestamp),
                    showTimestamp = groups.isEmpty() || showTimeSeparator
                )
            )
        }
        lastMessageTimestamp = msgTimestamp ?: lastMessageTimestamp

        urlPattern.findAll(msg.text).forEach { match ->
            collectedUrls.add(match.value to msg.nick)
        }
    }

    /** Recompute all derived state from allLines. Used on history loads and after trimming. */
    private fun rebuildDerivedState(allowAutoFetch: Boolean) {
        displayMessages.clear()
        groups.clear()
        collectedUrls.clear()
        lastMessageTimestamp = null

        for (line in allLines) appendLine(line)

        if (allowAutoFetch) {
            // If we don't have enough real messages, request more (cap at 5000 to avoid infinite fetching).
            if (!autoFetchingMore && displayMessages.size < targetMessageCount && currentLineCount < 5000) {
                autoFetchingMore = true
                currentLineCount += 500
                relayClient.requestLines(currentBufferFullName, currentLineCount)
            } else if (autoFetchingMore) {
                autoFetchingMore = false
                _initialLoadComplete.value = true
            } else if (!_initialLoadComplete.value) {
                _initialLoadComplete.value = true
            }
        }

        publish()
    }

    /** Push the current derived state to the UI. */
    private fun publish() {
        _messageGroups.value = groups.toList()
        _urls.value = collectedUrls.reversed() // newest first
    }

    private fun isRegularMessage(line: RelayLine): Boolean = MessageFormat.isRegularMessage(line)

    private fun extractNick(prefix: String): String = MessageFormat.extractNick(prefix)

    private fun formatMessageTime(dateStr: String): String {
        return try {
            val instant = Instant.parse(dateStr)
            instant.atZone(ZoneId.systemDefault()).format(timeFormatter)
        } catch (e: Exception) {
            ""
        }
    }

    private fun formatGroupTimestamp(instant: Instant?): String {
        if (instant == null) return ""
        return try {
            instant.atZone(ZoneId.systemDefault()).format(dateTimeFormatter)
        } catch (e: Exception) {
            ""
        }
    }

    private fun parseIsoTimestamp(dateStr: String): Instant? {
        return try {
            Instant.parse(dateStr)
        } catch (e: Exception) {
            null
        }
    }

    fun loadHighlights() {
        val items = allLines
            .filter { it.highlight && it.displayed && it.message.isNotBlank() }
            .map { line ->
                val nick = extractNick(line.prefix)
                val buf = relayClient.buffers.value.find { it.id == line.bufferId }
                HighlightItem(
                    messageId = line.id,
                    channel = buf?.shortName ?: buf?.name ?: currentBufferFullName.substringAfterLast("."),
                    bufferFullName = currentBufferFullName,
                    nick = nick,
                    message = MessageFormat.stripIrcFormatting(line.message),
                    time = formatMessageTime(line.date)
                )
            }
            .reversed()
        _highlights.value = items
    }
}
