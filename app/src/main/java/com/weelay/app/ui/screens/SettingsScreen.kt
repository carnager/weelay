package com.weelay.app.ui.screens

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.Chat
import androidx.compose.material.icons.automirrored.filled.Logout
import androidx.compose.material.icons.filled.AlternateEmail
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Code
import androidx.compose.material.icons.filled.Dns
import androidx.compose.material.icons.filled.ExpandLess
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material.icons.filled.Link
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.Tag
import androidx.compose.material.icons.filled.Visibility
import androidx.compose.material.icons.filled.VisibilityOff
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.navigation.compose.hiltViewModel
import com.weelay.app.data.MessageLayout
import com.weelay.app.relay.ConnectionState
import com.weelay.app.ui.theme.AppStyle
import com.weelay.app.ui.theme.Palette
import com.weelay.app.viewmodel.SettingsViewModel

private val LAYOUT_OPTIONS = listOf(
    MessageLayout.LINES to "Lines",
    MessageLayout.GROUPED to "Grouped",
    MessageLayout.BUBBLES to "Bubbles"
)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(
    onBack: () -> Unit,
    onDisconnected: () -> Unit,
    viewModel: SettingsViewModel = hiltViewModel()
) {
    val uiState by viewModel.uiState.collectAsState()
    val connectionState by viewModel.connectionState.collectAsState()
    val uriHandler = LocalUriHandler.current
    // The connection form is rarely needed, so it starts folded into a summary row.
    var editingConnection by rememberSaveable { mutableStateOf(false) }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Text(
                        "Settings",
                        style = MaterialTheme.typography.titleLarge.copy(fontSize = 20.sp, fontWeight = FontWeight.SemiBold),
                        color = Palette.onSurface
                    )
                },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back", tint = Palette.onSurface)
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = Palette.background)
            )
        },
        containerColor = Palette.background
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 16.dp, vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            // --- Connection ---------------------------------------------------------------
            SectionTitle("Connection")
            SettingsCard {
                val (status, dot) = when (connectionState) {
                    ConnectionState.CONNECTED -> "Connected" to Palette.ok
                    ConnectionState.CONNECTING -> "Connecting…" to Palette.accent
                    ConnectionState.RECONNECTING -> "Reconnecting…" to Palette.accent
                    ConnectionState.DISCONNECTED -> "Disconnected" to Palette.danger
                    ConnectionState.ERROR -> "Connection lost" to Palette.danger
                }
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable { editingConnection = !editingConnection }
                        .padding(16.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    RowIcon(Icons.Default.Dns)
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            "${uiState.host}:${uiState.port}",
                            style = MaterialTheme.typography.bodyLarge.copy(fontSize = 17.sp),
                            color = Palette.onSurface
                        )
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Box(modifier = Modifier.size(8.dp).background(dot, CircleShape))
                            Spacer(modifier = Modifier.width(6.dp))
                            Text(
                                status + if (uiState.useSsl) " · TLS" else " · unencrypted",
                                style = MaterialTheme.typography.bodyMedium.copy(fontSize = 14.sp),
                                color = Palette.muted
                            )
                        }
                    }
                    Icon(
                        if (editingConnection) Icons.Default.ExpandLess else Icons.Default.ExpandMore,
                        contentDescription = if (editingConnection) "Hide connection details" else "Edit connection",
                        tint = Palette.muted
                    )
                }
                AnimatedVisibility(visible = editingConnection) {
                    ConnectionForm(
                        host = uiState.host,
                        port = uiState.port,
                        password = uiState.password,
                        useSsl = uiState.useSsl,
                        onHostChange = viewModel::onHostChange,
                        onPortChange = viewModel::onPortChange,
                        onPasswordChange = viewModel::onPasswordChange,
                        onSslChange = viewModel::onSslChange,
                        onSave = {
                            viewModel.saveAndReconnect()
                            editingConnection = false
                        }
                    )
                }
                CardDivider()
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable {
                            viewModel.disconnect()
                            onDisconnected()
                        }
                        .padding(16.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    RowIcon(Icons.AutoMirrored.Filled.Logout, tint = Palette.danger)
                    Column {
                        Text("Disconnect", style = MaterialTheme.typography.bodyLarge.copy(fontSize = 17.sp), color = Palette.danger)
                        Text(
                            "Stops the background service; Weelay stays offline until you connect again.",
                            style = MaterialTheme.typography.bodyMedium.copy(fontSize = 14.sp),
                            color = Palette.muted
                        )
                    }
                }
            }

            // --- Appearance ---------------------------------------------------------------
            SectionTitle("Appearance")
            SettingsCard {
                Column(modifier = Modifier.padding(16.dp)) {
                    Text("Style", style = MaterialTheme.typography.bodyLarge.copy(fontSize = 17.sp), color = Palette.onSurface)
                    Spacer(modifier = Modifier.height(12.dp))
                    Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                        StyleTile(
                            style = AppStyle.RELAY,
                            name = "Relay",
                            selected = uiState.style == AppStyle.RELAY,
                            onClick = { viewModel.onStyleChange(AppStyle.RELAY) },
                            modifier = Modifier.weight(1f)
                        )
                        StyleTile(
                            style = AppStyle.THREADS,
                            name = "Threads",
                            selected = uiState.style == AppStyle.THREADS,
                            onClick = { viewModel.onStyleChange(AppStyle.THREADS) },
                            modifier = Modifier.weight(1f)
                        )
                    }
                }
                CardDivider()
                ChoiceRow(
                    icon = Icons.Default.Tag,
                    title = "Channel messages",
                    options = LAYOUT_OPTIONS,
                    selected = uiState.channelLayout,
                    onChange = viewModel::onChannelLayoutChange
                )
                CardDivider()
                ChoiceRow(
                    icon = Icons.AutoMirrored.Filled.Chat,
                    title = "Private chat messages",
                    options = LAYOUT_OPTIONS,
                    selected = uiState.privateLayout,
                    onChange = viewModel::onPrivateLayoutChange
                )
            }

            // --- Chat ---------------------------------------------------------------------
            SectionTitle("Chat")
            SettingsCard {
                SwitchRow(
                    icon = Icons.Default.Link,
                    title = "Link previews",
                    summary = "Fetching a preview reveals your IP address to the linked site.",
                    checked = uiState.linkPreviews,
                    onCheckedChange = viewModel::onLinkPreviewsChange
                )
                CardDivider()
                Column(modifier = Modifier.padding(16.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        RowIcon(Icons.Default.AlternateEmail)
                        Text("Highlight words", style = MaterialTheme.typography.bodyLarge.copy(fontSize = 17.sp), color = Palette.onSurface)
                    }
                    Spacer(modifier = Modifier.height(10.dp))
                    OutlinedTextField(
                        value = uiState.highlightWords,
                        onValueChange = viewModel::onHighlightWordsChange,
                        placeholder = { Text("word, another word", color = Palette.muted) },
                        singleLine = true,
                        shape = RoundedCornerShape(12.dp),
                        colors = fieldColors(),
                        modifier = Modifier.fillMaxWidth()
                    )
                    Text(
                        "Your nick always counts. WeeChat's own highlight settings can't be read over the relay, so mirror them here.",
                        style = MaterialTheme.typography.bodySmall.copy(fontSize = 13.sp),
                        color = Palette.muted,
                        modifier = Modifier.padding(top = 8.dp)
                    )
                }
            }

            // --- Buffer list --------------------------------------------------------------
            SectionTitle("Buffer list")
            SettingsCard {
                SwitchRow(
                    icon = Icons.Default.VisibilityOff,
                    title = "Hide special buffers",
                    summary = "Show only channels and private chats.",
                    checked = uiState.hideSpecialBuffers,
                    onCheckedChange = viewModel::onHideSpecialBuffersChange
                )
            }

            // --- About --------------------------------------------------------------------
            SectionTitle("About")
            SettingsCard {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable { uriHandler.openUri("https://github.com/carnager/weelay") }
                        .padding(16.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    RowIcon(Icons.Default.Code)
                    Column {
                        Text(
                            "Weelay ${viewModel.appVersion}",
                            style = MaterialTheme.typography.bodyLarge.copy(fontSize = 17.sp),
                            color = Palette.onSurface
                        )
                        Text(
                            "github.com/carnager/weelay · GPL-3.0",
                            style = MaterialTheme.typography.bodyMedium.copy(fontSize = 14.sp),
                            color = Palette.muted
                        )
                    }
                }
            }
            Spacer(modifier = Modifier.height(24.dp))
        }
    }
}

