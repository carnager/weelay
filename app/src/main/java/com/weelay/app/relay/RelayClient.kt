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

    private val _syncEvents = MutableSharedFlow<SyncEvent>(replay = 0, extraBufferCapacity = 64)
    val syncEvents: SharedFlow<SyncEvent> = _syncEvents.asSharedFlow()

    private val _errorMessage = MutableStateFlow<String?>(null)
    val errorMessage: StateFlow<String?> = _errorMessage.asStateFlow()

    // Full name of the buffer currently on screen (null when none). Used so the buffer list can
    // suppress/reset unread counts for the buffer the user is actively viewing.
    private val _activeBuffer = MutableStateFlow<String?>(null)
    val activeBuffer: StateFlow<String?> = _activeBuffer.asStateFlow()

    fun setActiveBuffer(fullName: String?) {
        _activeBuffer.value = fullName
    }

    // Collected highlight lines: bufferFullName to RelayLine.
    // Only ever mutated from the single message-processing consumer (see incomingMessages),
    // so no synchronization is needed. Bounded to avoid unbounded growth over long sessions.
    private val maxHighlightLines = 500
    private val _highlightLines = mutableListOf<Pair<String, RelayLine>>()
    val highlightLines: List<Pair<String, RelayLine>> get() = _highlightLines.toList()

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
    private val maxReconnectAttempts: Int = 12
    private var pingJob: Job? = null
    private var isReconnecting: Boolean = false

    // Dead-connection watchdog. We ping periodically and track when we last heard anything from
    // the server; if a healthy connection goes silent past the timeout the socket is half-open,
    // so we force a reconnect rather than waiting for a write to eventually fail.
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

        val scheme = if (useSsl) "wss" else "ws"
        val uri = URI("$scheme://$host:$port/api")

        // Auth via Sec-WebSocket-Protocol using base64url-encoded "plain:<password>"
        val credentials = "plain:$password"
        val encoded = Base64.encodeToString(
            credentials.toByteArray(),
            Base64.URL_SAFE or Base64.NO_WRAP or Base64.NO_PADDING
        )
        val authProtocol = "base64url.bearer.authorization.weechat.$encoded"

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
                if (explicitDisconnect || fatalError) return
                if (wsClient == null) return
                scheduleReconnect()
            }

            override fun onError(ex: Exception) {
                Log.e("RelayClient", "onError: ${ex.message} state=${_connectionState.value} explicit=$explicitDisconnect", ex)
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
                val sslContext = SSLContext.getInstance("TLS")
                sslContext.init(null, null, null)
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

    private fun scheduleReconnect() {
        if (explicitDisconnect || fatalError) return
        if (lastHost.isEmpty()) return
        if (isReconnecting) return // prevent re-entrancy from onClose firing during reconnect

        if (reconnectAttempt >= maxReconnectAttempts) {
            failFatally("Unable to reconnect after $maxReconnectAttempts attempts")
            return
        }

        isReconnecting = true
        _connectionState.value = ConnectionState.RECONNECTING
        stopPing()

        reconnectJob?.cancel()
        reconnectJob = scope.launch {
            reconnectAttempt++
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
        if (explicitDisconnect || fatalError) return
        if (lastHost.isEmpty()) return

        val client = wsClient
        if (client == null || !client.isOpen) {
            Log.i("RelayClient", "checkConnectionOnResume: connection dead, reconnecting immediately")
            // Cancel any existing reconnect and force an immediate one
            reconnectJob?.cancel()
            isReconnecting = false
            reconnectAttempt = 0
            _connectionState.value = ConnectionState.RECONNECTING
            stopPing()
            val oldClient = wsClient
            wsClient = null
            try { oldClient?.closeConnection(1000, "resume-reconnect") } catch (_: Exception) {}
            scope.launch {
                connectInternal(lastHost, lastPort, lastPassword, lastUseSsl, isReconnect = true)
            }
        }
    }

    fun disconnect() {
        explicitDisconnect = true
        isReconnecting = false
        cancelReconnect()
        stopPing()
        disconnectInternal()
        _connectionState.value = ConnectionState.DISCONNECTED
        _buffers.value = emptyList()
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

    private suspend fun handleMessage(text: String) {
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

            when (bodyType) {
                "buffers" -> {
                    val bufferList = json.decodeFromJsonElement(
                        kotlinx.serialization.builtins.ListSerializer(RelayBuffer.serializer()),
                        body!!
                    )
                    _buffers.value = bufferList
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
                    val lineList = json.decodeFromJsonElement(
                        kotlinx.serialization.builtins.ListSerializer(RelayLine.serializer()),
                        body!!
                    )
                    val request = obj["request"]?.jsonPrimitive?.content ?: ""
                    val bufferName = extractBufferName(request)
                    if (bufferName != null) {
                        // body_type "lines" is fetched backlog — never notify on these.
                        _lines.emit(LinesUpdate(bufferName, lineList, isHistory = true))
                        lineList.filter { it.highlight && it.displayed && it.message.isNotBlank() }
                            .forEach { addHighlight(bufferName to it) }
                    }
                }
                "line" -> {
                    val line = json.decodeFromJsonElement(RelayLine.serializer(), body!!)
                    // Sync events have buffer_id at top level
                    val topBufferId = obj["buffer_id"]?.let {
                        try { it.jsonPrimitive.content.toBigDecimal().toLong() } catch (e: Exception) { null }
                    }
                    val bid = if (line.bufferId != 0L) line.bufferId else topBufferId
                    val bufferName = _buffers.value.find { it.id == bid }?.fullName
                    if (bufferName != null) {
                        // body_type "line" is a live sync event — eligible for notifications.
                        _lines.emit(LinesUpdate(bufferName, listOf(line), isHistory = false))
                        if (line.highlight && line.displayed && line.message.isNotBlank()) {
                            addHighlight(bufferName to line)
                        }
                    }
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
                else -> {
                    if (bodyType != null) {
                        _syncEvents.emit(SyncEvent(type = bodyType, body = body))
                        when (bodyType) {
                            "buffer_opened", "buffer_closed", "buffer_renamed" -> {
                                requestBuffers()
                            }
                        }
                    }
                }
            }
        } catch (e: Exception) {
            _errorMessage.value = "Parse error: ${e.message}"
        }
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
