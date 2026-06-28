package com.weelay.app.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
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
    onHighlightClick: (bufferFullName: String, messageId: Long) -> Unit = { b, _ -> onBufferClick(b) },
    onSettingsClick: () -> Unit = {},
    viewModel: BufferListViewModel = hiltViewModel()
) {
    val buffers by viewModel.buffers.collectAsState()
    val connectionState by viewModel.connectionState.collectAsState()
    val highlights by viewModel.highlights.collectAsState()
    var showHighlights by remember { mutableStateOf(false) }

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
                        color = Color(0xFFE9EDEF)
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
                            tint = Color(0xFF8696A0)
                        )
                    }
                    IconButton(onClick = onSettingsClick) {
                        Icon(
                            imageVector = Icons.Default.Settings,
                            contentDescription = "Settings",
                            tint = Color(0xFF8696A0)
                        )
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = Color(0xFF1F2C34)
                )
            )
        },
        containerColor = Color(0xFF0B141A)
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
                    com.weelay.app.relay.ConnectionState.CONNECTING -> Color(0xFF1B5E20)
                    else -> Color(0xFFB71C1C)
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

            if (buffers.isEmpty()) {
                Box(
                    modifier = Modifier.fillMaxSize(),
                    contentAlignment = Alignment.Center
                ) {
                    Text(
                        text = "No buffers",
                        style = MaterialTheme.typography.bodyLarge,
                        color = Color(0xFF8696A0)
                    )
                }
            } else {
                LazyColumn(
                    modifier = Modifier.fillMaxSize()
                ) {
                    items(buffers, key = { it.fullName }) { buffer ->
                        BufferListItem(
                            buffer = buffer,
                            onClick = { onBufferClick(buffer.fullName) }
                        )
                        HorizontalDivider(
                            modifier = Modifier.padding(start = 76.dp),
                            color = Color(0xFF233039)
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun BufferListItem(
    buffer: BufferDisplayItem,
    onClick: () -> Unit
) {
    val avatarColor = if (buffer.isChannel) {
        Color(0xFF00A884)
    } else {
        nickColor(buffer.displayName)
    }

    val initial = if (buffer.isChannel) "#" else {
        buffer.displayName.firstOrNull()?.uppercase() ?: "?"
    }

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
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
                    color = Color(0xFFE9EDEF),
                    modifier = Modifier.weight(1f)
                )
                if (buffer.lastMessageTime.isNotEmpty()) {
                    Text(
                        text = buffer.lastMessageTime,
                        style = MaterialTheme.typography.labelSmall.copy(
                            fontSize = 12.sp
                        ),
                        color = if (buffer.unreadCount > 0)
                            Color(0xFF00A884)
                        else Color(0xFF8696A0)
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
                    color = Color(0xFF8696A0),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier
                        .weight(1f)
                        .padding(end = 8.dp)
                )
                if (buffer.unreadCount > 0) {
                    Badge(
                        containerColor = Color(0xFF00A884)
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
