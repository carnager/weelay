package com.weelay.app.relay

import android.util.Base64
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import org.java_websocket.client.WebSocketClient
import org.java_websocket.drafts.Draft_6455
import org.java_websocket.handshake.ServerHandshake
import org.java_websocket.protocols.Protocol
import android.util.Log
import java.net.URI
import javax.inject.Inject
import javax.inject.Singleton
import javax.net.ssl.SSLContext

/** A server certificate that failed verification, offered to the user to trust. */
data class UntrustedCertificate(
    val host: String,
    val port: Int,
    val sha256: String,
    val subject: String,
    val issuer: String,
    val notAfter: java.util.Date,
    /** True if a different certificate had been trusted for this server before. */
    val replacesPinned: Boolean
)

enum class ConnectionState {
    DISCONNECTED,
    CONNECTING,
    CONNECTED,
    RECONNECTING,
    ERROR
}

@Singleton
class RelayClient @Inject constructor() {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    private val json = Json {
        ignoreUnknownKeys = true
        isLenient = true
        encodeDefaults = true
    }

    private val _connectionState = MutableStateFlow(ConnectionState.DISCONNECTED)
    val connectionState: StateFlow<ConnectionState> = _connectionState.asStateFlow()

    private val _buffers = MutableStateFlow<List<RelayBuffer>>(emptyList())
    val buffers: StateFlow<List<RelayBuffer>> = _buffers.asStateFlow()

    private val _lines = MutableSharedFlow<LinesUpdate>(replay = 0, extraBufferCapacity = 64)
    val lines: SharedFlow<LinesUpdate> = _lines.asSharedFlow()

    private val _nicklist = MutableSharedFlow<Pair<String, List<RelayNicklistEntry>>>(replay = 0)
    val nicklist: SharedFlow<Pair<String, List<RelayNicklistEntry>>> = _nicklist.asSharedFlow()

    /**
     * SHA-256 fingerprint of a certificate the user chose to trust for this server, accepted in
     * addition to the system CAs. Set by whoever starts the session, before [connect].
     */
    @Volatile var pinnedCertSha256: String? = null

    private val _untrustedCertificate = MutableStateFlow<UntrustedCertificate?>(null)
    val untrustedCertificate: StateFlow<UntrustedCertificate?> = _untrustedCertificate.asStateFlow()

    fun dismissUntrustedCertificate() {
        _untrustedCertificate.value = null
    }

    private val _errorMessage = MutableStateFlow<String?>(null)
    val errorMessage: StateFlow<String?> = _errorMessage.asStateFlow()

    // Unread state, keyed by buffer id. Seeded from WeeChat's hotlist on connect and then kept up
    // to date locally, because the relay api has no hotlist push events to subscribe to.
    private val _hotlist = MutableStateFlow<Map<Long, RelayHotlistEntry>>(emptyMap())
    val hotlist: StateFlow<Map<Long, RelayHotlistEntry>> = _hotlist.asStateFlow()

    // Full name of the buffer currently on screen (null when none). Used so the buffer list can
    // suppress/reset unread counts for the buffer the user is actively viewing.
    private val _activeBuffer = MutableStateFlow<String?>(null)
    val activeBuffer: StateFlow<String?> = _activeBuffer.asStateFlow()

    fun setActiveBuffer(fullName: String?) {
        _activeBuffer.value = fullName
        // Opening a buffer is reading it — tell WeeChat, so the desktop client agrees.
        if (fullName != null) {
            markBufferRead(fullName)
        }
    }

    // Collected highlight lines: bufferFullName to RelayLine.
    // Only ever mutated from the single message-processing consumer (see incomingMessages),
    // so no synchronization is needed. Bounded to avoid unbounded growth over long sessions.
    private val maxHighlightLines = 500
    private val _highlightLines = mutableListOf<Pair<String, RelayLine>>()
    val highlightLines: List<Pair<String, RelayLine>> get() = _highlightLines.toList()

    // Lines whose buffer wasn't in _buffers yet, held for replay by flushPendingLines. Bounded so
    // a persistently unknown buffer id can't grow this without limit.
    private val maxPendingLines = 100
    private val pendingLines = mutableListOf<Pair<Long, RelayLine>>()

    private fun addHighlight(entry: Pair<String, RelayLine>) {
        _highlightLines.add(entry)
        if (_highlightLines.size > maxHighlightLines) {
            _highlightLines.subList(0, _highlightLines.size - maxHighlightLines).clear()
        }
    }