@Composable
private fun SectionTitle(text: String) {
    Text(
        text,
        style = MaterialTheme.typography.labelLarge.copy(fontSize = 14.sp, fontWeight = FontWeight.SemiBold),
        color = Palette.accent,
        modifier = Modifier.padding(start = 4.dp, top = 16.dp, bottom = 2.dp)
    )
}

@Composable
private fun SettingsCard(content: @Composable ColumnScope.() -> Unit) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .background(Palette.surface, RoundedCornerShape(16.dp)),
        content = content
    )
}

@Composable
private fun CardDivider() {
    HorizontalDivider(color = Palette.divider, modifier = Modifier.padding(start = 56.dp))
}

@Composable
private fun RowIcon(icon: ImageVector, tint: Color = Palette.muted) {
    Icon(icon, contentDescription = null, tint = tint, modifier = Modifier.size(24.dp))
    Spacer(modifier = Modifier.width(16.dp))
}

@Composable
private fun fieldColors() = OutlinedTextFieldDefaults.colors(
    focusedContainerColor = Palette.background,
    unfocusedContainerColor = Palette.background,
    focusedBorderColor = Palette.accent,
    unfocusedBorderColor = Palette.divider,
    focusedTextColor = Palette.onSurface,
    unfocusedTextColor = Palette.onSurface,
    focusedLabelColor = Palette.accent,
    unfocusedLabelColor = Palette.muted,
    cursorColor = Palette.accent
)

