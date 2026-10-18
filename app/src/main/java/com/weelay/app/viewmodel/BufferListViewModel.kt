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
    /** Unread messages, from WeeChat's hotlist. Includes [highlightCount]. */
    val unreadCount: Int,
    /** Unread messages that highlighted the user; badged differently from plain unreads. */
    val highlightCount: Int,
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

    // Buffers already given a deeper history fetch to find a preview message; see
    // requestPreviewBackfill.
    private val previewBackfillRequested = mutableSetOf<Long>()

    init {
        viewModelScope.launch {
            relayClient.buffers.collect { relayBuffers ->
                // An empty list means we disconnected; allow backfills to be retried next session.
                if (relayBuffers.isEmpty()) previewBackfillRequested.clear()
                buildDisplayItems(relayBuffers)
                requestPreviewBackfill(relayBuffers)
            }
        }

        // Unread counts come from WeeChat's hotlist rather than being tallied here, so they are
        // already correct on the first frame after connecting and stay in step with the desktop.
        viewModelScope.launch {
            relayClient.hotlist.collect {
                refreshBufferDisplay()
            }
        }

        // Listen for new lines from sync to update the last-message preview. Highlights are not
        // collected here: RelayClient already accumulates them (from history and live sync alike)
        // in one bounded list, and keeping a second copy here only risked the two drifting apart.
        viewModelScope.launch {
            relayClient.lines.collect { update ->
                for (line in update.lines) {
                    if (line.bufferId == 0L || !isRegularMessage(line)) continue
                    // Keep the newest: a history response can land after a live line that it
                    // doesn't include, and must not roll the preview back. Ids rise within a buffer.
                    val current = latestLinePerBuffer[line.bufferId]
                    if (current == null || line.id >= current.id) {
                        latestLinePerBuffer[line.bufferId] = line
                    }
                }
                refreshBufferDisplay()
            }
        }
    }

    private fun buildDisplayItems(relayBuffers: List<com.weelay.app.relay.RelayBuffer>) {
        val hideSpecial = connectionPrefs.hideSpecialBuffers
        val hotlist = relayClient.hotlist.value

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
                val isChannel = MessageFormat.isChannel(buf)

                val hot = hotlist[buf.id]

                BufferDisplayItem(
                    fullName = buf.fullName,
                    displayName = buf.shortName ?: buf.name,
                    isChannel = isChannel,
                    lastMessage = lastLine?.let { MessageFormat.stripIrcFormatting(it.message) } ?: "",
                    lastMessageTime = lastLine?.let { formatTime(it.date) } ?: "",
                    unreadCount = hot?.totalCount ?: 0,
                    highlightCount = hot?.highlightCount ?: 0,
                    number = buf.number
                )
            }
            .sortedBy { it.number }

        _buffers.value = displayItems
    }

    /**
     * Fetch a deeper slice of history for buffers whose preview came back empty.
     *
     * The buffer list is loaded with `lines=-50`, and in a quiet channel with churny join/quit
     * traffic all fifty can be status lines — WeeChat has already filtered them (they arrive with
     * displayed=false), but they still fill the window, leaving no real message to preview. The
     * relay api has no way to ask for only displayed lines, so the only remedy is to look further
     * back, and only for the buffers that actually need it.
     *
     * The responses land in the lines collector above, which picks the newest real message.
     */
    private fun requestPreviewBackfill(relayBuffers: List<com.weelay.app.relay.RelayBuffer>) {
        for (buf in relayBuffers) {
            if (buf.id == 0L || buf.id in previewBackfillRequested) continue
            // Nothing fetched yet, or a usable preview already exists.
            if (buf.lines.isEmpty()) continue
            if (latestLinePerBuffer[buf.id] != null) continue
            if (buf.lines.any { isRegularMessage(it) }) continue

            // Once per buffer per session: if even the deeper window is all status lines, the
            // channel really has had no messages and re-asking would just loop.
            previewBackfillRequested += buf.id
            relayClient.requestLines(buf.fullName, PREVIEW_BACKFILL_LINES)
        }
    }

    private fun refreshBufferDisplay() {
        val currentBuffers = relayClient.buffers.value
        if (currentBuffers.isEmpty()) return
        buildDisplayItems(currentBuffers)
    }

    /** Mark a buffer read here and in WeeChat, without opening it. */
    fun markRead(fullName: String) {
        relayClient.markBufferRead(fullName)
    }

    /**
     * Close a buffer in WeeChat. For an IRC channel that also parts it, which is why the UI asks
     * first. The buffer list updates from the resulting buffer_closed event.
     */
    fun closeBuffer(fullName: String) {
        relayClient.sendInput(fullName, "/buffer close")
    }

    fun refreshFilter() {
        refreshBufferDisplay()
    }

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
        for ((bufFullName, line) in relayClient.highlightLines) {
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

    private companion object {
        /** How far back to look for a preview message in a buffer dominated by status lines. */
        const val PREVIEW_BACKFILL_LINES = 500
    }
}