    private var wsClient: WebSocketClient? = null

    // Stored connection parameters for reconnection
    private var lastHost: String = ""
    private var lastPort: Int = 0
    private var lastPassword: String = ""
    private var lastUseSsl: Boolean = false
    private var explicitDisconnect: Boolean = false
    // Set when a non-recoverable failure (e.g. bad password) occurs. Stops the reconnect loop
    // so the UI surfaces ERROR instead of spinning in RECONNECTING forever.
    private var fatalError: Boolean = false
    private var reconnectJob: Job? = null
    private var reconnectAttempt: Int = 0
    private val maxReconnectDelay: Long = 30_000L
    private var pingJob: Job? = null
    private var isReconnecting: Boolean = false

    /**
     * True once [connect] has been called with usable parameters and the session hasn't been
     * explicitly torn down. Lets callers (the service, the network monitor) tell "we should be
     * connected but currently aren't" apart from "there is no session at all".
     */
    val hasSession: Boolean get() = lastHost.isNotEmpty() && !explicitDisconnect && !fatalError

    // Dead-connection watchdog. We ping periodically and track when we last heard anything from
    // the server; if a healthy connection goes silent past the timeout the socket is half-open,
    // so we force a reconnect rather than waiting for a write to eventually fail.
    // How long a connect attempt is left alone before an external trigger may restart it.
    private val connectAttemptGraceMs: Long = 15_000L
    @Volatile private var connectAttemptStartedAt: Long = 0L

    private val pingIntervalMs: Long = 20_000L
    private val activityTimeoutMs: Long = 60_000L // > 2 ping intervals; healthy pongs reset it
    @Volatile private var lastActivityMs: Long = 0L

    // Incoming WebSocket frames are processed strictly in order by a single consumer coroutine.
    // Launching a coroutine per frame (the previous approach) raced _buffers/_highlightLines.
    private val incomingMessages = Channel<String>(capacity = Channel.UNLIMITED)

    init {
        scope.launch {
            for (message in incomingMessages) {
                handleMessage(message)
            }
        }
    }

    fun connect(host: String, port: Int, password: String, useSsl: Boolean = false) {
        explicitDisconnect = false
        fatalError = false
        cancelReconnect()
        disconnectInternal()

        lastHost = host
        lastPort = port
        lastPassword = password
        lastUseSsl = useSsl
        reconnectAttempt = 0

        connectInternal(host, port, password, useSsl, isReconnect = false)
    }

    private fun connectInternal(host: String, port: Int, password: String, useSsl: Boolean, isReconnect: Boolean) {
        if (!isReconnect) {
            _connectionState.value = ConnectionState.CONNECTING
        }
        _errorMessage.value = null
        connectAttemptStartedAt = System.currentTimeMillis()

        val scheme = if (useSsl) "wss" else "ws"
        val uri = URI("$scheme://$host:$port/api")

        // Auth via Sec-WebSocket-Protocol using base64url-encoded "plain:<password>"
        val credentials = "plain:$password"
        val encoded = Base64.encodeToString(
            credentials.toByteArray(),
            Base64.URL_SAFE or Base64.NO_WRAP or Base64.NO_PADDING
        )
        val authProtocol = "base64url.bearer.authorization.weechat.$encoded"

        // Set by the trust manager during the TLS handshake if the server's certificate is refused.
        var rejectedCert: java.security.cert.X509Certificate? = null

        val draft = Draft_6455(
            emptyList(),
            listOf(Protocol("api.weechat"), Protocol(authProtocol))
        )

        wsClient = object : WebSocketClient(uri, draft) {
            override fun onOpen(handshake: ServerHandshake) {
                reconnectAttempt = 0
                isReconnecting = false
                lastActivityMs = System.currentTimeMillis()
                _connectionState.value = ConnectionState.CONNECTED
                enableSync()
                requestBuffers()
                requestHotlist()
                startPing()
            }

            override fun onMessage(message: String) {
                // Any frame (including ping responses) counts as the connection being alive.
                lastActivityMs = System.currentTimeMillis()
                // Hand off to the single ordered consumer; never block the WS read thread.
                incomingMessages.trySend(message)
            }

            override fun onClose(code: Int, reason: String, remote: Boolean) {
                Log.w("RelayClient", "onClose: code=$code reason=$reason remote=$remote explicit=$explicitDisconnect state=${_connectionState.value}")
                rejectedCert?.let { rejectCertificate(it, host, port); return }
                if (explicitDisconnect || fatalError) return
                if (wsClient == null) return
                scheduleReconnect()
            }

            override fun onError(ex: Exception) {
                Log.e("RelayClient", "onError: ${ex.message} state=${_connectionState.value} explicit=$explicitDisconnect", ex)
                // An untrusted certificate won't become trusted by retrying; stop and ask instead.
                rejectedCert?.let { rejectCertificate(it, host, port); return }
                if (explicitDisconnect) {
                    _connectionState.value = ConnectionState.ERROR
                    _errorMessage.value = ex.message ?: "Connection failed"
                    return
                }
                if (fatalError) return
                // Try to reconnect unless it was an explicit disconnect or a fatal error
                scheduleReconnect()
            }
        }.apply {
            connectionLostTimeout = 0 // Disabled; we use our own API-level ping
            // Happy-eyeballs-style resolution: prefer a reachable IPv4 address. Without this,
            // a host with both A and AAAA records whose relay only listens on IPv4 fails with
            // ECONNREFUSED because Android picks the IPv6 address first.
            setDnsResolver { u -> resolvePreferredAddress(u, port) }
            if (useSsl) {
                val trustManager = PinningTrustManager(
                    PinningTrustManager.systemTrustManager(),
                    pinnedCertSha256
                ) { rejectedCert = it }
                val sslContext = SSLContext.getInstance("TLS")
                sslContext.init(null, arrayOf(trustManager), null)
                setSocketFactory(sslContext.socketFactory)
            }
            connect()
        }
    }

