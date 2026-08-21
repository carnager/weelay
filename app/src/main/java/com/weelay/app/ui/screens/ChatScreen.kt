package com.weelay.app.ui.screens

import android.widget.Toast
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.ui.input.pointer.positionChange
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.Reply
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Group
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.Link
import androidx.compose.material.icons.filled.Notifications
import androidx.compose.material.icons.filled.Person
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.FloatingActionButtonDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.material.icons.filled.Reply
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.ime
import androidx.compose.ui.Alignment
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.navigation.compose.hiltViewModel
import com.weelay.app.relay.ConnectionState
import com.weelay.app.ui.components.HighlightsSheet
import com.weelay.app.ui.components.MessageBubble
import com.weelay.app.ui.components.NickActionSheet
import com.weelay.app.ui.theme.nickColor
import com.weelay.app.viewmodel.ChatViewModel
import com.weelay.app.viewmodel.MessageGroup
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ChatScreen(
    bufferFullName: String,
    targetMessageId: Long? = null,
    onBack: () -> Unit,
    onNavigateToBuffer: (String) -> Unit = {},
    viewModel: ChatViewModel = hiltViewModel()
) {
    val messages by viewModel.messageGroups.collectAsState()
    val bufferName by viewModel.bufferName.collectAsState()
    val nicklist by viewModel.nicklist.collectAsState()
    val urls by viewModel.urls.collectAsState()
    val loadedMoreHistory by viewModel.loadedMoreHistory.collectAsState()
    val initialLoadComplete by viewModel.initialLoadComplete.collectAsState()
    val hasMoreHistory by viewModel.hasMoreHistory.collectAsState()
    val connectionState by viewModel.connectionState.collectAsState()
    var inputText by remember { mutableStateOf(TextFieldValue("")) }
    val listState = rememberLazyListState()
    var selectedNick by remember { mutableStateOf<String?>(null) }
    var showNicklist by remember { mutableStateOf(false) }
    var showUrlList by remember { mutableStateOf(false) }
    var showSearch by remember { mutableStateOf(false) }
    var showHighlightsSheet by remember { mutableStateOf(false) }
    val chatHighlights by viewModel.highlights.collectAsState()
    var searchQuery by remember { mutableStateOf("") }
    var searchMatchIds by remember { mutableStateOf<Set<Long>>(emptySet()) }
    var highlightedMessageId by remember { mutableStateOf<Long?>(null) }
    var searchResults by remember { mutableStateOf<List<Pair<Long, Int>>>(emptyList()) } // msgId to itemIndex
    var currentSearchIndex by remember { mutableStateOf(-1) }
    val inputFocusRequester = remember { FocusRequester() }
    val inputFieldFocusRequester = remember { FocusRequester() }
    val coroutineScope = rememberCoroutineScope()
    val context = LocalContext.current
    var replyToNick by remember { mutableStateOf<String?>(null) }
    var replyToMessage by remember { mutableStateOf<String?>(null) }
    var replyToColor by remember { mutableStateOf(Color(0xFF00A884)) }
    var justSentMessage by remember { mutableStateOf(false) }
    var oldestMessageId by remember { mutableStateOf<Long?>(null) }

    val isChannel = bufferFullName.contains("#")

    val showScrollToBottom by remember {
        derivedStateOf {
            val layoutInfo = listState.layoutInfo
            val lastVisibleItem = layoutInfo.visibleItemsInfo.lastOrNull()?.index ?: 0
            val totalItems = layoutInfo.totalItemsCount
            totalItems > 0 && lastVisibleItem < totalItems - 3
        }
    }

    var contentReady by remember { mutableStateOf(false) }
    var initialLoadTime by remember { mutableStateOf(0L) }
    var showContent by remember { mutableStateOf(false) }
    var scrolledToTarget by remember { mutableStateOf(false) }
    val contentAlpha by animateFloatAsState(
        targetValue = if (showContent) 1f else 0f,
        animationSpec = tween(150),
        label = "contentAlpha"
    )

    val imeBottom = WindowInsets.ime.getBottom(LocalDensity.current)
    LaunchedEffect(imeBottom) {
        if (imeBottom > 0 && messages.isNotEmpty()) {
            val totalItems = listState.layoutInfo.totalItemsCount
            if (totalItems > 0) {
                listState.scrollToItem(totalItems - 1)
                // Scroll again after layout settles
                delay(100)
                val total2 = listState.layoutInfo.totalItemsCount
                if (total2 > 0) listState.scrollToItem(total2 - 1)
            }
        }
    }

    LaunchedEffect(replyToNick) {
        if (replyToNick != null) {
            inputFieldFocusRequester.requestFocus()
            val totalItems = listState.layoutInfo.totalItemsCount
            if (totalItems > 0) {
                listState.scrollToItem(totalItems - 1)
            }
        }
    }

    LaunchedEffect(showSearch) {
        if (showSearch) {
            inputFocusRequester.requestFocus()
        }
    }

    LaunchedEffect(bufferFullName) {
        viewModel.loadBuffer(bufferFullName)
    }

    // Mark this buffer as the one on screen (and clear it on leave) so the buffer list can
    // suppress and reset unread counts for it.
    DisposableEffect(bufferFullName) {
        viewModel.onBufferVisible(bufferFullName)
        onDispose { viewModel.onBufferHidden(bufferFullName) }
    }

    LaunchedEffect(messages, initialLoadComplete) {
        if (messages.isEmpty()) {
            // No messages — still show the (empty) content
            if (!contentReady && initialLoadComplete) {
                contentReady = true
                showContent = true
            }
        } else if (messages.isNotEmpty() && initialLoadComplete) {
            // +1 for "load more" button at top
            val totalItems = 1 + messages.sumOf { (if (it.showTimestamp) 1 else 0) + it.messages.size }
            val lastIndex = totalItems - 1

            if (!contentReady || (initialLoadTime > 0 && System.currentTimeMillis() - initialLoadTime < 2000)) {
                // Wait a frame for Compose to lay out the items
                delay(50)

                if (targetMessageId != null && !scrolledToTarget) {
                    // Find and scroll to the target message
                    var itemIndex = 1
                    var found = false
                    for (group in messages) {
                        if (group.showTimestamp) itemIndex++
                        for (msg in group.messages) {
                            if (msg.id == targetMessageId) {
                                listState.scrollToItem(itemIndex)
                                highlightedMessageId = targetMessageId
                                scrolledToTarget = true
                                found = true
                                break
                            }
                            itemIndex++
                        }
                        if (found) break
                    }
                    if (!found) {
                        listState.scrollToItem(lastIndex)
                    }
                } else {
                    listState.scrollToItem(lastIndex)
                }
                if (!contentReady) {
                    initialLoadTime = System.currentTimeMillis()
                    contentReady = true
                    delay(300)
                    // Re-scroll after settle (link previews etc. may have shifted layout)
                    if (targetMessageId != null && scrolledToTarget) {
                        var itemIndex = 1
                        for (group in messages) {
                            if (group.showTimestamp) itemIndex++
                            for (msg in group.messages) {
                                if (msg.id == targetMessageId) {
                                    listState.scrollToItem(itemIndex)
                                    break
                                }
                                itemIndex++
                            }
                        }
                    } else {
                        listState.scrollToItem(lastIndex)
                    }
                    showContent = true
                }
            } else if (loadedMoreHistory && oldestMessageId != null) {
                // Find the old first message and scroll to it
                var itemIndex = 1 // after "load more" button
                for (group in messages) {
                    if (group.showTimestamp) {
                        itemIndex++ // only count if timestamp separator is shown
                    }
                    for (msg in group.messages) {
                        if (msg.id == oldestMessageId) {
                            listState.scrollToItem(itemIndex)
                            oldestMessageId = null
                            viewModel.onHistoryScrollHandled()
                            return@LaunchedEffect
                        }
                        itemIndex++
                    }
                }
                viewModel.onHistoryScrollHandled()
            } else if (!loadedMoreHistory) {
                if (justSentMessage) {
                    justSentMessage = false
                    listState.animateScrollToItem(lastIndex)
                } else {
                    // New message — only auto-scroll if near bottom
                    val layoutInfo = listState.layoutInfo
                    val lastVisible = layoutInfo.visibleItemsInfo.lastOrNull()?.index ?: 0
                    if (lastVisible >= totalItems - 5) {
                        listState.animateScrollToItem(lastIndex)
                    }
                }
            }
        }
    }

    // Nick action bottom sheet
    selectedNick?.let { nick ->
        NickActionSheet(
            nick = nick,
            nickColor = nickColor(nick),
            isChannel = isChannel,
            onDismiss = { selectedNick = null },
            onQuery = {
                val targetBuffer = viewModel.queryNick(nick)
                onNavigateToBuffer(targetBuffer)
            },
            onWhois = {
                val targetBuffer = viewModel.nickservInfo(nick)
                onNavigateToBuffer(targetBuffer)
            },
            onBan = {
                viewModel.sendMessage("/ban $nick")
            },
            onKick = {
                viewModel.sendMessage("/kick $nick")
            },
            onQuiet = {
                viewModel.sendMessage("/quiet $nick")
            }
        )
    }

    // Highlights bottom sheet
    if (showHighlightsSheet) {
        HighlightsSheet(
            highlights = chatHighlights,
            title = "Highlights in $bufferName",
            showChannel = false,
            onDismiss = { showHighlightsSheet = false },
            onItemClick = { item ->
                showHighlightsSheet = false
                // Scroll to and highlight the message
                highlightedMessageId = item.messageId
                coroutineScope.launch {
                    var itemIndex = 1
                    for (group in messages) {
                        if (group.showTimestamp) itemIndex++
                        for (msg in group.messages) {
                            if (msg.id == item.messageId) {
                                listState.animateScrollToItem(itemIndex)
                                return@launch
                            }
                            itemIndex++
                        }
                    }
                }
            }
        )
    }

    // Nicklist bottom sheet
    if (showNicklist) {
        NicklistSheet(
            nicks = nicklist.map { it.name },
            onDismiss = { showNicklist = false },
            onNickClick = { nick ->
                showNicklist = false
                selectedNick = nick
            }
        )
    }

    // URL list bottom sheet
    if (showUrlList) {
        UrlListSheet(
            urls = urls,
            onDismiss = { showUrlList = false }
        )
    }

    Scaffold(
        topBar = {
            if (showSearch) {
                TopAppBar(
                    title = {
                        OutlinedTextField(
                            value = searchQuery,
                            onValueChange = { value ->
                                searchQuery = value
                                if (value.length >= 2) {
                                    val ids = mutableSetOf<Long>()
                                    val results = mutableListOf<Pair<Long, Int>>()
                                    var itemIndex = 1
                                    for (group in messages) {
                                        if (group.showTimestamp) itemIndex++
                                        for (msg in group.messages) {
                                            if (msg.text.contains(value, ignoreCase = true) ||
                                                msg.nick.contains(value, ignoreCase = true)) {
                                                ids.add(msg.id)
                                                results.add(msg.id to itemIndex)
                                            }
                                            itemIndex++
                                        }
                                    }
                                    searchMatchIds = ids
                                    searchResults = results
                                    currentSearchIndex = if (results.isNotEmpty()) results.size - 1 else -1
                                    if (results.isNotEmpty()) {
                                        highlightedMessageId = results.last().first
                                        coroutineScope.launch {
                                            listState.animateScrollToItem(results.last().second)
                                        }
                                    } else {
                                        highlightedMessageId = null
                                    }
                                } else {
                                    searchMatchIds = emptySet()
                                    searchResults = emptyList()
                                    currentSearchIndex = -1
                                    highlightedMessageId = null
                                }
                            },
                            placeholder = { Text("Search...", color = Color(0xFF8696A0)) },
                            modifier = Modifier
                                .fillMaxWidth()
                                .focusRequester(inputFocusRequester),
                            singleLine = true,
                            textStyle = MaterialTheme.typography.bodyMedium.copy(
                                fontSize = 15.sp,
                                color = Color(0xFFE9EDEF)
                            ),
                            colors = OutlinedTextFieldDefaults.colors(
                                focusedContainerColor = Color.Transparent,
                                unfocusedContainerColor = Color.Transparent,
                                focusedBorderColor = Color.Transparent,
                                unfocusedBorderColor = Color.Transparent,
                                cursorColor = Color(0xFF00A884)
                            )
                        )
                    },
                    navigationIcon = {
                        IconButton(onClick = {
                            showSearch = false
                            searchQuery = ""
                            searchMatchIds = emptySet()
                            highlightedMessageId = null
                            searchResults = emptyList()
                            currentSearchIndex = -1
                        }) {
                            Icon(
                                imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                                contentDescription = "Close search",
                                tint = Color(0xFFE9EDEF)
                            )
                        }
                    },
                    actions = {
                        if (searchResults.isNotEmpty()) {
                            Text(
                                text = "${currentSearchIndex + 1}/${searchResults.size}",
                                color = Color(0xFF8696A0),
                                style = MaterialTheme.typography.labelSmall,
                                modifier = Modifier.padding(end = 4.dp)
                            )
                        }
                        IconButton(
                            onClick = {
                                if (currentSearchIndex > 0) {
                                    currentSearchIndex--
                                    val (msgId, itemIdx) = searchResults[currentSearchIndex]
                                    highlightedMessageId = msgId
                                    coroutineScope.launch {
                                        listState.animateScrollToItem(itemIdx)
                                    }
                                }
                            },
                            modifier = Modifier.size(36.dp)
                        ) {
                            Icon(
                                imageVector = Icons.Default.KeyboardArrowDown,
                                contentDescription = "Previous",
                                tint = if (currentSearchIndex > 0) Color(0xFFE9EDEF) else Color(0xFF8696A0),
                                modifier = Modifier
                                    .size(24.dp)
                                    .graphicsLayer { rotationZ = 180f }
                            )
                        }
                        IconButton(
                            onClick = {
                                if (currentSearchIndex < searchResults.size - 1) {
                                    currentSearchIndex++
                                    val (msgId, itemIdx) = searchResults[currentSearchIndex]
                                    highlightedMessageId = msgId
                                    coroutineScope.launch {
                                        listState.animateScrollToItem(itemIdx)
                                    }
                                }
                            },
                            modifier = Modifier.size(36.dp)
                        ) {
                            Icon(
                                imageVector = Icons.Default.KeyboardArrowDown,
                                contentDescription = "Next",
                                tint = if (currentSearchIndex < searchResults.size - 1) Color(0xFFE9EDEF) else Color(0xFF8696A0),
                                modifier = Modifier.size(24.dp)
                            )
                        }
                    },
                    colors = TopAppBarDefaults.topAppBarColors(
                        containerColor = Color(0xFF1F2C34)
                    )
                )
            } else {
                TopAppBar(
                    title = {
                        Text(
                            text = bufferName,
                            style = MaterialTheme.typography.titleMedium.copy(
                                fontSize = 18.sp
                            )
                        )
                    },
                    navigationIcon = {
                        IconButton(onClick = onBack) {
                            Icon(
                                imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                                contentDescription = "Back"
                            )
                        }
                    },
                    actions = {
                        IconButton(onClick = {
                            viewModel.loadHighlights()
                            showHighlightsSheet = true
                        }) {
                            Icon(
                                imageVector = Icons.Default.Notifications,
                                contentDescription = "Highlights",
                                tint = Color(0xFF8696A0)
                            )
                        }
                        IconButton(onClick = { showSearch = true }) {
                            Icon(
                                imageVector = Icons.Default.Search,
                                contentDescription = "Search",
                                tint = Color(0xFF8696A0)
                            )
                        }
                        if (urls.isNotEmpty()) {
                            IconButton(onClick = { showUrlList = true }) {
                                Icon(
                                    imageVector = Icons.Default.Link,
                                    contentDescription = "URLs",
                                    tint = Color(0xFF8696A0)
                                )
                            }
                        }
                        if (isChannel) {
                            IconButton(onClick = { showNicklist = true }) {
                                Icon(
                                    imageVector = Icons.Default.Group,
                                    contentDescription = "Nicklist",
                                    tint = Color(0xFF8696A0)
                                )
                            }
                        }
                    },
                    colors = TopAppBarDefaults.topAppBarColors(
                        containerColor = Color(0xFF1F2C34)
                    )
                )
            }
        },
        containerColor = Color(0xFF0B141A)
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .imePadding()
        ) {

            // Connection status banner
            if (connectionState != ConnectionState.CONNECTED) {
                val bannerText = when (connectionState) {
                    ConnectionState.RECONNECTING -> "Reconnecting..."
                    ConnectionState.CONNECTING -> "Connecting..."
                    ConnectionState.DISCONNECTED -> "Disconnected"
                    ConnectionState.ERROR -> "Connection lost"
                    else -> ""
                }
                val bannerColor = when (connectionState) {
                    ConnectionState.RECONNECTING, ConnectionState.CONNECTING -> Color(0xFF1B5E20)
                    else -> Color(0xFFB71C1C)
                }
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .background(bannerColor)
                        .padding(horizontal = 8.dp, vertical = 2.dp),
                    contentAlignment = Alignment.Center
                ) {
                    Text(
                        text = bannerText,
                        style = MaterialTheme.typography.labelSmall.copy(fontSize = 12.sp),
                        color = Color.White,
                        modifier = Modifier
                            .padding(vertical = 4.dp, horizontal = 12.dp)
                    )
                }
            }

            Box(
                modifier = Modifier
                    .weight(1f)
                    .fillMaxWidth()
                    .alpha(contentAlpha)
            ) {
                LazyColumn(
                    modifier = Modifier.fillMaxSize(),
                    state = listState,
                    contentPadding = PaddingValues(horizontal = 6.dp, vertical = 6.dp),
                    verticalArrangement = Arrangement.spacedBy(1.dp)
                ) {
                    item(key = "load_more") {
                        Box(
                            modifier = Modifier
                                .fillMaxWidth()
                                .then(
                                    if (hasMoreHistory) {
                                        Modifier.clickable {
                                            // Remember the currently oldest message
                                            oldestMessageId = messages.firstOrNull()
                                                ?.messages?.firstOrNull()?.id
                                            viewModel.loadMoreHistory()
                                        }
                                    } else Modifier
                                )
                                .padding(vertical = 12.dp),
                            contentAlignment = Alignment.Center
                        ) {
                            Text(
                                text = if (hasMoreHistory) {
                                    "Load earlier messages"
                                } else {
                                    "Beginning of available history"
                                },
                                style = MaterialTheme.typography.labelMedium,
                                color = if (hasMoreHistory) {
                                    Color(0xFF53BDEB)
                                } else {
                                    Color(0xFF8696A0)
                                }
                            )
                        }
                    }

                    messages.forEach { group ->
                        if (group.showTimestamp) {
                            item(key = "ts_${group.id}") {
                                TimestampSeparator(timestamp = group.formattedTimestamp)
                            }
                        }

                        items(
                            items = group.messages,
                            key = { it.id }
                        ) { message ->
                            SwipeableMessage(
                                onSwipeRight = {
                                    replyToNick = group.nick
                                    replyToMessage = message.text
                                    replyToColor = group.nickColor
                                },
                                onSwipeLeft = {
                                    selectedNick = group.nick
                                }
                            ) {
                                MessageBubble(
                                    message = message,
                                    isFirstInGroup = message == group.messages.first(),
                                    isLastInGroup = message == group.messages.last(),
                                    isOwnMessage = group.isOwnMessage,
                                    onNickClick = { nick ->
                                    val text = inputText.text
                                    val suffix = if (text.isEmpty()) "$nick: " else "$nick "
                                    val newText = text + suffix
                                    inputText = TextFieldValue(newText, TextRange(newText.length))
                                },
                                    nick = group.nick,
                                    nickColorValue = group.nickColor,
                                    searchQuery = if (showSearch && searchQuery.length >= 2) searchQuery else null,
                                    isHighlighted = message.id == highlightedMessageId
                                )
                            }
                        }
                    }
                }

                // Scroll to bottom FAB
                androidx.compose.animation.AnimatedVisibility(
                    visible = showScrollToBottom,
                    modifier = Modifier
                        .align(Alignment.BottomEnd)
                        .padding(end = 12.dp, bottom = 12.dp),
                    enter = fadeIn() + scaleIn(),
                    exit = fadeOut() + scaleOut()
                ) {
                    FloatingActionButton(
                        onClick = {
                            coroutineScope.launch {
                                val totalItems = listState.layoutInfo.totalItemsCount
                                if (totalItems > 0) {
                                    listState.animateScrollToItem(totalItems - 1)
                                }
                            }
                        },
                        modifier = Modifier.size(40.dp),
                        shape = CircleShape,
                        containerColor = Color(0xFF1F2C34),
                        contentColor = Color(0xFF8696A0),
                        elevation = FloatingActionButtonDefaults.elevation(
                            defaultElevation = 4.dp
                        )
                    ) {
                        Icon(
                            imageVector = Icons.Default.KeyboardArrowDown,
                            contentDescription = "Scroll to bottom",
                            modifier = Modifier.size(24.dp)
                        )
                    }
                }
            }

            // Reply preview bar
            if (replyToNick != null) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 8.dp, vertical = 4.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Box(
                        modifier = Modifier
                            .width(3.dp)
                            .height(40.dp)
                            .padding(end = 0.dp)
                    ) {
                        Spacer(
                            modifier = Modifier
                                .fillMaxSize()
                                .then(
                                    Modifier.drawBehind {
                                        drawRect(replyToColor)
                                    }
                                )
                        )
                    }
                    Column(
                        modifier = Modifier
                            .weight(1f)
                            .padding(start = 8.dp)
                    ) {
                        Text(
                            text = replyToNick ?: "",
                            style = MaterialTheme.typography.labelSmall.copy(
                                fontSize = 13.sp,
                                fontWeight = FontWeight.Bold
                            ),
                            color = replyToColor
                        )
                        Text(
                            text = replyToMessage ?: "",
                            style = MaterialTheme.typography.bodySmall.copy(fontSize = 13.sp),
                            color = Color(0xFF8696A0),
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                    }
                    IconButton(
                        onClick = {
                            replyToNick = null
                            replyToMessage = null
                        },
                        modifier = Modifier.size(32.dp)
                    ) {
                        Icon(
                            imageVector = Icons.Default.Close,
                            contentDescription = "Cancel reply",
                            tint = Color(0xFF8696A0),
                            modifier = Modifier.size(18.dp)
                        )
                    }
                }
            }

            // Nick autocomplete suggestions
            val nickSuggestions = remember(inputText.text, nicklist) {
                val text = inputText.text
                if (text.isBlank()) return@remember emptyList()
                val lastSpaceIdx = text.lastIndexOf(' ')
                val currentWord = if (lastSpaceIdx >= 0) text.substring(lastSpaceIdx + 1) else text
                if (currentWord.length < 2) return@remember emptyList()
                val lower = currentWord.lowercase()
                nicklist
                    .map { it.name }
                    .filter { it.lowercase().startsWith(lower) && it.lowercase() != lower }
                    .take(8)
            }

            if (nickSuggestions.isNotEmpty()) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .horizontalScroll(rememberScrollState())
                        .padding(horizontal = 8.dp, vertical = 2.dp),
                    horizontalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    for (nick in nickSuggestions) {
                        Box(
                            modifier = Modifier
                                .clickable {
                                    val text = inputText.text
                                    val lastSpaceIdx = text.lastIndexOf(' ')
                                    val prefix = if (lastSpaceIdx >= 0) text.substring(0, lastSpaceIdx + 1) else ""
                                    val suffix = if (prefix.isEmpty()) ": " else " "
                                    val newText = "$prefix$nick$suffix"
                                    inputText = TextFieldValue(newText, TextRange(newText.length))
                                }
                                .background(Color(0xFF1F2C34), MaterialTheme.shapes.small)
                                .padding(horizontal = 12.dp, vertical = 6.dp)
                        ) {
                            Text(
                                text = nick,
                                style = MaterialTheme.typography.bodySmall.copy(fontSize = 14.sp),
                                color = nickColor(nick)
                            )
                        }
                    }
                }
            }

            // Input bar
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 4.dp, vertical = 4.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                OutlinedTextField(
                    value = inputText,
                    onValueChange = { inputText = it },
                    placeholder = {
                        Text("Message", color = Color(0xFF8696A0))
                    },
                    modifier = Modifier
                        .weight(1f)
                        .focusRequester(inputFieldFocusRequester),
                    shape = MaterialTheme.shapes.extraLarge,
                    singleLine = false,
                    maxLines = 4,
                    colors = OutlinedTextFieldDefaults.colors(
                        focusedContainerColor = Color(0xFF1F2C34),
                        unfocusedContainerColor = Color(0xFF1F2C34),
                        focusedBorderColor = Color.Transparent,
                        unfocusedBorderColor = Color.Transparent,
                        focusedTextColor = Color(0xFFE9EDEF),
                        unfocusedTextColor = Color(0xFFE9EDEF),
                        cursorColor = Color(0xFF00A884)
                    )
                )

                FloatingActionButton(
                    onClick = {
                        if (inputText.text.isNotBlank()) {
                            val msg = if (replyToNick != null) {
                                "${replyToNick}: ${inputText.text.trim()}"
                            } else {
                                inputText.text.trim()
                            }
                            if (viewModel.sendMessage(msg)) {
                                inputText = TextFieldValue("")
                                replyToNick = null
                                replyToMessage = null
                                justSentMessage = true
                            } else {
                                // Connection is down — keep the text so it isn't lost.
                                Toast.makeText(
                                    context,
                                    "Not connected — message not sent",
                                    Toast.LENGTH_SHORT
                                ).show()
                            }
                        }
                    },
                    modifier = Modifier
                        .padding(start = 4.dp)
                        .size(48.dp),
                    shape = CircleShape,
                    containerColor = Color(0xFF00A884),
                    elevation = FloatingActionButtonDefaults.elevation(
                        defaultElevation = 2.dp
                    )
                ) {
                    Icon(
                        imageVector = Icons.AutoMirrored.Filled.Send,
                        contentDescription = "Send",
                        tint = Color(0xFF0B141A),
                        modifier = Modifier.size(20.dp)
                    )
                }
            }
        }
    }
}

