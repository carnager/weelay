package com.weelay.app.ui.screens

import com.weelay.app.ui.theme.Palette
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
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.foundation.gestures.scrollBy
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material.icons.filled.History
import androidx.compose.foundation.layout.widthIn
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.material3.TextButton
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.text.AnnotatedString
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
import com.weelay.app.ui.components.CompactMessageRow
import com.weelay.app.ui.components.MessageBubble
import com.weelay.app.ui.components.NickActionSheet
import com.weelay.app.ui.theme.nickColor
import com.weelay.app.viewmodel.ChatViewModel
import com.weelay.app.relay.RelayNicklistEntry
import com.weelay.app.viewmodel.DisplayMessage
import com.weelay.app.viewmodel.MessageGroup
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ChatScreen(
    bufferFullName: String,
    targetMessageId: Long? = null,
    /** Text shared from another app, put into the input for the user to edit and send. */
    initialDraft: String? = null,
    onDraftConsumed: () -> Unit = {},
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
    val readMarkerId by viewModel.readMarkerId.collectAsState()
    val bufferTitle by viewModel.bufferTitle.collectAsState()
    val isChannel by viewModel.isChannel.collectAsState()
    val showLinkPreviews = remember { viewModel.linkPreviews }
    val compact = remember(isChannel) { viewModel.compactLayout(isChannel) }
    var showTopic by remember { mutableStateOf(false) }
    var messageMenuFor by remember { mutableStateOf<Pair<MessageGroup, DisplayMessage>?>(null) }
    // A kick or ban waiting for confirmation: the command to send, and a description of it.
    var pendingModAction by remember { mutableStateOf<Pair<String, String>?>(null) }
    var inputText by remember { mutableStateOf(TextFieldValue("")) }
    val sentHistory by viewModel.sentHistory.collectAsState()
    var showHistoryMenu by remember { mutableStateOf(false) }
    // Hardware-keyboard recall: which history entry is in the input (-1 = none), and what was
    // typed before recalling started, so Down past the newest entry gives it back.
    var historyIndex by remember { mutableStateOf(-1) }
    var historyDraft by remember { mutableStateOf("") }
    fun recall(index: Int) {
        val text = if (index < 0) historyDraft else sentHistory[index]
        historyIndex = index
        inputText = TextFieldValue(text, TextRange(text.length))
    }
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
    val clipboard = LocalClipboardManager.current
    val uriHandler = LocalUriHandler.current
    val haptic = LocalHapticFeedback.current
    var replyToNick by remember { mutableStateOf<String?>(null) }
    var replyToMessage by remember { mutableStateOf<String?>(null) }
    var replyToColor by remember { mutableStateOf(Palette.accent) }
    var justSentMessage by remember { mutableStateOf(false) }
    var oldestMessageId by remember { mutableStateOf<Long?>(null) }

    val showScrollToBottom by remember {
        derivedStateOf {
            val layoutInfo = listState.layoutInfo
            val lastVisibleItem = layoutInfo.visibleItemsInfo.lastOrNull()?.index ?: 0
            val totalItems = layoutInfo.totalItemsCount
            totalItems > 0 && lastVisibleItem < totalItems - 3
        }
    }

    // Stay pinned to the bottom while content there grows. Link previews load after the list is
    // laid out and make the last message taller, which used to leave the view stranded a little
    // short of the newest message. Pinned once the list reaches the end; released as soon as the
    // user scrolls away from it.
    var stickToBottom by remember { mutableStateOf(false) }
    val pinning = remember { booleanArrayOf(false) } // our own corrective scroll is in flight
    LaunchedEffect(listState) {
        snapshotFlow { listState.isScrollInProgress to listState.canScrollForward }
            .collect { (scrolling, canScrollForward) ->
                if (pinning[0]) return@collect
                if (!canScrollForward) stickToBottom = true
                else if (scrolling) stickToBottom = false
            }
    }
    LaunchedEffect(listState) {
        // How far the end of the list sits below the viewport: 0 when pinned exactly, null for an
        // empty list, and PUSHED_OUT when growth further up pushed the last item off screen.
        snapshotFlow {
            val info = listState.layoutInfo
            val last = info.visibleItemsInfo.lastOrNull()
            when {
                last == null -> null
                last.index != info.totalItemsCount - 1 -> PUSHED_OUT
                else -> last.offset + last.size + info.afterContentPadding - info.viewportEndOffset
            }
        }.collect { overflow ->
            if (overflow == null || overflow <= 0) return@collect
            if (!stickToBottom || listState.isScrollInProgress) return@collect
            pinning[0] = true
            try {
                if (overflow == PUSHED_OUT) {
                    listState.scrollToItem(listState.layoutInfo.totalItemsCount - 1)
                } else {
                    listState.scrollBy(overflow.toFloat())
                }
            } finally {
                pinning[0] = false
            }
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

    LaunchedEffect(initialDraft) {
        if (initialDraft != null) {
            inputText = TextFieldValue(initialDraft, TextRange(initialDraft.length))
            onDraftConsumed()
            inputFieldFocusRequester.requestFocus()
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

                // Where to open: a message jumped to from a highlight wins, then the first unread
                // message, then the bottom.
                val targetIndex = targetMessageId?.let { itemIndexOf(messages, it) }
                if (targetIndex != null && !scrolledToTarget) {
                    highlightedMessageId = targetMessageId
                    scrolledToTarget = true
                }
                val openIndex = targetIndex
                    ?: readMarkerId?.let { itemIndexOf(messages, it) }
                    ?: lastIndex
                listState.scrollToItem(openIndex)
                if (!contentReady) {
                    initialLoadTime = System.currentTimeMillis()
                    contentReady = true
                    delay(300)
                    // Re-scroll after settle (link previews etc. may have shifted layout)
                    listState.scrollToItem(openIndex)
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
            // Kick and ban are a swipe and a tap away and can't be undone from here, so they ask
            // first. Quiet is milder and stays one tap.
            onBan = {
                pendingModAction = "/ban $nick" to "Ban $nick from $bufferName?"
            },
            onKick = {
                pendingModAction = "/kick $nick" to "Kick $nick from $bufferName?"
            },
            onQuiet = {
                viewModel.sendMessage("/quiet $nick")
            }
        )
    }

    pendingModAction?.let { (command, question) ->
        AlertDialog(
            onDismissRequest = { pendingModAction = null },
            text = { Text(question) },
            confirmButton = {
                TextButton(onClick = {
                    viewModel.sendMessage(command)
                    pendingModAction = null
                }) { Text(if (command.startsWith("/ban")) "Ban" else "Kick", color = Palette.danger) }
            },
            dismissButton = {
                TextButton(onClick = { pendingModAction = null }) { Text("Cancel") }
            },
            containerColor = Palette.surface
        )
    }

    if (showTopic && bufferTitle.isNotEmpty()) {
        AlertDialog(
            onDismissRequest = { showTopic = false },
            title = { Text(bufferName) },
            text = {
                Column {
                    SelectionContainer {
                        Text(bufferTitle, style = MaterialTheme.typography.bodyMedium)
                    }
                    // Selectable text can't also take link taps, so the topic's links get their own
                    // rows underneath.
                    val topicLinks = remember(bufferTitle) {
                        TOPIC_URL.findAll(bufferTitle).map { it.value.trimEnd('.', ',', ')') }
                            .distinct().toList()
                    }
                    topicLinks.forEach { url ->
                        Text(
                            text = url,
                            style = MaterialTheme.typography.bodyMedium,
                            color = Palette.link,
                            textDecoration = TextDecoration.Underline,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable { uriHandler.openUri(url) }
                                .padding(top = 12.dp)
                        )
                    }
                }
            },
            confirmButton = {
                TextButton(onClick = { showTopic = false }) { Text("Close") }
            },
            containerColor = Palette.surface
        )
    }

    messageMenuFor?.let { (group, message) ->
        MessageActionSheet(
            onDismiss = { messageMenuFor = null },
            onCopy = {
                clipboard.setText(AnnotatedString(message.text))
                // Android 13+ shows its own confirmation when something is copied.
                if (android.os.Build.VERSION.SDK_INT < android.os.Build.VERSION_CODES.TIRAMISU) {
                    Toast.makeText(context, "Copied", Toast.LENGTH_SHORT).show()
                }
            },
            onReply = {
                replyToNick = group.nick
                replyToMessage = message.text
                replyToColor = group.nickColor
            },
            onNickActions = if (group.isOwnMessage) null else ({ selectedNick = group.nick })
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
            nicks = nicklist,
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
                            placeholder = { Text("Search...", color = Palette.muted) },
                            modifier = Modifier
                                .fillMaxWidth()
                                .focusRequester(inputFocusRequester),
                            singleLine = true,
                            textStyle = MaterialTheme.typography.bodyMedium.copy(
                                fontSize = 15.sp,
                                color = Palette.onSurface
                            ),
                            colors = OutlinedTextFieldDefaults.colors(
                                focusedContainerColor = Color.Transparent,
                                unfocusedContainerColor = Color.Transparent,
                                focusedBorderColor = Color.Transparent,
                                unfocusedBorderColor = Color.Transparent,
                                cursorColor = Palette.accent
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
                                tint = Palette.onSurface
                            )
                        }
                    },
                    actions = {
                        if (searchResults.isNotEmpty()) {
                            Text(
                                text = "${currentSearchIndex + 1}/${searchResults.size}",
                                color = Palette.muted,
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
                                tint = if (currentSearchIndex > 0) Palette.onSurface else Palette.muted,
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
                                tint = if (currentSearchIndex < searchResults.size - 1) Palette.onSurface else Palette.muted,
                                modifier = Modifier.size(24.dp)
                            )
                        }
                    },
                    colors = TopAppBarDefaults.topAppBarColors(
                        containerColor = Palette.surface
                    )
                )
            } else {
                TopAppBar(
                    title = {
                        Column(
                            modifier = if (bufferTitle.isNotEmpty()) {
                                Modifier.clickable { showTopic = true }
                            } else Modifier
                        ) {
                            Text(
                                text = bufferName,
                                style = MaterialTheme.typography.titleMedium.copy(
                                    fontSize = 18.sp
                                ),
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis
                            )
                            if (bufferTitle.isNotEmpty()) {
                                Text(
                                    text = bufferTitle,
                                    style = MaterialTheme.typography.bodySmall.copy(fontSize = 12.sp),
                                    color = Palette.muted,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis
                                )
                            }
                        }
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
                                tint = Palette.muted
                            )
                        }
                        IconButton(onClick = { showSearch = true }) {
                            Icon(
                                imageVector = Icons.Default.Search,
                                contentDescription = "Search",
                                tint = Palette.muted
                            )
                        }
                        if (urls.isNotEmpty()) {
                            IconButton(onClick = { showUrlList = true }) {
                                Icon(
                                    imageVector = Icons.Default.Link,
                                    contentDescription = "URLs",
                                    tint = Palette.muted
                                )
                            }
                        }
                        if (isChannel) {
                            IconButton(onClick = { showNicklist = true }) {
                                Icon(
                                    imageVector = Icons.Default.Group,
                                    contentDescription = "Nicklist",
                                    tint = Palette.muted
                                )
                            }
                        }
                    },
                    colors = TopAppBarDefaults.topAppBarColors(
                        containerColor = Palette.surface
                    )
                )
            }
        },
        containerColor = Palette.background
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
                    // Amber: in progress, not yet a failure — green read as "all good".
                    ConnectionState.RECONNECTING, ConnectionState.CONNECTING -> Palette.bannerProgress
                    else -> Palette.bannerError
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
                                    Palette.link
                                } else {
                                    Palette.muted
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
                            // Drawn inside the message's own item rather than as a list item of its
                            // own, so the item indices that search and jumps compute stay valid.
                            Column {
                                if (message.id == readMarkerId) NewMessagesDivider()
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
                                    val insertNick: (String) -> Unit = { nick ->
                                        val text = inputText.text
                                        val suffix = if (text.isEmpty()) "$nick: " else "$nick "
                                        val newText = text + suffix
                                        inputText = TextFieldValue(newText, TextRange(newText.length))
                                    }
                                    val openMenu = {
                                        haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                                        messageMenuFor = group to message
                                    }
                                    val query = if (showSearch && searchQuery.length >= 2) searchQuery else null
                                    if (compact) {
                                        CompactMessageRow(
                                            message = message,
                                            nick = group.nick,
                                            nickColor = group.nickColor,
                                            isFirstInGroup = message == group.messages.first(),
                                            searchQuery = query,
                                            isHighlighted = message.id == highlightedMessageId,
                                            showLinkPreview = showLinkPreviews,
                                            onNickClick = insertNick,
                                            onLongPress = openMenu
                                        )
                                    } else {
                                        MessageBubble(
                                            message = message,
                                            isFirstInGroup = message == group.messages.first(),
                                            isLastInGroup = message == group.messages.last(),
                                            isOwnMessage = group.isOwnMessage,
                                            onNickClick = insertNick,
                                            nick = group.nick,
                                            nickColorValue = group.nickColor,
                                            searchQuery = query,
                                            isHighlighted = message.id == highlightedMessageId,
                                            showLinkPreview = showLinkPreviews,
                                            onLongPress = openMenu
                                        )
                                    }
                                }
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
                        containerColor = Palette.surface,
                        contentColor = Palette.muted,
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
                            color = Palette.muted,
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
                            tint = Palette.muted,
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
                                .background(Palette.surface, MaterialTheme.shapes.small)
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
                    onValueChange = {
                        // Editing a recalled entry makes it a new draft rather than history.
                        if (it.text != inputText.text) historyIndex = -1
                        inputText = it
                    },
                    placeholder = {
                        Text("Message", color = Palette.muted)
                    },
                    trailingIcon = if (inputText.text.isEmpty() && sentHistory.isNotEmpty()) {
                        {
                            Box {
                                IconButton(onClick = { showHistoryMenu = true }) {
                                    Icon(
                                        imageVector = Icons.Default.History,
                                        contentDescription = "Sent messages",
                                        tint = Palette.muted
                                    )
                                }
                                DropdownMenu(
                                    expanded = showHistoryMenu,
                                    onDismissRequest = { showHistoryMenu = false },
                                    modifier = Modifier
                                        .background(Palette.surface)
                                        .widthIn(max = 320.dp)
                                ) {
                                    sentHistory.take(15).forEach { entry ->
                                        DropdownMenuItem(
                                            text = {
                                                Text(
                                                    entry,
                                                    color = Palette.onSurface,
                                                    maxLines = 2,
                                                    overflow = TextOverflow.Ellipsis
                                                )
                                            },
                                            onClick = {
                                                showHistoryMenu = false
                                                inputText = TextFieldValue(entry, TextRange(entry.length))
                                                inputFieldFocusRequester.requestFocus()
                                            }
                                        )
                                    }
                                }
                            }
                        }
                    } else null,
                    modifier = Modifier
                        .weight(1f)
                        .focusRequester(inputFieldFocusRequester)
                        // Up/Down on a hardware keyboard step through what was sent, as in
                        // WeeChat. Up only takes over while the input is empty or already showing
                        // a recalled entry, so it still moves the cursor in a typed draft.
                        .onPreviewKeyEvent { event ->
                            if (event.type != KeyEventType.KeyDown) return@onPreviewKeyEvent false
                            when (event.key) {
                                Key.DirectionUp -> {
                                    val browsing = historyIndex >= 0 || inputText.text.isEmpty()
                                    if (!browsing || historyIndex >= sentHistory.size - 1) {
                                        return@onPreviewKeyEvent false
                                    }
                                    if (historyIndex < 0) historyDraft = inputText.text
                                    recall(historyIndex + 1)
                                    true
                                }
                                Key.DirectionDown -> {
                                    if (historyIndex < 0) return@onPreviewKeyEvent false
                                    recall(historyIndex - 1)
                                    true
                                }
                                else -> false
                            }
                        },
                    shape = MaterialTheme.shapes.extraLarge,
                    singleLine = false,
                    maxLines = 4,
                    colors = OutlinedTextFieldDefaults.colors(
                        focusedContainerColor = Palette.surface,
                        unfocusedContainerColor = Palette.surface,
                        focusedBorderColor = Color.Transparent,
                        unfocusedBorderColor = Color.Transparent,
                        focusedTextColor = Palette.onSurface,
                        unfocusedTextColor = Palette.onSurface,
                        cursorColor = Palette.accent
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
                            if (viewModel.sendTypedInput(msg)) {
                                historyIndex = -1
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
                    containerColor = Palette.accent,
                    elevation = FloatingActionButtonDefaults.elevation(
                        defaultElevation = 2.dp
                    )
                ) {
                    Icon(
                        imageVector = Icons.AutoMirrored.Filled.Send,
                        contentDescription = "Send",
                        tint = Palette.onAccent,
                        modifier = Modifier.size(20.dp)
                    )
                }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun MessageActionSheet(
    onDismiss: () -> Unit,
    onCopy: () -> Unit,
    onReply: () -> Unit,
    onNickActions: (() -> Unit)?
) {
    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = rememberModalBottomSheetState(),
        containerColor = Palette.surface,
        dragHandle = null
    ) {
        Column(modifier = Modifier.padding(vertical = 12.dp)) {
            MessageActionRow(Icons.Default.ContentCopy, "Copy text") { onCopy(); onDismiss() }
            MessageActionRow(Icons.AutoMirrored.Filled.Reply, "Reply") { onReply(); onDismiss() }
            onNickActions?.let { action ->
                MessageActionRow(Icons.Default.Person, "Nick actions") { onDismiss(); action() }
            }
        }
    }
}

@Composable
private fun MessageActionRow(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    label: String,
    onClick: () -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(horizontal = 20.dp, vertical = 14.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Icon(icon, contentDescription = null, tint = Palette.onSurface, modifier = Modifier.size(24.dp))
        Spacer(modifier = Modifier.width(16.dp))
        Text(label, style = MaterialTheme.typography.bodyLarge.copy(fontSize = 16.sp), color = Palette.onSurface)
    }
}

/** Sentinel for the bottom-pinning logic: the last item was pushed out of the viewport. */
private const val PUSHED_OUT = Int.MAX_VALUE

private val TOPIC_URL = Regex("""https?://[^\s<>"{}|\\^`\[\]]+""")

/**
 * LazyColumn index of the message with [id]: one item for the "load earlier" row, then per group
 * an optional timestamp separator followed by one item per message. Null if it isn't loaded.
 */
private fun itemIndexOf(groups: List<MessageGroup>, id: Long): Int? {
    var index = 1
    for (group in groups) {
        if (group.showTimestamp) index++
        for (msg in group.messages) {
            if (msg.id == id) return index
            index++
        }
    }
    return null
}

@Composable
private fun NewMessagesDivider() {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(top = 10.dp, bottom = 2.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        HorizontalDivider(modifier = Modifier.weight(1f), color = Palette.accent)
        Text(
            text = "New messages",
            style = MaterialTheme.typography.labelSmall.copy(fontSize = 11.sp),
            color = Palette.accent,
            modifier = Modifier.padding(horizontal = 8.dp)
        )
        HorizontalDivider(modifier = Modifier.weight(1f), color = Palette.accent)
    }
}

@Composable
private fun TimestampSeparator(timestamp: String) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 6.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        HorizontalDivider(modifier = Modifier.weight(1f), color = Palette.divider)
        Text(
            text = timestamp,
            style = MaterialTheme.typography.labelSmall.copy(
                fontSize = 11.sp,
                fontFamily = androidx.compose.ui.text.font.FontFamily.Monospace
            ),
            color = Palette.muted,
            modifier = Modifier.padding(horizontal = 8.dp)
        )
        HorizontalDivider(modifier = Modifier.weight(1f), color = Palette.divider)
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun NicklistSheet(
    nicks: List<RelayNicklistEntry>,
    onDismiss: () -> Unit,
    onNickClick: (String) -> Unit
) {
    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = rememberModalBottomSheetState(),
        containerColor = Palette.surface,
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
                color = Palette.onSurface,
                modifier = Modifier.padding(start = 20.dp, end = 20.dp, bottom = 12.dp)
            )

            HorizontalDivider(color = Palette.divider)

            LazyColumn(
                modifier = Modifier.height(400.dp)
            ) {
                items(items = nicks, key = { it.name }) { entry ->
                    val nick = entry.name
                    val mode = entry.prefix.trim()
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
                        // Fixed-width slot so names line up whether or not a nick has a mode.
                        Text(
                            text = mode,
                            style = MaterialTheme.typography.bodyLarge.copy(
                                fontSize = 15.sp,
                                fontWeight = FontWeight.Bold
                            ),
                            color = when (mode.firstOrNull()) {
                                '~', '&', '@' -> Color(0xFF81C784)
                                '%' -> Color(0xFFFFB74D)
                                '+' -> Color(0xFFFFF176)
                                else -> Palette.muted
                            },
                            modifier = Modifier.width(14.dp)
                        )
                        Text(
                            text = nick,
                            style = MaterialTheme.typography.bodyLarge.copy(fontSize = 15.sp),
                            color = Palette.onSurface
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
        containerColor = Palette.surface,
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
                color = Palette.onSurface,
                modifier = Modifier.padding(start = 20.dp, end = 20.dp, bottom = 12.dp)
            )

            HorizontalDivider(color = Palette.divider)

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
                            color = Palette.link,
                            maxLines = 2,
                            overflow = TextOverflow.Ellipsis
                        )
                        Text(
                            text = "by $nick",
                            style = MaterialTheme.typography.labelSmall.copy(fontSize = 11.sp),
                            color = Palette.muted,
                            modifier = Modifier.padding(top = 2.dp)
                        )
                    }
                    HorizontalDivider(
                        modifier = Modifier.padding(horizontal = 20.dp),
                        color = Palette.divider
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
                    tint = Palette.accent.copy(
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
                    tint = Palette.accent.copy(
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