    /**
     * Resolve [uri]'s host to the address most likely to accept a connection: try each resolved
     * address (IPv4 first) with a short TCP probe and return the first that connects. Falls back
     * to the first resolved address so the real connect surfaces any error normally.
     *
     * Runs on the WebSocket connect thread, so the blocking probes are acceptable here.
     */
    private fun resolvePreferredAddress(uri: URI, port: Int): java.net.InetAddress {
        val host = uri.host
        val addresses = java.net.InetAddress.getAllByName(host)
            // IPv4 first (false sorts before true)
            .sortedBy { it !is java.net.Inet4Address }
        for (addr in addresses) {
            try {
                java.net.Socket().use { probe ->
                    probe.connect(java.net.InetSocketAddress(addr, port), 1500)
                }
                Log.i("RelayClient", "resolved $host:$port -> ${addr.hostAddress} (reachable)")
                return addr
            } catch (_: Exception) {
                // unreachable on this address — try the next
            }
        }
        val fallback = addresses.firstOrNull() ?: java.net.InetAddress.getByName(host)
        Log.w("RelayClient", "no reachable address for $host:$port; falling back to ${fallback.hostAddress}")
        return fallback
    }

    /** Stop on a refused certificate and publish it so the user can decide whether to trust it. */
    private fun rejectCertificate(cert: java.security.cert.X509Certificate, host: String, port: Int) {
        if (fatalError) return
        _untrustedCertificate.value = UntrustedCertificate(
            host = host,
            port = port,
            sha256 = PinningTrustManager.fingerprint(cert),
            subject = cert.subjectX500Principal.name,
            issuer = cert.issuerX500Principal.name,
            notAfter = cert.notAfter,
            replacesPinned = pinnedCertSha256 != null
        )
        failFatally("The relay's TLS certificate isn't trusted")
    }

    /**
     * Transition to a terminal error state and stop the reconnect loop. Used for
     * non-recoverable failures such as authentication rejection, where retrying with
     * the same credentials would loop forever.
     */
    private fun failFatally(message: String) {
        fatalError = true
        isReconnecting = false
        cancelReconnect()
        stopPing()
        _errorMessage.value = message
        _connectionState.value = ConnectionState.ERROR
    }