@Composable
private fun TimestampSeparator(timestamp: String) {
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 8.dp),
        contentAlignment = Alignment.Center
    ) {
        Text(
            text = timestamp,
            style = MaterialTheme.typography.labelSmall.copy(
                fontSize = 11.sp
            ),
            color = Color(0xFF8696A0),
            modifier = Modifier
                .padding(horizontal = 12.dp, vertical = 4.dp)
        )
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun NicklistSheet(
    nicks: List<String>,
    onDismiss: () -> Unit,
    onNickClick: (String) -> Unit
) {
    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = rememberModalBottomSheetState(),
        containerColor = Color(0xFF1F2C34),
        dragHandle = null
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(top = 20.dp, bottom = 24.dp)
        ) {
            Text(
                text = "Users (${nicks.size})",
                style = MaterialTheme.typography.titleMedium.copy(
                    fontSize = 18.sp,
                    fontWeight = FontWeight.SemiBold
                ),
                color = Color(0xFFE9EDEF),
                modifier = Modifier.padding(start = 20.dp, end = 20.dp, bottom = 12.dp)
            )

            HorizontalDivider(color = Color(0xFF233039))

            LazyColumn(
                modifier = Modifier.height(400.dp)
            ) {
                items(items = nicks, key = { it }) { nick ->
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable { onNickClick(nick) }
                            .padding(horizontal = 20.dp, vertical = 12.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        // Colored dot
                        val color = nickColor(nick)
                        androidx.compose.foundation.Canvas(
                            modifier = Modifier.size(10.dp)
                        ) {
                            drawCircle(color = color)
                        }
                        Spacer(modifier = Modifier.width(12.dp))
                        Text(
                            text = nick,
                            style = MaterialTheme.typography.bodyLarge.copy(fontSize = 15.sp),
                            color = Color(0xFFE9EDEF)
                        )
                    }
                }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun UrlListSheet(
    urls: List<Pair<String, String>>, // url to nick
    onDismiss: () -> Unit
) {
    val uriHandler = LocalUriHandler.current

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = rememberModalBottomSheetState(),
        containerColor = Color(0xFF1F2C34),
        dragHandle = null
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(top = 20.dp, bottom = 24.dp)
        ) {
            Text(
                text = "Links (${urls.size})",
                style = MaterialTheme.typography.titleMedium.copy(
                    fontSize = 18.sp,
                    fontWeight = FontWeight.SemiBold
                ),
                color = Color(0xFFE9EDEF),
                modifier = Modifier.padding(start = 20.dp, end = 20.dp, bottom = 12.dp)
            )

            HorizontalDivider(color = Color(0xFF233039))

            LazyColumn(
                modifier = Modifier.height(400.dp)
            ) {
                items(items = urls, key = { it.first }) { (url, nick) ->
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable { uriHandler.openUri(url) }
                            .padding(horizontal = 20.dp, vertical = 10.dp)
                    ) {
                        Text(
                            text = url,
                            style = MaterialTheme.typography.bodyMedium.copy(fontSize = 14.sp),
                            color = Color(0xFF53BDEB),
                            maxLines = 2,
                            overflow = TextOverflow.Ellipsis
                        )
                        Text(
                            text = "by $nick",
                            style = MaterialTheme.typography.labelSmall.copy(fontSize = 11.sp),
                            color = Color(0xFF8696A0),
                            modifier = Modifier.padding(top = 2.dp)
                        )
                    }
                    HorizontalDivider(
                        modifier = Modifier.padding(horizontal = 20.dp),
                        color = Color(0xFF233039)
                    )
                }
            }
        }
    }
}

