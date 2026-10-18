package com.weelay.app.service

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat
import androidx.core.app.Person
import androidx.core.app.RemoteInput
import androidx.core.content.ContextCompat
import com.weelay.app.MainActivity
import com.weelay.app.R
import com.weelay.app.data.ConnectionPrefs
import com.weelay.app.relay.ConnectionState
import com.weelay.app.relay.MessageFormat
import com.weelay.app.relay.NetworkMonitor
import com.weelay.app.relay.RelayClient
import com.weelay.app.relay.RelayLine
import java.time.Instant
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import javax.inject.Inject

@AndroidEntryPoint
class RelayService : Service() {

    @Inject
    lateinit var relayClient: RelayClient

    @Inject
    lateinit var networkMonitor: NetworkMonitor

    @Inject
    lateinit var connectionPrefs: ConnectionPrefs

    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    companion object {
        const val CHANNEL_ID = "weelay_relay_channel"
        const val MESSAGE_CHANNEL_ID = "weelay_messages_channel"
        const val MESSAGE_GROUP_KEY = "com.weelay.app.MESSAGES"
        const val NOTIFICATION_ID = 1
        const val SUMMARY_NOTIFICATION_ID = 2
        const val ERROR_NOTIFICATION_ID = 3
        private const val MESSAGE_NOTIFICATION_ID_BASE = 1000
        const val ACTION_CONNECT = "com.weelay.app.CONNECT"
        const val ACTION_DISCONNECT = "com.weelay.app.DISCONNECT"
        const val ACTION_REPLY = "com.weelay.app.REPLY"
        const val ACTION_MARK_READ = "com.weelay.app.MARK_READ"
        const val EXTRA_HOST = "host"
        const val EXTRA_PORT = "port"
        const val EXTRA_PASSWORD = "password"
        const val EXTRA_SSL = "ssl"
        const val EXTRA_BUFFER = "buffer"
        const val KEY_REPLY_TEXT = "reply_text"

        /** Messages retained per buffer for MessagingStyle. */
        private const val MAX_MESSAGES_PER_CONVERSATION = 8

        /** Keeps mark-read request codes from colliding with reply request codes. */
        private const val MARK_READ_REQUEST_OFFSET = 100_000

        /**
         * Open (or re-open) the relay connection, owned by this foreground service.
         *
         * Always go through here rather than calling [RelayClient.connect] directly: without a
         * foreground service the process is frozen or killed shortly after the app is
         * backgrounded, which silently drops the WebSocket and stops notifications.
         */
        fun start(context: Context, host: String, port: Int, password: String, useSsl: Boolean) {
            val intent = Intent(context, RelayService::class.java).apply {
                action = ACTION_CONNECT
                putExtra(EXTRA_HOST, host)
                putExtra(EXTRA_PORT, port)
                putExtra(EXTRA_PASSWORD, password)
                putExtra(EXTRA_SSL, useSsl)
            }
            ContextCompat.startForegroundService(context, intent)
        }

        /** Disconnect and tear the service down. */
        fun stop(context: Context) {
            val intent = Intent(context, RelayService::class.java).apply {
                action = ACTION_DISCONNECT
            }
            ContextCompat.startForegroundService(context, intent)
        }
    }

    /** Maps buffer full name to a stable notification ID */
    private val bufferNotificationIds = mutableMapOf<String, Int>()
    private var nextNotificationId = MESSAGE_NOTIFICATION_ID_BASE

    /**
     * Recent messages per buffer, so each notification can be rebuilt as a full conversation
     * rather than a single line that replaces the previous one.
     */
    private val conversations = mutableMapOf<String, MutableList<NotificationCompat.MessagingStyle.Message>>()

    private val selfPerson: Person by lazy {
        Person.Builder().setName("You").setKey("weelay:self").build()
    }