    /**
     * Reconnect immediately, discarding any pending backoff and resetting the attempt counter.
     *
     * Used when something tells us the network situation just changed for the better — the app
     * came back to the foreground, or a usable network became available. Retrying right away is
     * far better than sitting out the remainder of a 30s backoff on a link that now works.
     *
     * No-op when there is no session, or when the socket is already open.
     */
    fun reconnectNow(reason: String) {
        if (!hasSession) return
        val client = wsClient
        if (client != null && client.isOpen) return

        // Don't preempt a connect that is already in flight — tearing it down and starting over
        // just doubles the work and delays the result. After the grace period a stalled attempt is
        // fair game, since nothing else would ever rescue it.
        val attemptAge = System.currentTimeMillis() - connectAttemptStartedAt
        if (client != null && attemptAge < connectAttemptGraceMs) {
            Log.i("RelayClient", "reconnectNow($reason) ignored; attempt in flight (${attemptAge}ms)")
            return
        }

        Log.i("RelayClient", "reconnectNow($reason)")
        cancelReconnect()
        isReconnecting = false
        reconnectAttempt = 0
        stopPing()
        _connectionState.value = ConnectionState.RECONNECTING

        val old = wsClient
        wsClient = null // null first so the resulting onClose is ignored
        try { old?.closeConnection(1000, reason) } catch (_: Exception) {}

        scope.launch {
            connectInternal(lastHost, lastPort, lastPassword, lastUseSsl, isReconnect = true)
        }
    }

    private fun scheduleReconnect() {
        if (explicitDisconnect || fatalError) return
        if (lastHost.isEmpty()) return
        if (isReconnecting) return // prevent re-entrancy from onClose firing during reconnect

        isReconnecting = true
        _connectionState.value = ConnectionState.RECONNECTING
        stopPing()

        reconnectJob?.cancel()
        reconnectJob = scope.launch {
            reconnectAttempt++
            // Exponential backoff capped at maxReconnectDelay. There is deliberately no attempt
            // limit: on a phone the connection is expected to drop for hours (doze, no signal),
            // and giving up permanently means the app is silently dead until reopened by hand.
            val delayMs = minOf(
                1000L * (1L shl minOf(reconnectAttempt - 1, 14)),
                maxReconnectDelay
            )
            Log.i("RelayClient", "scheduleReconnect: attempt=$reconnectAttempt delay=${delayMs}ms")
            delay(delayMs)

            if (!explicitDisconnect) {
                val oldClient = wsClient
                wsClient = null
                try { oldClient?.closeConnection(1000, "reconnecting") } catch (_: Exception) {}
                Log.i("RelayClient", "reconnecting now...")
                connectInternal(lastHost, lastPort, lastPassword, lastUseSsl, isReconnect = true)
            }
            isReconnecting = false
        }
    }

    private fun cancelReconnect() {
        reconnectJob?.cancel()
        reconnectJob = null
    }

    private fun startPing() {
        pingJob?.cancel()
        pingJob = scope.launch {
            while (true) {
                delay(pingIntervalMs)
                val client = wsClient
                if (client == null || !client.isOpen) break

                // Half-open detection: a live connection answers our pings, refreshing
                // lastActivityMs. Prolonged silence means the socket is dead — reconnect.
                if (lastActivityMs != 0L &&
                    System.currentTimeMillis() - lastActivityMs > activityTimeoutMs
                ) {
                    Log.w("RelayClient", "no activity for >${activityTimeoutMs}ms; forcing reconnect")
                    forceReconnect()
                    break
                }

                val msg = buildJsonObject {
                    put("request", "POST /api/ping")
                }
                try {
                    client.send(msg.toString())
                } catch (e: Exception) {
                    Log.w("RelayClient", "ping send failed; forcing reconnect", e)
                    forceReconnect()
                    break
                }
            }
        }
    }

    /** Tear down a presumed-dead socket and schedule a reconnect. */
    private fun forceReconnect() {
        if (explicitDisconnect || fatalError) return
        val old = wsClient
        wsClient = null // null first so the resulting onClose is ignored
        try { old?.closeConnection(1000, "watchdog") } catch (_: Exception) {}
        scheduleReconnect()
    }

    private fun stopPing() {
        pingJob?.cancel()
        pingJob = null
    }

    /**
     * Called when app returns to foreground. Checks if the WebSocket is still alive
     * and reconnects immediately if not.
     */
    fun checkConnectionOnResume() {
        reconnectNow("resume")
    }

    fun disconnect() {
        explicitDisconnect = true
        isReconnecting = false
        cancelReconnect()
        stopPing()
        disconnectInternal()
        _connectionState.value = ConnectionState.DISCONNECTED
        _buffers.value = emptyList()
        _hotlist.value = emptyMap()
    }

