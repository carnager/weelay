package com.weelay.app.viewmodel

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.weelay.app.data.ConnectionPrefs
import com.weelay.app.relay.ConnectionState
import com.weelay.app.relay.MessageFormat
import com.weelay.app.relay.RelayClient
import com.weelay.app.relay.RelayLine
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import javax.inject.Inject

data class BufferDisplayItem(
    val fullName: String,
    val displayName: String,
    val isChannel: Boolean,
    val lastMessage: String,
    val lastMessageTime: String,
    val unreadCount: Int,
    val number: Int
)

data class HighlightItem(
    val messageId: Long,
    val channel: String,
    val bufferFullName: String,
    val nick: String,
    val message: String,
    val time: String
)

@HiltViewModel
class BufferListViewModel @Inject constructor(
    private val relayClient: RelayClient,
    private val connectionPrefs: ConnectionPrefs
) : ViewModel() {

    val connectionState: StateFlow<ConnectionState> = relayClient.connectionState

    private val _buffers = MutableStateFlow<List<BufferDisplayItem>>(emptyList())
    val buffers: StateFlow<List<BufferDisplayItem>> = _buffers.asStateFlow()

    private val _highlights = MutableStateFlow<List<HighlightItem>>(emptyList())
    val highlights: StateFlow<List<HighlightItem>> = _highlights.asStateFlow()

    private val _highlightsLoading = MutableStateFlow(false)
    val highlightsLoading: StateFlow<Boolean> = _highlightsLoading.asStateFlow()

    private val timeFormatter = DateTimeFormatter.ofPattern("HH:mm")
    private val dateFormatter = DateTimeFormatter.ofPattern("MMM d")

    // Track latest line per buffer from sync events
    private val latestLinePerBuffer = mutableMapOf<Long, RelayLine>()

    // Unread message count per buffer full name. Incremented for live messages to buffers the
    // user isn't currently viewing, reset to 0 when a buffer becomes active.
    private val unreadByName = mutableMapOf<String, Int>()

    init {
        viewModelScope.launch {
            relayClient.buffers.collect { relayBuffers ->
                buildDisplayItems(relayBuffers)
            }
        }

        // Listen for new lines from sync to update last message preview and unread counts
        viewModelScope.launch {
            relayClient.lines.collect { update ->
                val active = relayClient.activeBuffer.value
                for (line in update.lines) {
                    if (line.bufferId != 0L && isRegularMessage(line)) {
                        latestLinePerBuffer[line.bufferId] = line
                        // Only live messages to a buffer that isn't on screen count as unread.
                        if (!update.isHistory && update.bufferName != active) {
                            unreadByName[update.bufferName] = (unreadByName[update.bufferName] ?: 0) + 1
                        }
                    }
                    if (line.highlight && line.displayed && line.message.isNotBlank()) {
                        highlightLines.add(update.bufferName to line)
                    }
                }
                refreshBufferDisplay()
            }
        }

        // Reset unread for whichever buffer the user opens.
        viewModelScope.launch {
            relayClient.activeBuffer.collect { active ->
                if (active != null && unreadByName.remove(active) != null) {
                    refreshBufferDisplay()
                }
            }
        }
    }

    private fun buildDisplayItems(relayBuffers: List<com.weelay.app.relay.RelayBuffer>) {
        val hideSpecial = connectionPrefs.hideSpecialBuffers

        val displayItems = relayBuffers
            .filter { !it.hidden }
            .filter { it.fullName != "core.weechat" }
            .filter { buf ->
                if (hideSpecial) {
                    val type = buf.localVariables["type"] ?: ""
                    // Keep channels and private chats, hide server buffers and others
                    type == "channel" || type == "private"
                } else true
            }
            .map { buf ->
                val lastLine = latestLinePerBuffer[buf.id]?.takeIf { isRegularMessage(it) }
                    ?: buf.lines.lastOrNull { isRegularMessage(it) }
                val isChannel = buf.fullName.contains("#") ||
                        buf.localVariables["type"] == "channel"

                BufferDisplayItem(
                    fullName = buf.fullName,
                    displayName = buf.shortName ?: buf.name,
                    isChannel = isChannel,
                    lastMessage = lastLine?.let { MessageFormat.stripIrcFormatting(it.message) } ?: "",
                    lastMessageTime = lastLine?.let { formatTime(it.date) } ?: "",
                    unreadCount = unreadByName[buf.fullName] ?: 0,
                    number = buf.number
                )
            }
            .sortedBy { it.number }

        _buffers.value = displayItems
    }

    private fun refreshBufferDisplay() {
        val currentBuffers = relayClient.buffers.value
        if (currentBuffers.isEmpty()) return
        buildDisplayItems(currentBuffers)
    }

    fun refreshFilter() {
        refreshBufferDisplay()
    }

    private val highlightLines = mutableListOf<Pair<String, RelayLine>>() // bufferFullName to line

    fun loadHighlights() {
        // Scan all buffers' initial lines for highlights
        val items = mutableListOf<HighlightItem>()
        for (buf in relayClient.buffers.value) {
            for (line in buf.lines) {
                if (line.highlight && line.displayed && line.message.isNotBlank()) {
                    items.add(lineToHighlight(buf.fullName, buf.shortName ?: buf.name, line))
                }
            }
        }
        // Also include highlights collected from sync events
        for ((bufFullName, line) in highlightLines) {
            val buf = relayClient.buffers.value.find { it.fullName == bufFullName }
            val displayName = buf?.shortName ?: buf?.name ?: bufFullName.substringAfterLast(".")
            items.add(lineToHighlight(bufFullName, displayName, line))
        }
        // Deduplicate by message ID, newest first
        _highlights.value = items
            .distinctBy { it.messageId }
            .sortedByDescending { it.messageId }
    }

    private fun lineToHighlight(bufFullName: String, channelName: String, line: RelayLine): HighlightItem {
        return HighlightItem(
            messageId = line.id,
            channel = channelName,
            bufferFullName = bufFullName,
            nick = MessageFormat.extractNick(line.prefix),
            message = MessageFormat.stripIrcFormatting(line.message),
            time = formatTime(line.date)
        )
    }

    private fun formatTime(dateStr: String): String {
        return try {
            val instant = Instant.parse(dateStr)
            val localDateTime = instant.atZone(ZoneId.systemDefault())
            val today = LocalDate.now()
            if (localDateTime.toLocalDate() == today) {
                localDateTime.format(timeFormatter)
            } else {
                localDateTime.format(dateFormatter)
            }
        } catch (e: Exception) {
            dateStr.takeLast(5)
        }
    }

    private fun isRegularMessage(line: RelayLine): Boolean = MessageFormat.isRegularMessage(line)
}
