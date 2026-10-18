package com.weelay.app.ui.screens

import com.weelay.app.ui.theme.Palette
import androidx.compose.foundation.background
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextAlign
import com.weelay.app.relay.ConnectionState
import androidx.compose.foundation.combinedClickable
import androidx.compose.material.icons.filled.DoneAll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Notifications
import androidx.compose.material.icons.filled.Person
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Tag
import androidx.compose.material3.Badge
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.navigation.compose.hiltViewModel
import com.weelay.app.ui.components.HighlightsSheet
import com.weelay.app.ui.theme.nickColor
import com.weelay.app.viewmodel.BufferDisplayItem
import com.weelay.app.viewmodel.BufferListViewModel

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun BufferListScreen(
    onBufferClick: (String) -> Unit,
    /** Text shared from another app; while set, tapping a buffer sends it there as a draft. */
    shareText: String? = null,
    onCancelShare: () -> Unit = {},
    onHighlightClick: (bufferFullName: String, messageId: Long) -> Unit = { b, _ -> onBufferClick(b) },
    onSettingsClick: () -> Unit = {},
    viewModel: BufferListViewModel = hiltViewModel()
) {
    val buffers by viewModel.buffers.collectAsState()
    val host = remember { viewModel.host }
    val connectionState by viewModel.connectionState.collectAsState()
    val highlights by viewModel.highlights.collectAsState()
    var showHighlights by remember { mutableStateOf(false) }
    var actionsFor by remember { mutableStateOf<BufferDisplayItem?>(null) }
    var confirmClose by remember { mutableStateOf<BufferDisplayItem?>(null) }

    actionsFor?.let { buffer ->
        ModalBottomSheet(
            onDismissRequest = { actionsFor = null },
            sheetState = rememberModalBottomSheetState(),
            containerColor = Palette.surface,
            dragHandle = null
        ) {
            Column(modifier = Modifier.padding(vertical = 12.dp)) {
                Text(
                    buffer.displayName,
                    style = MaterialTheme.typography.titleMedium,
                    color = Palette.onSurface,
                    modifier = Modifier.padding(horizontal = 20.dp, vertical = 8.dp)
                )
                if (buffer.unreadCount > 0) {
                    BufferActionRow(Icons.Default.DoneAll, "Mark as read", Palette.onSurface) {
                        viewModel.markRead(buffer.fullName)
                        actionsFor = null
                    }
                }
                BufferActionRow(
                    Icons.Default.Close,
                    if (buffer.isChannel) "Leave and close" else "Close buffer",
                    Palette.danger
                ) {
                    confirmClose = buffer
                    actionsFor = null
                }
            }
        }
    }

    confirmClose?.let { buffer ->
        AlertDialog(
            onDismissRequest = { confirmClose = null },
            containerColor = Palette.surface,
            text = {
                Text(
                    if (buffer.isChannel) "Leave ${buffer.displayName} and close its buffer?"
                    else "Close ${buffer.displayName}?"
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    viewModel.closeBuffer(buffer.fullName)
                    confirmClose = null
                }) { Text(if (buffer.isChannel) "Leave" else "Close", color = Palette.danger) }
            },
            dismissButton = {
                TextButton(onClick = { confirmClose = null }) { Text("Cancel") }
            }
        )
    }

    if (showHighlights) {
        HighlightsSheet(
            highlights = highlights,
            onDismiss = { showHighlights = false },
            onItemClick = { item ->
                showHighlights = false
                onHighlightClick(item.bufferFullName, item.messageId)
            }
        )
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    val (status, dot) = when (connectionState) {
                        ConnectionState.CONNECTED -> "connected" to Palette.ok
                        ConnectionState.CONNECTING -> "connecting…" to Palette.accent
                        ConnectionState.RECONNECTING -> "reconnecting…" to Palette.accent
                        ConnectionState.DISCONNECTED -> "disconnected" to Palette.danger
                        ConnectionState.ERROR -> "connection lost" to Palette.danger
                    }
                    Column {
                        Text(
                            "weelay",
                            style = MaterialTheme.typography.titleLarge.copy(
                                fontSize = 19.sp,
                                fontWeight = FontWeight.SemiBold,
                                fontFamily = FontFamily.Monospace
                            ),
                            color = Palette.onSurface
                        )
                        // The connection state lives here rather than in a banner: it's always
                        // visible without pushing the list down when it changes.
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Box(
                                modifier = Modifier
                                    .size(7.dp)
                                    .background(dot, CircleShape)
                            )
                            Spacer(modifier = Modifier.width(6.dp))
                            Text(
                                "$host · $status",
                                style = MaterialTheme.typography.labelSmall.copy(
                                    fontSize = 11.sp,
                                    fontFamily = FontFamily.Monospace
                                ),
                                color = Palette.muted
                            )
                        }
                    }
                },
                actions = {
                    IconButton(onClick = {
                        viewModel.loadHighlights()
                        showHighlights = true
                    }) {
                        Icon(
                            imageVector = Icons.Default.Notifications,
                            contentDescription = "Highlights",
                            tint = Palette.muted
                        )
                    }
                    IconButton(onClick = onSettingsClick) {
                        Icon(
                            imageVector = Icons.Default.Settings,
                            contentDescription = "Settings",
                            tint = Palette.muted
                        )
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = Palette.surface
                )
            )
        },
        containerColor = Palette.background
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
        ) {
            if (shareText != null) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .background(Palette.surface)
                        .padding(start = 16.dp, top = 8.dp, bottom = 8.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            "Share to…",
                            style = MaterialTheme.typography.labelLarge,
                            color = Palette.accent
                        )
                        Text(
                            shareText,
                            style = MaterialTheme.typography.bodySmall,
                            color = Palette.muted,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                    }
                    IconButton(onClick = onCancelShare) {
                        Icon(
                            imageVector = Icons.Default.Close,
                            contentDescription = "Cancel sharing",
                            tint = Palette.muted
                        )
                    }
                }
            }

            if (buffers.isEmpty()) {
                Box(
                    modifier = Modifier.fillMaxSize(),
                    contentAlignment = Alignment.Center
                ) {
                    Text(
                        text = "No buffers",
                        style = MaterialTheme.typography.bodyLarge,
                        color = Palette.muted
                    )
                }
            } else {
                val active = remember(buffers) { buffers.filter { it.unreadCount > 0 } }
                // Sections in buffer order: each network's channels (and server buffer), then its
                // private chats.
                val sections = remember(buffers) {
                    buffers.groupBy { it.network }.flatMap { (network, list) ->
                        val (private, rest) = list.partition { it.isPrivate }
                        listOfNotNull(
                            rest.takeIf { it.isNotEmpty() }?.let { network to it },
                            private.takeIf { it.isNotEmpty() }?.let { "$network · private" to it }
                        )
                    }
                }
                LazyColumn(
                    modifier = Modifier.fillMaxSize()
                ) {
                    if (active.isNotEmpty()) {
                        item(key = "act") {
                            ActivityStrip(active, onClick = { onBufferClick(it.fullName) })
                        }
                    }
                    sections.forEach { (title, list) ->
                        item(key = "section_$title") {
                            Text(
                                title.uppercase(),
                                style = MaterialTheme.typography.labelSmall.copy(
                                    fontSize = 11.sp,
                                    fontFamily = FontFamily.Monospace,
                                    letterSpacing = 0.8.sp
                                ),
                                color = Palette.muted,
                                modifier = Modifier.padding(start = 16.dp, top = 14.dp, bottom = 2.dp)
                            )
                        }
                        items(list, key = { it.fullName }) { buffer ->
                            BufferListItem(
                                buffer = buffer,
                                onClick = { onBufferClick(buffer.fullName) },
                                onLongClick = { actionsFor = buffer }
                            )
                        }
                    }
                }
            }
        }
    }
}