@Composable
private fun ConnectionForm(
    host: String,
    port: String,
    password: String,
    useSsl: Boolean,
    onHostChange: (String) -> Unit,
    onPortChange: (String) -> Unit,
    onPasswordChange: (String) -> Unit,
    onSslChange: (Boolean) -> Unit,
    onSave: () -> Unit
) {
    var passwordVisible by remember { mutableStateOf(false) }
    Column(
        modifier = Modifier.padding(start = 16.dp, end = 16.dp, bottom = 16.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            OutlinedTextField(
                value = host,
                onValueChange = onHostChange,
                label = { Text("Host") },
                singleLine = true,
                shape = RoundedCornerShape(12.dp),
                colors = fieldColors(),
                modifier = Modifier.weight(1f)
            )
            OutlinedTextField(
                value = port,
                onValueChange = onPortChange,
                label = { Text("Port") },
                singleLine = true,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                shape = RoundedCornerShape(12.dp),
                colors = fieldColors(),
                modifier = Modifier.width(96.dp)
            )
        }
        OutlinedTextField(
            value = password,
            onValueChange = onPasswordChange,
            label = { Text("Password") },
            singleLine = true,
            visualTransformation = if (passwordVisible) VisualTransformation.None else PasswordVisualTransformation(),
            trailingIcon = {
                IconButton(onClick = { passwordVisible = !passwordVisible }) {
                    Icon(
                        if (passwordVisible) Icons.Default.VisibilityOff else Icons.Default.Visibility,
                        contentDescription = if (passwordVisible) "Hide password" else "Show password",
                        tint = Palette.muted
                    )
                }
            },
            shape = RoundedCornerShape(12.dp),
            colors = fieldColors(),
            modifier = Modifier.fillMaxWidth()
        )
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .clickable { onSslChange(!useSsl) }
                .padding(vertical = 4.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Icon(Icons.Default.Lock, contentDescription = null, tint = Palette.muted, modifier = Modifier.size(20.dp))
            Spacer(modifier = Modifier.width(12.dp))
            Column(modifier = Modifier.weight(1f).padding(end = 12.dp)) {
                Text("Use TLS", style = MaterialTheme.typography.bodyLarge, color = Palette.onSurface)
                Text(
                    "On for a tls.api relay or a TLS proxy in front of it.",
                    style = MaterialTheme.typography.bodySmall.copy(fontSize = 13.sp),
                    color = Palette.muted
                )
            }
            Switch(checked = useSsl, onCheckedChange = onSslChange, colors = switchColors())
        }
        Button(
            onClick = onSave,
            modifier = Modifier
                .fillMaxWidth()
                .height(48.dp),
            shape = RoundedCornerShape(12.dp),
            colors = ButtonDefaults.buttonColors(containerColor = Palette.accent, contentColor = Palette.onAccent)
        ) {
            Text("Save and reconnect", fontWeight = FontWeight.SemiBold)
        }
    }
}

/**
 * A pickable preview of a style, drawn in that style's own colours whichever one is active, so
 * the choice is visible before it's made.
 */