    private fun disconnectInternal() {
        stopPing()
        wsClient?.close()
        wsClient = null
    }

    private fun enableSync() {
        val msg = buildJsonObject {
            put("request", "POST /api/sync")
            put("body", buildJsonObject {
                put("sync", true)
                put("nicks", true)
                put("colors", "strip")
            })
        }
        wsClient?.send(msg.toString())
    }

    fun requestBuffers() {
        sendRequest("GET /api/buffers?lines=-50&colors=strip")
    }

    fun requestHotlist() {
        sendRequest("GET /api/hotlist")
    }

    /**
     * Mark [bufferFullName] as read in WeeChat itself, so the desktop client's hotlist and read
     * marker agree with what has been read on the phone.
     *
     * The relay api has no read endpoint, so this goes through /api/input. Commands sent that way
     * run with the named buffer as their context, which is what makes the "current buffer" form of
     * set_unread work for a buffer that isn't the relay's active one.
     */
    fun markBufferRead(bufferFullName: String) {
        val bufferId = _buffers.value.find { it.fullName == bufferFullName }?.id
        sendInput(bufferFullName, "/buffer set hotlist -1")
        sendInput(bufferFullName, "/input set_unread_current_buffer")
        if (bufferId != null) {
            _hotlist.value = _hotlist.value - bufferId
        }
    }

    /**
     * Fold a freshly arrived line into the local hotlist, mirroring what WeeChat does on its side.
     * Skipped for the buffer on screen and for the user's own messages, matching WeeChat's own
     * behaviour of not adding the current buffer to the hotlist.
     */
    private fun updateHotlistForLine(bufferId: Long, line: RelayLine, bufferFullName: String) {
        if (bufferId == 0L) return
        if (_activeBuffer.value == bufferFullName) return
        if (line.tags.any { it == "self_msg" }) return
        if (!MessageFormat.isRegularMessage(line)) return

        val level = if (line.highlight) {
            RelayHotlistEntry.LEVEL_HIGHLIGHT
        } else {
            RelayHotlistEntry.LEVEL_MESSAGE
        }

        val existing = _hotlist.value[bufferId]
        val counts = MutableList(4) { index -> existing?.count?.getOrElse(index) { 0 } ?: 0 }
        counts[level] = counts[level] + 1

        _hotlist.value = _hotlist.value + (bufferId to RelayHotlistEntry(
            priority = maxOf(existing?.priority ?: 0, level),
            date = line.date,
            bufferId = bufferId,
            count = counts
        ))
    }

    fun requestLines(bufferFullName: String, count: Int = 100) {
        val encoded = java.net.URLEncoder.encode(bufferFullName, "UTF-8")
        sendRequest("GET /api/buffers/$encoded/lines?lines=-$count&colors=strip")
    }

    fun requestNicklist(bufferFullName: String) {
        val encoded = java.net.URLEncoder.encode(bufferFullName, "UTF-8")
        sendRequest("GET /api/buffers/$encoded/nicks")
    }

    /** Returns true if the message was handed to an open socket, false if the connection was down. */
    fun sendInput(bufferFullName: String, text: String): Boolean {
        val client = wsClient ?: return false
        if (!client.isOpen) return false
        val msg = buildJsonObject {
            put("request", "POST /api/input")
            put("body", buildJsonObject {
                put("buffer_name", bufferFullName)
                put("command", text)
            })
        }
        return try {
            client.send(msg.toString())
            true
        } catch (e: Exception) {
            false
        }
    }

    private fun sendRequest(requestPath: String) {
        val client = wsClient ?: return
        if (!client.isOpen) return
        val msg = buildJsonObject {
            put("request", requestPath)
        }
        client.send(msg.toString())
    }

