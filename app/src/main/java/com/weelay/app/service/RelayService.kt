package com.weelay.app.service

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Intent
import android.os.IBinder
import androidx.core.app.NotificationCompat
import com.weelay.app.MainActivity
import com.weelay.app.R
import com.weelay.app.relay.ConnectionState
import com.weelay.app.relay.RelayClient
import com.weelay.app.relay.RelayLine
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

    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    companion object {
        const val CHANNEL_ID = "weelay_relay_channel"
        const val MESSAGE_CHANNEL_ID = "weelay_messages_channel"
        const val MESSAGE_GROUP_KEY = "com.weelay.app.MESSAGES"
        const val NOTIFICATION_ID = 1
        const val SUMMARY_NOTIFICATION_ID = 2
        private const val MESSAGE_NOTIFICATION_ID_BASE = 1000
        const val ACTION_CONNECT = "com.weelay.app.CONNECT"
        const val ACTION_DISCONNECT = "com.weelay.app.DISCONNECT"
        const val EXTRA_HOST = "host"
        const val EXTRA_PORT = "port"
        const val EXTRA_PASSWORD = "password"
        const val EXTRA_SSL = "ssl"
    }

    /** Maps buffer full name to a stable notification ID */
    private val bufferNotificationIds = mutableMapOf<String, Int>()
    private var nextNotificationId = MESSAGE_NOTIFICATION_ID_BASE

    // Per-connect collectors; cancelled and relaunched on each ACTION_CONNECT so repeated
    // connect intents don't stack duplicate collectors (which caused duplicate notifications).
    private var stateJob: Job? = null
    private var messagesJob: Job? = null

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
        createMessageNotificationChannel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_CONNECT -> {
                val host = intent.getStringExtra(EXTRA_HOST) ?: return START_NOT_STICKY
                val port = intent.getIntExtra(EXTRA_PORT, 9001)
                val password = intent.getStringExtra(EXTRA_PASSWORD) ?: return START_NOT_STICKY
                val useSsl = intent.getBooleanExtra(EXTRA_SSL, false)

                startForeground(NOTIFICATION_ID, buildNotification("Connecting..."))
                relayClient.connect(host, port, password, useSsl)

                stateJob?.cancel()
                messagesJob?.cancel()
                startCollectingMessages()

                stateJob = serviceScope.launch {
                    relayClient.connectionState.collect { state ->
                        val text = when (state) {
                            ConnectionState.CONNECTED -> "Connected"
                            ConnectionState.CONNECTING -> "Connecting..."
                            ConnectionState.RECONNECTING -> "Reconnecting..."
                            ConnectionState.DISCONNECTED -> "Disconnected"
                            ConnectionState.ERROR -> "Connection error"
                        }
                        updateNotification(text)

                        if (state == ConnectionState.DISCONNECTED || state == ConnectionState.ERROR) {
                            stopSelf()
                        }
                    }
                }
            }
            ACTION_DISCONNECT -> {
                relayClient.disconnect()
                stopForeground(STOP_FOREGROUND_REMOVE)
                stopSelf()
            }
        }
        return START_STICKY
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onDestroy() {
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
            .setSmallIcon(android.R.drawable.ic_dialog_info)
            .setContentIntent(pendingIntent)
            .setOngoing(true)
            .setSilent(true)
            .build()
    }

    private fun updateNotification(text: String) {
        val notificationManager = getSystemService(NotificationManager::class.java)
        notificationManager.notify(NOTIFICATION_ID, buildNotification(text))
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
    }

    private fun shouldNotify(line: RelayLine, bufferName: String): Boolean {
        // Never notify for own messages
        if (line.tags.any { it == "self_msg" }) return false

        // Check if this is a private message buffer
        val buffer = relayClient.buffers.value.find { it.fullName == bufferName }
        val isPrivate = buffer?.localVariables?.get("type") == "private"

        // Notify on highlights or private messages
        return line.highlight || isPrivate
    }

    private fun showMessageNotification(bufferName: String, line: RelayLine) {
        val notificationManager = getSystemService(NotificationManager::class.java)

        val buffer = relayClient.buffers.value.find { it.fullName == bufferName }
        val displayName = buffer?.shortName ?: buffer?.name ?: bufferName

        val nick = line.prefix.ifEmpty { "***" }
        val contentText = "$nick: ${line.message}"

        val pendingIntent = PendingIntent.getActivity(
            this,
            0,
            Intent(this, MainActivity::class.java).apply {
                flags = Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP
            },
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )

        val notifId = bufferNotificationIds.getOrPut(bufferName) { nextNotificationId++ }

        val notification = NotificationCompat.Builder(this, MESSAGE_CHANNEL_ID)
            .setSmallIcon(android.R.drawable.ic_dialog_info)
            .setContentTitle(displayName)
            .setContentText(contentText)
            .setStyle(NotificationCompat.BigTextStyle().bigText(contentText))
            .setContentIntent(pendingIntent)
            .setAutoCancel(true)
            .setGroup(MESSAGE_GROUP_KEY)
            .build()

        notificationManager.notify(notifId, notification)

        // Show a summary notification for grouping
        val summary = NotificationCompat.Builder(this, MESSAGE_CHANNEL_ID)
            .setSmallIcon(android.R.drawable.ic_dialog_info)
            .setContentTitle("Weelay")
            .setContentText("New messages")
            .setGroup(MESSAGE_GROUP_KEY)
            .setGroupSummary(true)
            .setAutoCancel(true)
            .setContentIntent(pendingIntent)
            .build()

        notificationManager.notify(SUMMARY_NOTIFICATION_ID, summary)
    }
}
