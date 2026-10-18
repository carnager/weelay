package com.weelay.app.viewmodel

import androidx.compose.ui.graphics.Color
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.weelay.app.data.ConnectionPrefs
import com.weelay.app.data.InputHistory
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
import kotlinx.coroutines.flow.onSubscription
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
    val isOwnMessage: Boolean,
    /** This message mentioned the user — rendered with a distinct bubble in the chat. */
    val isMention: Boolean = false,
    /** A /me action. WeeChat sends these with prefix "*" and the nick inside the message. */
    val isAction: Boolean = false
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
    private val relayClient: RelayClient,
    private val connectionPrefs: ConnectionPrefs,
    private val inputHistory: InputHistory
) : ViewModel() {

    val connectionState: StateFlow<ConnectionState> = relayClient.connectionState

    private val _messageGroups = MutableStateFlow<List<MessageGroup>>(emptyList())
    val messageGroups: StateFlow<List<MessageGroup>> = _messageGroups.asStateFlow()

    private val _bufferName = MutableStateFlow("")
    val bufferName: StateFlow<String> = _bufferName.asStateFlow()

    /** The buffer's title — the channel topic, for IRC channels. Empty when there is none. */
    private val _bufferTitle = MutableStateFlow("")
    val bufferTitle: StateFlow<String> = _bufferTitle.asStateFlow()

    private val _isChannel = MutableStateFlow(false)
    val isChannel: StateFlow<Boolean> = _isChannel.asStateFlow()

    /** Read once per screen; flipping the setting takes effect the next time a buffer opens. */
    val linkPreviews: Boolean get() = connectionPrefs.linkPreviews

    /** Whether this kind of buffer uses the compact line layout rather than bubbles. */
    fun compactLayout(channel: Boolean): Boolean =
        if (channel) connectionPrefs.compactChannels else connectionPrefs.compactPrivate

    /** What the user sent in this buffer, newest first; see [InputHistory]. */
    private val _sentHistory = MutableStateFlow<List<String>>(emptyList())
    val sentHistory: StateFlow<List<String>> = _sentHistory.asStateFlow()

    private val _nicklist = MutableStateFlow<List<RelayNicklistEntry>>(emptyList())
    val nicklist: StateFlow<List<RelayNicklistEntry>> = _nicklist.asStateFlow()

    private val _urls = MutableStateFlow<List<Pair<String, String>>>(emptyList()) // url to nick
    val urls: StateFlow<List<Pair<String, String>>> = _urls.asStateFlow()

    private var currentBufferFullName: String = ""
    private var currentBufferId: Long = -1
    private var ownNick: String = ""

    // Compiled lazily and reset whenever the buffer (and so the nick) changes; see mentionPatterns.
    private var mentionPatternsCache: List<Regex>? = null
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

    /**
     * False once the server has no older lines to give. The relay only serves what WeeChat holds
     * in memory — it never reads the log files — so after a WeeChat restart that can be as little
     * as `logger.look.backlog` lines per buffer. Without this the "load earlier messages" button
     * stayed put and silently did nothing.
     */
    private val _hasMoreHistory = MutableStateFlow(true)
    val hasMoreHistory: StateFlow<Boolean> = _hasMoreHistory.asStateFlow()

    /**
     * Id of the first message that was unread when the buffer was opened, or null if nothing was.
     * Placed once per opening and then left alone, so new lines arriving while reading don't move
     * it; the UI draws a "new messages" divider above this message and opens scrolled to it.
     */
    private val _readMarkerId = MutableStateFlow<Long?>(null)
    val readMarkerId: StateFlow<Long?> = _readMarkerId.asStateFlow()

    // Unread count from WeeChat's hotlist, captured before opening the buffer clears it.
    private var unreadAtOpen = 0
    private var readMarkerPlaced = false

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
        _sentHistory.value = inputHistory.forBuffer(bufferFullName)
        allLines.clear()
        displayMessages.clear()
        groups.clear()
        collectedUrls.clear()
        lastMessageTimestamp = null
        _initialLoadComplete.value = false
        autoFetchingMore = false
        currentLineCount = 500
        _hasMoreHistory.value = true
        _readMarkerId.value = null
        readMarkerPlaced = false
        _bufferTitle.value = ""

        val buffer = relayClient.buffers.value.find { it.fullName == bufferFullName }
        if (buffer != null) {
            currentBufferId = buffer.id
            _bufferName.value = buffer.shortName ?: buffer.name
            ownNick = buffer.localVariables["nick"] ?: ""
            mentionPatternsCache = null
        } else {
            _bufferName.value = bufferFullName.substringAfterLast(".")
            _isChannel.value = bufferFullName.substringAfterLast('.').startsWith('#')
        }

        // Track the buffer's metadata as it changes: topic changes arrive as buffer updates, and a
        // buffer opened from a deep link may not be in the list yet when the screen first loads.
        bufferJobs += viewModelScope.launch {
            relayClient.buffers.collect { buffers ->
                val buf = buffers.find { it.fullName == currentBufferFullName } ?: return@collect
                _bufferTitle.value = MessageFormat.stripIrcFormatting(buf.title).trim()
                _isChannel.value = MessageFormat.isChannel(buf)
            }
        }

        // Watch for incoming lines (history + real-time). The history request is issued from
        // onSubscription, which runs once this collector is registered on the shared flow — so the
        // response can't be emitted before we're listening. (This previously relied on a 50ms
        // delay, which was a race: on a slow frame the backlog was simply dropped.)
        bufferJobs += viewModelScope.launch {
            relayClient.lines.onSubscription {
                relayClient.requestLines(bufferFullName, currentLineCount)
            }.collect { update ->
                if (update.bufferName != currentBufferFullName) return@collect
                val displayed = update.lines.filter { it.displayed }
                if (update.isHistory) {
                    // Fewer lines back than we asked for means we've reached the oldest line the
                    // server still has in memory, so there is nothing left to page in.
                    _hasMoreHistory.value = update.lines.size >= currentLineCount
                    mergeHistory(displayed)
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

        // Watch for nicklist updates, requesting once subscribed for the same reason as above.
        bufferJobs += viewModelScope.launch {
            relayClient.nicklist.onSubscription {
                relayClient.requestNicklist(bufferFullName)
            }.collect { (bufferName, nicks) ->
                if (bufferName == currentBufferFullName) {
                    // Ranked like WeeChat's nicklist: owners, admins, ops, halfops, voiced, rest.
                    _nicklist.value = nicks
                        .filter { it.visible }
                        .sortedWith(compareBy({ nickRank(it.prefix) }, { it.name.lowercase() }))
                }
            }
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

    /**
     * Send what the user typed into the input bar and remember it for recall. Kept apart from
     * [sendMessage] so commands the UI issues on the user's behalf (kick, ban, …) stay out of it.
     */
    fun sendTypedInput(text: String): Boolean {
        val sent = relayClient.sendInput(currentBufferFullName, text)
        if (sent) {
            inputHistory.add(currentBufferFullName, text)
            _sentHistory.value = inputHistory.forBuffer(currentBufferFullName)
        }
        return sent
    }

    /** Called when this buffer becomes visible — marks it active so its unread count is cleared. */
    fun onBufferVisible(fullName: String) {
        // Read the unread count first: making the buffer active marks it read, which clears it.
        // This runs before loadBuffer's history arrives, so the marker can be placed from it then.
        val bufferId = relayClient.buffers.value.find { it.fullName == fullName }?.id
        unreadAtOpen = bufferId?.let { relayClient.hotlist.value[it]?.totalCount } ?: 0
        relayClient.setActiveBuffer(fullName)
    }

    /** Called when leaving this buffer — clears the active marker if it's still this buffer. */
    fun onBufferHidden(fullName: String) {
        if (relayClient.activeBuffer.value == fullName) {
            relayClient.setActiveBuffer(null)
        }
    }

    fun loadMoreHistory() {
        if (!_hasMoreHistory.value) return
        previousMessageCount = allLines.size
        // Step in larger strides: the relay can only widen the window from "now", so a 100-line
        // bump costs a full refetch to gain very little.
        currentLineCount += 500
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
        val isAction = MessageFormat.isAction(line)
        val text = MessageFormat.stripIrcFormatting(line.message)
        // An action's prefix is just "*", so the speaker has to come from the nick_ tag (or, for
        // lines restored from the log where tags are gone, the leading word of the message).
        val nick = if (isAction) {
            MessageFormat.actionNick(line, text)
        } else {
            extractNick(line.prefix)
        }
        val isOwn = nick == ownNick || line.tags.any { it == "nick_$ownNick" }
        return DisplayMessage(
            id = line.id,
            text = text,
            time = formatMessageTime(line.date),
            rawDate = line.date,
            nick = nick,
            isOwnMessage = isOwn,
            isMention = !isOwn && (line.highlight || mentionsOwnNick(text)),
            isAction = isAction
        )
    }

    /**
     * Fallback mention detection for lines WeeChat didn't flag.
     *
     * Lines restored from the log on a WeeChat restart always come back with highlight=false —
     * the highlight was evaluated when they were first printed, and re-reading the log doesn't
     * re-evaluate it. Without this, every mention older than the last restart looks like ordinary
     * chat. Matches the nick as a whole word so "rasika" doesn't count as a mention of "rasi".
     */
    private fun mentionsOwnNick(text: String): Boolean =
        mentionPatterns().any { it.containsMatchIn(text) }

    /**
     * Matchers for the nick plus any configured highlight words, built once per buffer load.
     * WeeChat's own highlight config can't be read over the relay api, so the extra words come
     * from the app's settings.
     */
    private fun mentionPatterns(): List<Regex> {
        mentionPatternsCache?.let { return it }
        val words = buildList {
            if (ownNick.isNotEmpty()) add(ownNick)
            connectionPrefs.highlightWords
                .split(',')
                .map { it.trim() }
                .filter { it.isNotEmpty() }
                .forEach { add(it) }
        }
        return words.map { MessageFormat.nickMentionRegex(it) }
            .also { mentionPatternsCache = it }
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

        // Actions stand alone: they read as "* nick does something", so folding them into a
        // neighbouring group under that group's nick header would misattribute them.
        val groupable = !msg.isAction && last?.messages?.lastOrNull()?.isAction != true

        // The first unread message opens a group of its own, so it keeps its nick header under the
        // "new messages" divider rather than continuing the group above it.
        val startsUnread = msg.id == _readMarkerId.value

        if (groupable && !startsUnread && last != null && msg.nick == last.nick && !showTimeSeparator) {
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

    /**
     * Fold a fetched history batch into [allLines], keyed by line id.
     *
     * A history response is a window ending at "now", so it overlaps almost entirely with what we
     * already have; replacing wholesale meant every "load more" and every reconnect threw away and
     * re-grouped the full history. Merging keeps live lines that arrived while the fetch was in
     * flight — those aren't in the response and used to vanish until the next sync line.
     */
    private fun mergeHistory(history: List<RelayLine>) {
        if (history.isEmpty()) return

        val merged = LinkedHashMap<Long, RelayLine>(allLines.size + history.size)
        // History first so it forms the base ordering, then live lines already held are layered on
        // top; ids collide for the overlap, which is exactly the dedup we want.
        for (line in history) merged[line.id] = line
        for (line in allLines) merged[line.id] = line

        allLines.clear()
        allLines.addAll(merged.values.sortedBy { it.id })

        // Merging can push us past the retention cap (a bigger window plus lines held from before).
        if (allLines.size > maxRetainedLines) {
            repeat(allLines.size - retainAfterTrim) { allLines.removeAt(0) }
        }
    }

    /** Recompute all derived state from allLines. Used on history loads and after trimming. */
    private fun rebuildDerivedState(allowAutoFetch: Boolean) {
        regroup()

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

        if (_initialLoadComplete.value && !readMarkerPlaced) {
            readMarkerPlaced = true
            _readMarkerId.value = readMarkerId(displayMessages, unreadAtOpen)
            // Once, so the marked message can be split off into its own group.
            if (_readMarkerId.value != null) regroup()
        }

        publish()
    }

    private fun regroup() {
        displayMessages.clear()
        groups.clear()
        collectedUrls.clear()
        lastMessageTimestamp = null

        for (line in allLines) appendLine(line)
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

/**
 * Where the "new messages" divider goes: the oldest of the last [unread] messages that weren't
 * the user's own. Own messages are skipped because WeeChat never puts them in the hotlist, so
 * they aren't part of the count. If fewer messages are loaded than were unread, the marker goes
 * on the oldest loaded one, since everything on screen is new.
 */
internal fun readMarkerId(messages: List<DisplayMessage>, unread: Int): Long? {
    if (unread <= 0) return null
    var counted = 0
    var oldestUnread: Long? = null
    for (message in messages.asReversed()) {
        if (message.isOwnMessage) continue
        counted++
        oldestUnread = message.id
        if (counted == unread) return oldestUnread
    }
    return messages.firstOrNull()?.id ?: oldestUnread
}

/** Sort key for an IRC nick-mode prefix: higher modes first, unprefixed nicks last. */
internal fun nickRank(prefix: String): Int {
    val mode = prefix.trim().firstOrNull() ?: return NICK_MODE_ORDER.length
    val rank = NICK_MODE_ORDER.indexOf(mode)
    return if (rank >= 0) rank else NICK_MODE_ORDER.length
}

private const val NICK_MODE_ORDER = "~&@%+"