    /**
     * Process one incoming frame.
     *
     * Deliberately free of any socket or Android dependency — outbound requests go through
     * [sendRequest], which is a no-op while there is no open connection — so the protocol layer
     * can be driven directly from JVM unit tests. Internal rather than private for that reason.
     */
    internal suspend fun handleMessage(text: String) {
        try {
            val jsonElement = json.parseToJsonElement(text)
            if (jsonElement !is JsonObject) return

            val obj = jsonElement.jsonObject
            val code = obj["code"]?.jsonPrimitive?.int
            val bodyType = obj["body_type"]?.jsonPrimitive?.content
            if (code != null && code >= 400) {
                val errorBody = obj["body"]?.jsonObject
                val errorMsg = errorBody?.get("error")?.jsonPrimitive?.content
                    ?: "Error $code"
                // 401/403 mean the credentials are wrong — reconnecting won't help.
                if (code == 401 || code == 403) {
                    failFatally(errorMsg)
                } else {
                    _errorMessage.value = errorMsg
                }
                return
            }

            val body = obj["body"]

            // Pushed sync messages carry an "event_name"; responses to our own requests don't.
            // Some events can only be told apart by that name — buffer_closed has a null body_type,
            // and buffer_opened/renamed share body_type "buffer" with ordinary buffer updates — so
            // they have to be routed here, before the body_type dispatch below.
            val eventName = obj["event_name"]?.jsonPrimitive?.content
            if (eventName != null && handleEvent(eventName, obj, body)) return

            when (bodyType) {
                "buffers" -> {
                    val bufferList = json.decodeFromJsonElement(
                        kotlinx.serialization.builtins.ListSerializer(RelayBuffer.serializer()),
                        body!!
                    )
                    _buffers.value = bufferList
                    flushPendingLines()
                }
                "buffer" -> {
                    val buffer = json.decodeFromJsonElement(RelayBuffer.serializer(), body!!)
                    _buffers.value = _buffers.value.map {
                        if (it.id == buffer.id) {
                            // Preserve lines from original if sync update has none
                            if (buffer.lines.isEmpty() && it.lines.isNotEmpty()) {
                                buffer.copy(lines = it.lines)
                            } else {
                                buffer
                            }
                        } else it
                    }
                }
                "lines" -> {
                    val request = obj["request"]?.jsonPrimitive?.content ?: ""
                    val bufferName = extractBufferName(request)
                    // Line objects carry no buffer_id of their own — the relay api only names the
                    // buffer in the request path — so stamp it on here for every consumer.
                    val bufferId = _buffers.value.find { it.fullName == bufferName }?.id ?: 0L
                    val lineList = json.decodeFromJsonElement(
                        kotlinx.serialization.builtins.ListSerializer(RelayLine.serializer()),
                        body!!
                    ).map { if (it.bufferId == 0L) it.copy(bufferId = bufferId) else it }
                    if (bufferName != null) {
                        // body_type "lines" is fetched backlog — never notify on these.
                        _lines.emit(LinesUpdate(bufferName, lineList, isHistory = true))
                        lineList.filter { it.highlight && it.displayed && it.message.isNotBlank() }
                            .forEach { addHighlight(bufferName to it) }
                    }
                }
                "line" -> {
                    // Sync events name the buffer at the top level, not in the line itself; copy
                    // it onto the line so consumers can rely on RelayLine.bufferId.
                    val topBufferId = obj["buffer_id"]?.let {
                        try { it.jsonPrimitive.content.toBigDecimal().toLong() } catch (e: Exception) { null }
                    }
                    val decoded = json.decodeFromJsonElement(RelayLine.serializer(), body!!)
                    val line = if (decoded.bufferId == 0L && topBufferId != null) {
                        decoded.copy(bufferId = topBufferId)
                    } else decoded
                    val bid = if (line.bufferId != 0L) line.bufferId else topBufferId
                    val bufferName = _buffers.value.find { it.id == bid }?.fullName
                    if (bufferName == null) {
                        // The buffer isn't known yet — a line can beat the buffer list for a
                        // channel that was just joined. Hold it and replay once the list catches
                        // up; dropping it here cost the message its notification entirely.
                        if (bid != null && bid != 0L) {
                            pendingLines.add(bid to line)
                            if (pendingLines.size > maxPendingLines) pendingLines.removeAt(0)
                            requestBuffers()
                        }
                    } else {
                        // body_type "line" is a live sync event — eligible for notifications.
                        _lines.emit(LinesUpdate(bufferName, listOf(line), isHistory = false))
                        if (line.highlight && line.displayed && line.message.isNotBlank()) {
                            addHighlight(bufferName to line)
                        }
                        if (bid != null) {
                            updateHotlistForLine(bid, line, bufferName)
                        }
                    }
                }
                "hotlist" -> {
                    val entries = json.decodeFromJsonElement(
                        kotlinx.serialization.builtins.ListSerializer(RelayHotlistEntry.serializer()),
                        body!!
                    )
                    _hotlist.value = entries.associateBy { it.bufferId }
                }
                "nicks", "nick_group" -> {
                    val rootGroup = json.decodeFromJsonElement(RelayNicklistGroup.serializer(), body!!)
                    val flatNicks = flattenNicklist(rootGroup)
                    val request2 = obj["request"]?.jsonPrimitive?.content ?: ""
                    val bufferName = extractBufferName(request2)
                    if (bufferName != null) {
                        _nicklist.emit(bufferName to flatNicks)
                    }
                }
                else -> Unit
            }
        } catch (e: Exception) {
            _errorMessage.value = "Parse error: ${e.message}"
        }
    }

