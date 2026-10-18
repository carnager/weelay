package com.weelay.app.ui.screens

import com.weelay.app.ui.theme.Palette
import androidx.compose.foundation.background
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.clickable
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
                    Text(
                        "Weelay",
                        style = MaterialTheme.typography.titleLarge.copy(
                            fontSize = 20.sp,
                            fontWeight = FontWeight.SemiBold
                        ),
                        color = Palette.onSurface
                    )
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
            // Connection status banner
            if (connectionState != com.weelay.app.relay.ConnectionState.CONNECTED) {
                val bannerText = when (connectionState) {
                    com.weelay.app.relay.ConnectionState.RECONNECTING -> "Reconnecting..."
                    com.weelay.app.relay.ConnectionState.CONNECTING -> "Connecting..."
                    com.weelay.app.relay.ConnectionState.DISCONNECTED -> "Disconnected"
                    com.weelay.app.relay.ConnectionState.ERROR -> "Connection lost"
                    else -> ""
                }
                val bannerColor = when (connectionState) {
                    com.weelay.app.relay.ConnectionState.RECONNECTING,
                    com.weelay.app.relay.ConnectionState.CONNECTING -> Palette.bannerProgress
                    else -> Palette.bannerError
                }
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .background(bannerColor)
                        .padding(vertical = 2.dp),
                    contentAlignment = Alignment.Center
                ) {
                    Text(
                        text = bannerText,
                        style = MaterialTheme.typography.labelSmall,
                        color = Color.White,
                        modifier = Modifier.padding(vertical = 4.dp, horizontal = 12.dp)
                    )
                }
            }

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
                LazyColumn(
                    modifier = Modifier.fillMaxSize()
                ) {
                    items(buffers, key = { it.fullName }) { buffer ->
                        BufferListItem(
                            buffer = buffer,
                            onClick = { onBufferClick(buffer.fullName) },
                            onLongClick = { actionsFor = buffer }
                        )
                        HorizontalDivider(
                            modifier = Modifier.padding(start = 76.dp),
                            color = Palette.divider
                        )
                    }
                }
            }
        }
    }
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
    val avatarColor = if (buffer.isChannel) {
        Palette.accent
    } else {
        nickColor(buffer.displayName)
    }

    val initial = if (buffer.isChannel) "#" else {
        buffer.displayName.firstOrNull()?.uppercase() ?: "?"
    }

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .combinedClickable(onClick = onClick, onLongClick = onLongClick)
            .padding(horizontal = 16.dp, vertical = 14.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Surface(
            shape = CircleShape,
            color = avatarColor,
            modifier = Modifier.size(50.dp)
        ) {
            Box(contentAlignment = Alignment.Center) {
                if (buffer.isChannel) {
                    Icon(
                        imageVector = Icons.Default.Tag,
                        contentDescription = null,
                        tint = Color.White,
                        modifier = Modifier.size(24.dp)
                    )
                } else {
                    Text(
                        text = initial,
                        style = MaterialTheme.typography.titleMedium.copy(
                            fontSize = 20.sp,
                            fontWeight = FontWeight.Medium
                        ),
                        color = Color.White
                    )
                }
            }
        }

        Spacer(modifier = Modifier.width(14.dp))

        Column(
            modifier = Modifier.weight(1f),
            verticalArrangement = Arrangement.Center
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = buffer.displayName,
                    style = MaterialTheme.typography.bodyLarge.copy(
                        fontSize = 17.sp,
                        fontWeight = if (buffer.unreadCount > 0) FontWeight.Bold else FontWeight.Normal
                    ),
                    color = Palette.onSurface,
                    modifier = Modifier.weight(1f)
                )
                if (buffer.lastMessageTime.isNotEmpty()) {
                    Text(
                        text = buffer.lastMessageTime,
                        style = MaterialTheme.typography.labelSmall.copy(
                            fontSize = 12.sp
                        ),
                        color = if (buffer.unreadCount > 0)
                            Palette.accent
                        else Palette.muted
                    )
                }
            }

            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = 2.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = buffer.lastMessage,
                    style = MaterialTheme.typography.bodyMedium.copy(
                        fontSize = 14.sp
                    ),
                    color = Palette.muted,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier
                        .weight(1f)
                        .padding(end = 8.dp)
                )
                if (buffer.unreadCount > 0) {
                    // Highlights get their own colour so a buffer that mentioned you stands out
                    // from one that merely has unread chatter.
                    Badge(
                        containerColor = if (buffer.highlightCount > 0) {
                            Color(0xFFE53935)
                        } else {
                            Palette.accent
                        }
                    ) {
                        Text(
                            text = if (buffer.unreadCount > 99) "99+" else buffer.unreadCount.toString(),
                            color = Color.White,
                            fontSize = 11.sp
                        )
                    }
                }
            }
        }
    }
}