@Composable
private fun SwipeableMessage(
    onSwipeRight: () -> Unit,
    onSwipeLeft: () -> Unit,
    content: @Composable () -> Unit
) {
    var offsetX by remember { mutableStateOf(0f) }
    val animatedOffset by animateFloatAsState(
        targetValue = offsetX,
        animationSpec = tween(durationMillis = if (offsetX == 0f) 200 else 0),
        label = "swipeOffset"
    )
    val threshold = 80f
    var triggered by remember { mutableStateOf(false) }
    val haptic = LocalHapticFeedback.current

    Box(
        modifier = Modifier
            .fillMaxWidth()
            .pointerInput(Unit) {
                awaitEachGesture {
                    val down = awaitFirstDown(requireUnconsumed = false)
                    var totalX = 0f
                    var totalY = 0f
                    var directionDecided = false
                    var isHorizontal = false

                    do {
                        val event = awaitPointerEvent()
                        val change = event.changes.firstOrNull() ?: break
                        val dx = change.position.x - change.previousPosition.x
                        val dy = change.position.y - change.previousPosition.y
                        totalX += dx
                        totalY += dy

                        if (!directionDecided) {
                            // Wait until finger has moved enough to decide direction
                            if (kotlin.math.abs(totalX) > 30f || kotlin.math.abs(totalY) > 30f) {
                                directionDecided = true
                                isHorizontal = kotlin.math.abs(totalX) > kotlin.math.abs(totalY) * 1.5f
                            }
                        }

                        if (directionDecided && isHorizontal) {
                            change.consume()
                            val newOffset = offsetX + dx
                            offsetX = newOffset.coerceIn(-200f, 200f)
                            if ((offsetX > threshold || offsetX < -threshold) && !triggered) {
                                triggered = true
                                haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                            }
                        }
                    } while (event.changes.any { it.pressed })

                    if (isHorizontal) {
                        if (offsetX > threshold) onSwipeRight()
                        else if (offsetX < -threshold) onSwipeLeft()
                    }
                    offsetX = 0f
                    triggered = false
                }
            }
    ) {
        // Reply icon (right swipe)
        if (animatedOffset > 10f) {
            Box(
                modifier = Modifier
                    .align(Alignment.CenterStart)
                    .padding(start = 16.dp)
                    .size(32.dp),
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    imageVector = Icons.AutoMirrored.Filled.Reply,
                    contentDescription = "Reply",
                    tint = Color(0xFF00A884).copy(
                        alpha = (animatedOffset / threshold).coerceIn(0f, 1f)
                    ),
                    modifier = Modifier.size(22.dp)
                )
            }
        }

        // Nick menu icon (left swipe)
        if (animatedOffset < -10f) {
            Box(
                modifier = Modifier
                    .align(Alignment.CenterEnd)
                    .padding(end = 16.dp)
                    .size(32.dp),
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    imageVector = Icons.Default.Person,
                    contentDescription = "Nick actions",
                    tint = Color(0xFF00A884).copy(
                        alpha = ((-animatedOffset) / threshold).coerceIn(0f, 1f)
                    ),
                    modifier = Modifier.size(22.dp)
                )
            }
        }

        Box(
            modifier = Modifier.offset(x = with(LocalDensity.current) { animatedOffset.toDp() })
        ) {
            content()
        }
    }
}