@Composable
private fun StyleTile(
    style: AppStyle,
    name: String,
    selected: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    val colors = Palette.colorsFor(style)
    val shape = RoundedCornerShape(14.dp)
    Column(
        modifier = modifier
            .border(
                if (selected) BorderStroke(2.dp, Palette.accent) else BorderStroke(1.dp, Palette.divider),
                shape
            )
            .background(colors.background, shape)
            .clickable(onClick = onClick)
            .padding(12.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp)
    ) {
        if (style == AppStyle.RELAY) {
            listOf(
                Triple("12:01", "maze", Color(0xFFA1887F)),
                Triple("12:02", "rasi", Color(0xFFF06292))
            ).forEach { (time, nick, color) ->
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
                    Text(time, fontSize = 10.sp, fontFamily = FontFamily.Monospace, color = colors.muted)
                    Text(nick, fontSize = 11.sp, fontFamily = FontFamily.Monospace, color = color)
                    Box(modifier = Modifier.height(6.dp).weight(1f).background(colors.surfaceRaised, RoundedCornerShape(3.dp)))
                }
            }
        } else {
            listOf("Maze" to Color(0xFFA1887F), "Rasi" to Color(0xFFF06292)).forEach { (nick, color) ->
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
                    Box(modifier = Modifier.size(14.dp).background(color.copy(alpha = 0.25f), RoundedCornerShape(4.dp)))
                    Text(nick, fontSize = 11.sp, fontWeight = FontWeight.SemiBold, color = color)
                    Box(modifier = Modifier.height(6.dp).weight(1f).background(colors.surfaceRaised, RoundedCornerShape(3.dp)))
                }
            }
        }
        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(top = 4.dp)) {
            Box(modifier = Modifier.size(10.dp).background(colors.accent, CircleShape))
            Spacer(modifier = Modifier.width(6.dp))
            Text(name, fontSize = 15.sp, fontWeight = FontWeight.SemiBold, color = colors.onSurface, modifier = Modifier.weight(1f))
            if (selected) {
                Icon(Icons.Default.CheckCircle, contentDescription = "Selected", tint = colors.accent, modifier = Modifier.size(18.dp))
            }
        }
    }
}

/** A titled set of mutually exclusive options, as segmented buttons under the title. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun <T> ChoiceRow(
    icon: ImageVector,
    title: String,
    options: List<Pair<T, String>>,
    selected: T,
    onChange: (T) -> Unit
) {
    Column(modifier = Modifier.padding(16.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            RowIcon(icon)
            Text(title, style = MaterialTheme.typography.bodyLarge.copy(fontSize = 17.sp), color = Palette.onSurface)
        }
        Spacer(modifier = Modifier.height(10.dp))
        SingleChoiceSegmentedButtonRow(modifier = Modifier.fillMaxWidth()) {
            options.forEachIndexed { index, (value, label) ->
                SegmentedButton(
                    selected = selected == value,
                    onClick = { onChange(value) },
                    shape = SegmentedButtonDefaults.itemShape(index = index, count = options.size),
                    colors = SegmentedButtonDefaults.colors(
                        activeContainerColor = Palette.accent,
                        activeContentColor = Palette.onAccent,
                        inactiveContainerColor = Palette.background,
                        inactiveContentColor = Palette.onSurface,
                        activeBorderColor = Palette.accent,
                        inactiveBorderColor = Palette.divider
                    ),
                    icon = {}
                ) { Text(label) }
            }
        }
    }
}

@Composable
private fun SwitchRow(
    icon: ImageVector,
    title: String,
    summary: String,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable { onCheckedChange(!checked) }
            .padding(16.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        RowIcon(icon)
        Column(modifier = Modifier.weight(1f).padding(end = 12.dp)) {
            Text(title, style = MaterialTheme.typography.bodyLarge.copy(fontSize = 17.sp), color = Palette.onSurface)
            Text(summary, style = MaterialTheme.typography.bodyMedium.copy(fontSize = 14.sp), color = Palette.muted)
        }
        Switch(checked = checked, onCheckedChange = onCheckedChange, colors = switchColors())
    }
}

@Composable
private fun switchColors() = SwitchDefaults.colors(
    checkedTrackColor = Palette.accent,
    checkedThumbColor = Color.White,
    uncheckedTrackColor = Palette.background,
    uncheckedThumbColor = Palette.muted,
    uncheckedBorderColor = Palette.divider
)