/**
 * WeeChat's hotlist as a strip: every buffer with unread messages, highlights in the accent.
 * Tapping one opens it.
 */
@Composable
private fun ActivityStrip(active: List<BufferDisplayItem>, onClick: (BufferDisplayItem) -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .horizontalScroll(rememberScrollState())
            .padding(horizontal = 12.dp, vertical = 10.dp),
        horizontalArrangement = Arrangement.spacedBy(6.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(
            "act",
            style = MaterialTheme.typography.labelSmall.copy(fontSize = 12.sp, fontFamily = FontFamily.Monospace),
            color = Palette.muted
        )
        active.forEach { buffer ->
            val highlight = buffer.highlightCount > 0
            Text(
                "${buffer.number}:${buffer.displayName.removePrefix("#")}",
                style = MaterialTheme.typography.labelSmall.copy(
                    fontSize = 12.sp,
                    fontFamily = FontFamily.Monospace,
                    fontWeight = if (highlight) FontWeight.Bold else FontWeight.Normal
                ),
                color = if (highlight) Palette.onAccent else Palette.onSurface,
                modifier = Modifier
                    .background(
                        if (highlight) Palette.accent else Palette.surfaceRaised,
                        RoundedCornerShape(4.dp)
                    )
                    .clickable { onClick(buffer) }
                    .padding(horizontal = 6.dp, vertical = 3.dp)
            )
        }
    }
    HorizontalDivider(color = Palette.divider)
}