    /**
     * Handle a pushed sync event by name. Returns true if the event was fully handled and the
     * body_type dispatch should be skipped.
     */
    private suspend fun handleEvent(
        eventName: String,
        obj: JsonObject,
        body: JsonElement?
    ): Boolean = when (eventName) {
        // Apply the new buffer from the event body immediately, before the requestBuffers()
        // round trip below completes. Until this buffer is known, any line arriving for it can't
        // be matched to a buffer name — and a highlight in a channel you just joined arrives in
        // exactly that window.
        "buffer_opened" -> {
            if (body != null) {
                try {
                    val buffer = json.decodeFromJsonElement(RelayBuffer.serializer(), body)
                    if (_buffers.value.none { it.id == buffer.id }) {
                        _buffers.value = _buffers.value + buffer
                    }
                    flushPendingLines()
                } catch (_: Exception) {
                    // Fall back to the refetch below.
                }
            }
            requestBuffers()
            requestHotlist()
            true
        }

        // The set of buffers changed. Refetching is cheaper to get right than patching the list
        // in place, and it also picks up the new ordering.
        "buffer_closed", "buffer_renamed", "buffer_moved",
        "buffer_merged", "buffer_unmerged", "buffer_hidden", "buffer_unhidden" -> {
            requestBuffers()
            // Unread counts are keyed by buffer id, so they need re-syncing alongside.
            requestHotlist()
            true
        }

        // Nicks come and go constantly; only the buffer actually on screen is worth refreshing.
        "nicklist_nick_added", "nicklist_nick_removing", "nicklist_nick_changed",
        "nicklist_group_added", "nicklist_group_removing" -> {
            val bufferId = obj["buffer_id"]?.let {
                try { it.jsonPrimitive.content.toBigDecimal().toLong() } catch (_: Exception) { null }
            }
            val bufferName = _buffers.value.find { it.id == bufferId }?.fullName
            if (bufferName != null && bufferName == _activeBuffer.value) {
                requestNicklist(bufferName)
            }
            true
        }

        // Anything else (buffer_line_added, buffer_title_changed, …) is described well enough by
        // its body_type; let the dispatch below deal with it.
        else -> false
    }

    /**
     * Re-deliver lines that arrived before their buffer was known, now that the buffer list may
     * have caught up. Anything still unresolved stays queued for the next attempt.
     */
    private suspend fun flushPendingLines() {
        if (pendingLines.isEmpty()) return

        val unresolved = mutableListOf<Pair<Long, RelayLine>>()
        for ((bufferId, line) in pendingLines) {
            val bufferName = _buffers.value.find { it.id == bufferId }?.fullName
            if (bufferName == null) {
                unresolved.add(bufferId to line)
                continue
            }
            _lines.emit(LinesUpdate(bufferName, listOf(line), isHistory = false))
            if (line.highlight && line.displayed && line.message.isNotBlank()) {
                addHighlight(bufferName to line)
            }
            updateHotlistForLine(bufferId, line, bufferName)
        }
        pendingLines.clear()
        pendingLines.addAll(unresolved)
    }

    private fun flattenNicklist(group: RelayNicklistGroup): List<RelayNicklistEntry> {
        val result = mutableListOf<RelayNicklistEntry>()
        for (nick in group.nicks) {
            result.add(RelayNicklistEntry(
                prefix = nick.prefix,
                name = nick.name,
                visible = nick.visible
            ))
        }
        for (subGroup in group.groups) {
            result.addAll(flattenNicklist(subGroup))
        }
        return result
    }

    private fun extractBufferName(request: String): String? {
        val match = Regex("/api/buffers/([^/]+)/(lines|nicks)").find(request)
        val encoded = match?.groupValues?.get(1) ?: return null
        return java.net.URLDecoder.decode(encoded, "UTF-8")
    }
}