    // Per-connect collectors; cancelled and relaunched on each ACTION_CONNECT so repeated
    // connect intents don't stack duplicate collectors (which caused duplicate notifications).
    private var stateJob: Job? = null
    private var messagesJob: Job? = null
    private var activeBufferJob: Job? = null

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
        createMessageNotificationChannel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        // Go foreground before anything else. A service started via startForegroundService(), or
        // restarted by the system with a null intent, must call startForeground() promptly or the
        // platform kills the process — so this must not sit behind an action check or an early
        // return for missing extras.
        startForeground(NOTIFICATION_ID, buildNotification(statusText(relayClient.connectionState.value)))

        when (intent?.action) {
            ACTION_CONNECT -> {
                val host = intent.getStringExtra(EXTRA_HOST)
                val password = intent.getStringExtra(EXTRA_PASSWORD)
                if (host.isNullOrEmpty() || password == null) {
                    shutdown()
                    return START_NOT_STICKY
                }
                val port = intent.getIntExtra(EXTRA_PORT, 9001)
                val useSsl = intent.getBooleanExtra(EXTRA_SSL, false)

                connectionPrefs.shouldAutoConnect = true
                startSession(host, port, password, useSsl)
            }

            ACTION_DISCONNECT -> {
                connectionPrefs.shouldAutoConnect = false
                relayClient.disconnect()
                shutdown()
                return START_NOT_STICKY
            }

            ACTION_REPLY -> {
                val bufferName = intent.getStringExtra(EXTRA_BUFFER)
                val reply = RemoteInput.getResultsFromIntent(intent)
                    ?.getCharSequence(KEY_REPLY_TEXT)
                    ?.toString()
                    ?.trim()
                if (!bufferName.isNullOrEmpty() && !reply.isNullOrEmpty()) {
                    handleReply(bufferName, reply)
                }
                return stickinessForNotificationAction()
            }

            ACTION_MARK_READ -> {
                val bufferName = intent.getStringExtra(EXTRA_BUFFER)
                if (!bufferName.isNullOrEmpty()) {
                    // Mark it read in WeeChat too, not just locally, so dismissing the
                    // notification also clears the buffer on the desktop client.
                    relayClient.markBufferRead(bufferName)
                    clearConversation(bufferName)
                }
                return stickinessForNotificationAction()
            }

            else -> {
                // Null intent means the system restarted us after killing the process. Restore the
                // session from saved credentials so the relay comes back on its own; if the user
                // disconnected deliberately, stand down instead of resurrecting the connection.
                if (!connectionPrefs.shouldAutoConnect || !connectionPrefs.hasSaved()) {
                    shutdown()
                    return START_NOT_STICKY
                }
                if (!relayClient.hasSession) {
                    val saved = connectionPrefs.load()
                    startSession(
                        host = saved.host,
                        port = saved.port.toIntOrNull() ?: 9001,
                        password = saved.password,
                        useSsl = saved.useSsl
                    )
                }
            }
        }
        return START_STICKY
    }

    /** Connect and (re)attach the per-session collectors and the network watcher. */
    private fun startSession(host: String, port: Int, password: String, useSsl: Boolean) {
        relayClient.connect(host, port, password, useSsl)

        stateJob?.cancel()
        messagesJob?.cancel()
        startCollectingMessages()

        stateJob = serviceScope.launch {
            relayClient.connectionState.collect { state ->
                updateNotification(statusText(state))

                // The client retries indefinitely on its own, so a dropped connection is not a
                // reason to tear the service down — only a deliberate disconnect or a fatal
                // (authentication) failure is.
                when (state) {
                    ConnectionState.DISCONNECTED -> shutdown()
                    ConnectionState.ERROR -> {
                        connectionPrefs.shouldAutoConnect = false
                        showFatalErrorNotification(relayClient.errorMessage.value)
                        shutdown()
                    }
                    else -> Unit
                }
            }
        }

        networkMonitor.start { relayClient.reconnectNow("network-available") }
    }

    private fun statusText(state: ConnectionState): String = when (state) {
        ConnectionState.CONNECTED -> "Connected"
        ConnectionState.CONNECTING -> "Connecting..."
        ConnectionState.RECONNECTING -> "Reconnecting..."
        ConnectionState.DISCONNECTED -> "Disconnected"
        ConnectionState.ERROR -> "Connection error"
    }

    /**
     * A notification action can arrive after the service has already been torn down (notifications
     * outlive it). In that case there is no session to keep alive, so stand back down instead of
     * leaving an orphaned foreground service with no connection.
     */
    private fun stickinessForNotificationAction(): Int {
        if (!relayClient.hasSession) {
            shutdown()
            return START_NOT_STICKY
        }
        return START_STICKY
    }

    private fun shutdown() {
        networkMonitor.stop()
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onDestroy() {
        networkMonitor.stop()
        serviceScope.cancel()
        super.onDestroy()
    }

    private fun createNotificationChannel() {
        val channel = NotificationChannel(
            CHANNEL_ID,
            "Relay Connection",
            NotificationManager.IMPORTANCE_LOW
        ).apply {
            description = "Shows the status of the WeeChat relay connection"
            setShowBadge(false)
        }
        val notificationManager = getSystemService(NotificationManager::class.java)
        notificationManager.createNotificationChannel(channel)
    }

    private fun buildNotification(text: String): Notification {
        val pendingIntent = PendingIntent.getActivity(
            this,
            0,
            Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )

        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("Weelay")
            .setContentText(text)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentIntent(pendingIntent)
            .setOngoing(true)
            .setSilent(true)
            .build()
    }

    private fun updateNotification(text: String) {
        val notificationManager = getSystemService(NotificationManager::class.java)
        notificationManager.notify(NOTIFICATION_ID, buildNotification(text))
    }

    /**
     * Surface a fatal failure (bad password, rejected token) as a dismissible notification.
     * The ongoing status notification disappears with the service, so without this the app would
     * just go quiet with no explanation of why it stopped reconnecting.
     */
    private fun showFatalErrorNotification(message: String?) {
        val pendingIntent = PendingIntent.getActivity(
            this,
            0,
            Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )

        val text = message ?: "The relay connection failed and will not be retried."
        val notification = NotificationCompat.Builder(this, MESSAGE_CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle("Weelay disconnected")
            .setContentText(text)
            .setStyle(NotificationCompat.BigTextStyle().bigText(text))
            .setContentIntent(pendingIntent)
            .setAutoCancel(true)
            .build()

        getSystemService(NotificationManager::class.java)
            .notify(ERROR_NOTIFICATION_ID, notification)
    }

    private fun createMessageNotificationChannel() {
        val channel = NotificationChannel(
            MESSAGE_CHANNEL_ID,
            "Messages",
            NotificationManager.IMPORTANCE_HIGH
        ).apply {
            description = "Notifications for highlights and private messages"
            enableVibration(true)
        }
        val notificationManager = getSystemService(NotificationManager::class.java)
        notificationManager.createNotificationChannel(channel)
    }

    private fun startCollectingMessages() {
        messagesJob = serviceScope.launch {
            relayClient.lines.collect { update ->
                // Never notify for fetched backlog — only for live sync lines.
                if (update.isHistory) return@collect
                for (line in update.lines) {
                    if (shouldNotify(line, update.bufferName)) {
                        showMessageNotification(update.bufferName, line)
                    }
                }
            }
        }

        // Opening a buffer in the app is the same as reading it: drop its pending notification
        // so the user isn't left dismissing a shade entry for a conversation they just read.
        activeBufferJob?.cancel()
        activeBufferJob = serviceScope.launch {
            relayClient.activeBuffer.collect { active ->
                if (active != null) clearConversation(active)
            }
        }
    }

    private fun shouldNotify(line: RelayLine, bufferName: String): Boolean {
        // Never notify for own messages
        if (line.tags.any { it == "self_msg" }) return false

        // Don't notify for the buffer the user is currently reading — they can already see it.
        if (relayClient.activeBuffer.value == bufferName) return false

        // Check if this is a private message buffer
        val buffer = relayClient.buffers.value.find { it.fullName == bufferName }
        val isPrivate = buffer?.localVariables?.get("type") == "private"

        // Notify on highlights or private messages
        return line.highlight || isPrivate
    }

    private fun showMessageNotification(bufferName: String, line: RelayLine) {
        val nick = MessageFormat.extractNick(line.prefix).ifEmpty { "***" }
        val text = MessageFormat.stripIrcFormatting(line.message)
        val timestamp = parseLineTimestamp(line.date)

        val person = Person.Builder().setName(nick).setKey(nick).build()
        addToConversation(
            bufferName,
            NotificationCompat.MessagingStyle.Message(text, timestamp, person)
        )
        postConversationNotification(bufferName)
    }

    private fun addToConversation(
        bufferName: String,
        message: NotificationCompat.MessagingStyle.Message
    ) {
        val history = conversations.getOrPut(bufferName) { mutableListOf() }
        history.add(message)
        // Keep the conversation bounded; the shade only renders a handful of lines anyway.
        while (history.size > MAX_MESSAGES_PER_CONVERSATION) {
            history.removeAt(0)
        }
    }

    /**
     * Build and post the notification for [bufferName] from its accumulated messages.
     *
     * Rebuilding from the whole conversation (rather than posting a single-line notification per
     * message) is what makes successive messages stack in the shade instead of each overwriting
     * the last, and it's also how a sent reply gets echoed back into the notification.
     */
    private fun postConversationNotification(bufferName: String) {
        val history = conversations[bufferName]
        if (history.isNullOrEmpty()) return

        val buffer = relayClient.buffers.value.find { it.fullName == bufferName }
        val displayName = buffer?.shortName ?: buffer?.name ?: bufferName
        val isPrivate = buffer?.localVariables?.get("type") == "private"
        val notifId = notificationIdFor(bufferName)

        val style = NotificationCompat.MessagingStyle(selfPerson)
            .setConversationTitle(displayName)
            .setGroupConversation(!isPrivate)
        history.forEach { style.addMessage(it) }

        val notification = NotificationCompat.Builder(this, MESSAGE_CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_notification)
            .setStyle(style)
            .setCategory(NotificationCompat.CATEGORY_MESSAGE)
            .setContentIntent(openBufferIntent(bufferName, notifId))
            .setDeleteIntent(bufferActionIntent(ACTION_MARK_READ, bufferName, notifId))
            .addAction(replyAction(bufferName, notifId))
            .addAction(markReadAction(bufferName, notifId))
            .setAutoCancel(true)
            .setGroup(MESSAGE_GROUP_KEY)
            .setOnlyAlertOnce(false)
            .build()

        val notificationManager = getSystemService(NotificationManager::class.java)
        notificationManager.notify(notifId, notification)

        // A group summary is only useful once there is more than one conversation to summarise;
        // posting it for a single conversation just adds a redundant row on some launchers.
        if (conversations.size > 1) {
            val summary = NotificationCompat.Builder(this, MESSAGE_CHANNEL_ID)
                .setSmallIcon(R.drawable.ic_notification)
                .setContentTitle("Weelay")
                .setContentText("New messages in ${conversations.size} buffers")
                .setGroup(MESSAGE_GROUP_KEY)
                .setGroupSummary(true)
                .setAutoCancel(true)
                .setContentIntent(openAppIntent())
                .build()
            notificationManager.notify(SUMMARY_NOTIFICATION_ID, summary)
        }
    }

    /**
     * Send a reply typed straight into the notification, then refresh the notification so the
     * sent line appears in it. Refreshing is also what dismisses the inline-reply spinner, so it
     * has to happen whether the send succeeded or not.
     */
    private fun handleReply(bufferName: String, text: String) {
        val sent = relayClient.sendInput(bufferName, text)
        if (sent) {
            // Replying means you've read it.
            relayClient.markBufferRead(bufferName)
            addToConversation(
                bufferName,
                // A null Person marks the message as being from the notification's own user.
                NotificationCompat.MessagingStyle.Message(
                    text,
                    System.currentTimeMillis(),
                    null as Person?
                )
            )
        } else {
            addToConversation(
                bufferName,
                NotificationCompat.MessagingStyle.Message(
                    "Not sent (disconnected): $text",
                    System.currentTimeMillis(),
                    null as Person?
                )
            )
        }
        postConversationNotification(bufferName)
    }

    /** Drop a buffer's accumulated messages and remove its notification. */
    private fun clearConversation(bufferName: String) {
        conversations.remove(bufferName)
        val notifId = bufferNotificationIds[bufferName] ?: return
        val notificationManager = getSystemService(NotificationManager::class.java)
        notificationManager.cancel(notifId)
        if (conversations.isEmpty()) {
            notificationManager.cancel(SUMMARY_NOTIFICATION_ID)
        }
    }

    private fun notificationIdFor(bufferName: String): Int =
        bufferNotificationIds.getOrPut(bufferName) { nextNotificationId++ }

    /** Deep link straight to the buffer, matching the scheme MainActivity already handles. */
    private fun openBufferIntent(bufferName: String, requestCode: Int): PendingIntent {
        val uri = Uri.parse("weelay://chat/${Uri.encode(bufferName, ".")}")
        val intent = Intent(Intent.ACTION_VIEW, uri, this, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP
        }
        return PendingIntent.getActivity(
            this,
            requestCode,
            intent,
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
    }

    private fun openAppIntent(): PendingIntent = PendingIntent.getActivity(
        this,
        0,
        Intent(this, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP
        },
        PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
    )

    /**
     * A PendingIntent back into this service for [action] on [bufferName].
     *
     * The distinct data URI matters: PendingIntents are matched ignoring extras, so without it
     * every buffer's actions would collapse onto whichever was created first.
     */
    private fun bufferActionIntent(
        action: String,
        bufferName: String,
        requestCode: Int,
        mutable: Boolean = false
    ): PendingIntent {
        val intent = Intent(this, RelayService::class.java).apply {
            this.action = action
            data = Uri.parse("weelay-action://$action/${Uri.encode(bufferName)}")
            putExtra(EXTRA_BUFFER, bufferName)
        }
        val mutabilityFlag = if (mutable) {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                PendingIntent.FLAG_MUTABLE
            } else {
                0
            }
        } else {
            PendingIntent.FLAG_IMMUTABLE
        }
        return PendingIntent.getForegroundService(
            this,
            requestCode,
            intent,
            mutabilityFlag or PendingIntent.FLAG_UPDATE_CURRENT
        )
    }

    private fun replyAction(bufferName: String, requestCode: Int): NotificationCompat.Action {
        val remoteInput = RemoteInput.Builder(KEY_REPLY_TEXT)
            .setLabel("Reply")
            .build()
        // Inline reply requires a mutable PendingIntent so the system can attach the typed text.
        val pendingIntent = bufferActionIntent(ACTION_REPLY, bufferName, requestCode, mutable = true)
        return NotificationCompat.Action.Builder(
            android.R.drawable.ic_menu_send,
            "Reply",
            pendingIntent
        )
            .addRemoteInput(remoteInput)
            .setSemanticAction(NotificationCompat.Action.SEMANTIC_ACTION_REPLY)
            .setAllowGeneratedReplies(true)
            .setShowsUserInterface(false)
            .build()
    }

    private fun markReadAction(bufferName: String, requestCode: Int): NotificationCompat.Action =
        NotificationCompat.Action.Builder(
            android.R.drawable.ic_menu_view,
            "Mark read",
            // Offset the request code so it can't collide with the reply action's PendingIntent.
            bufferActionIntent(ACTION_MARK_READ, bufferName, requestCode + MARK_READ_REQUEST_OFFSET)
        )
            .setSemanticAction(NotificationCompat.Action.SEMANTIC_ACTION_MARK_AS_READ)
            .setShowsUserInterface(false)
            .build()

    private fun parseLineTimestamp(date: String): Long = try {
        Instant.parse(date).toEpochMilli()
    } catch (_: Exception) {
        System.currentTimeMillis()
    }
}