@Composable
private fun BufferActionRow(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    label: String,
    tint: Color,
    onClick: () -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(horizontal = 20.dp, vertical = 14.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Icon(icon, contentDescription = null, tint = tint, modifier = Modifier.size(24.dp))
        Spacer(modifier = Modifier.width(16.dp))
        Text(label, style = MaterialTheme.typography.bodyLarge.copy(fontSize = 16.sp), color = tint)
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun BufferListItem(
    buffer: BufferDisplayItem,
    onClick: () -> Unit,
    onLongClick: () -> Unit
) {
    val unread = buffer.unreadCount > 0
    val mono = FontFamily.Monospace
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .combinedClickable(onClick = onClick, onLongClick = onLongClick)
            .padding(horizontal = 16.dp, vertical = 9.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        // WeeChat's buffer number, so the list lines up with the desktop client and /buffer N.
        Text(
            text = buffer.number.toString(),
            style = MaterialTheme.typography.labelSmall.copy(fontSize = 12.sp, fontFamily = mono),
            color = Palette.muted,
            textAlign = TextAlign.End,
            modifier = Modifier.width(22.dp)
        )
        Spacer(modifier = Modifier.width(10.dp))
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = buffer.displayName,
                style = MaterialTheme.typography.bodyLarge.copy(
                    fontSize = 15.sp,
                    fontFamily = mono,
                    fontWeight = if (unread) FontWeight.SemiBold else FontWeight.Normal
                ),
                color = when {
                    buffer.isPrivate -> nickColor(buffer.displayName)
                    unread -> Palette.onSurface
                    else -> Palette.onSurface.copy(alpha = 0.78f)
                },
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
            if (buffer.lastMessage.isNotEmpty()) {
                Text(
                    text = buffer.lastMessage,
                    style = MaterialTheme.typography.bodyMedium.copy(fontSize = 13.sp),
                    color = Palette.muted,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }
        }
        Spacer(modifier = Modifier.width(10.dp))
        Column(horizontalAlignment = Alignment.End) {
            Text(
                text = buffer.lastMessageTime,
                style = MaterialTheme.typography.labelSmall.copy(fontSize = 11.sp, fontFamily = mono),
                color = Palette.muted
            )
            if (unread) {
                // Highlights in the accent so a buffer that mentioned you stands out from one
                // that merely has unread chatter.
                val highlight = buffer.highlightCount > 0
                Text(
                    text = if (buffer.unreadCount > 99) "99+" else buffer.unreadCount.toString(),
                    style = MaterialTheme.typography.labelSmall.copy(
                        fontSize = 11.sp,
                        fontFamily = mono,
                        fontWeight = FontWeight.SemiBold
                    ),
                    color = if (highlight) Palette.onAccent else Palette.onSurface,
                    modifier = Modifier
                        .padding(top = 3.dp)
                        .background(
                            if (highlight) Palette.accent else Palette.surfaceRaised,
                            RoundedCornerShape(4.dp)
                        )
                        .padding(horizontal = 5.dp, vertical = 1.dp)
                )
            }
        }
    }
}
