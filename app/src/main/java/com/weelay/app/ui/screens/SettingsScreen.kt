package com.weelay.app.ui.screens

import com.weelay.app.ui.theme.Palette
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Visibility
import androidx.compose.material.icons.filled.VisibilityOff
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CheckboxDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Scaffold
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
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.navigation.compose.hiltViewModel
import com.weelay.app.viewmodel.SettingsViewModel

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(
    onBack: () -> Unit,
    onDisconnected: () -> Unit,
    viewModel: SettingsViewModel = hiltViewModel()
) {
    val uiState by viewModel.uiState.collectAsState()

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Text(
                        "Settings",
                        style = MaterialTheme.typography.titleMedium.copy(fontSize = 18.sp),
                        color = Palette.onSurface
                    )
                },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(
                            imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                            contentDescription = "Back",
                            tint = Palette.onSurface
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
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 16.dp)
        ) {
            Spacer(modifier = Modifier.height(16.dp))

            // Connection section
            Text(
                text = "Connection",
                style = MaterialTheme.typography.labelLarge.copy(
                    fontSize = 14.sp,
                    fontWeight = FontWeight.SemiBold
                ),
                color = Palette.accent,
                modifier = Modifier.padding(bottom = 12.dp)
            )

            val textFieldColors = OutlinedTextFieldDefaults.colors(
                focusedContainerColor = Palette.surface,
                unfocusedContainerColor = Palette.surface,
                focusedBorderColor = Palette.accent,
                unfocusedBorderColor = Palette.divider,
                focusedTextColor = Palette.onSurface,
                unfocusedTextColor = Palette.onSurface,
                focusedLabelColor = Palette.accent,
                unfocusedLabelColor = Palette.muted,
                cursorColor = Palette.accent
            )

            OutlinedTextField(
                value = uiState.host,
                onValueChange = viewModel::onHostChange,
                label = { Text("Host") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
                colors = textFieldColors
            )

            Spacer(modifier = Modifier.height(12.dp))

            OutlinedTextField(
                value = uiState.port,
                onValueChange = viewModel::onPortChange,
                label = { Text("Port") },
                singleLine = true,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                modifier = Modifier.fillMaxWidth(),
                colors = textFieldColors
            )

            Spacer(modifier = Modifier.height(12.dp))

            var passwordVisible by remember { mutableStateOf(false) }

            OutlinedTextField(
                value = uiState.password,
                onValueChange = viewModel::onPasswordChange,
                label = { Text("Password") },
                singleLine = true,
                visualTransformation = if (passwordVisible)
                    VisualTransformation.None else PasswordVisualTransformation(),
                trailingIcon = {
                    IconButton(onClick = { passwordVisible = !passwordVisible }) {
                        Icon(
                            imageVector = if (passwordVisible)
                                Icons.Default.VisibilityOff else Icons.Default.Visibility,
                            contentDescription = "Toggle password visibility",
                            tint = Palette.muted
                        )
                    }
                },
                modifier = Modifier.fillMaxWidth(),
                colors = textFieldColors
            )

            Spacer(modifier = Modifier.height(8.dp))

            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.fillMaxWidth()
            ) {
                Checkbox(
                    checked = uiState.useSsl,
                    onCheckedChange = viewModel::onSslChange,
                    colors = CheckboxDefaults.colors(
                        checkedColor = Palette.accent,
                        uncheckedColor = Palette.muted
                    )
                )
                Spacer(modifier = Modifier.width(4.dp))
                Text(
                    "Use SSL/TLS",
                    style = MaterialTheme.typography.bodyMedium,
                    color = Palette.onSurface
                )
            }

            Spacer(modifier = Modifier.height(8.dp))

            Button(
                onClick = viewModel::saveAndReconnect,
                modifier = Modifier.fillMaxWidth(),
                colors = ButtonDefaults.buttonColors(
                    containerColor = Palette.accent
                )
            ) {
                Text("Save & Reconnect", color = Palette.onAccent)
            }

            Spacer(modifier = Modifier.height(24.dp))

            HorizontalDivider(color = Palette.divider)

            Spacer(modifier = Modifier.height(16.dp))

            // Display section
            Text(
                text = "Display",
                style = MaterialTheme.typography.labelLarge.copy(
                    fontSize = 14.sp,
                    fontWeight = FontWeight.SemiBold
                ),
                color = Palette.accent,
                modifier = Modifier.padding(bottom = 12.dp)
            )

            LayoutChoiceRow(
                title = "Channels",
                compact = uiState.compactChannels,
                onChange = viewModel::onCompactChannelsChange
            )
            LayoutChoiceRow(
                title = "Private chats",
                compact = uiState.compactPrivate,
                onChange = viewModel::onCompactPrivateChange
            )

            SettingsSwitchRow(
                title = "Hide special buffers",
                summary = "Hide server and special buffers from the buffer list",
                checked = uiState.hideSpecialBuffers,
                onCheckedChange = viewModel::onHideSpecialBuffersChange
            )

            SettingsSwitchRow(
                title = "Link previews",
                summary = "Show a preview card for links in chat. Fetching a preview reveals " +
                    "your IP address to the site the link points to.",
                checked = uiState.linkPreviews,
                onCheckedChange = viewModel::onLinkPreviewsChange
            )

            Spacer(modifier = Modifier.height(8.dp))

            OutlinedTextField(
                value = uiState.highlightWords,
                onValueChange = viewModel::onHighlightWordsChange,
                label = { Text("Highlight words") },
                supportingText = {
                    Text(
                        "Comma-separated. Your nick always counts. " +
                            "WeeChat's own highlight settings can't be read over the relay, " +
                            "so mirror them here."
                    )
                },
                singleLine = true,
                colors = textFieldColors,
                modifier = Modifier.fillMaxWidth()
            )

            Spacer(modifier = Modifier.height(24.dp))

            HorizontalDivider(color = Palette.divider)

            Spacer(modifier = Modifier.height(16.dp))

            OutlinedButton(
                onClick = {
                    viewModel.disconnect()
                    onDisconnected()
                },
                modifier = Modifier.fillMaxWidth(),
                border = BorderStroke(1.dp, Palette.danger),
                colors = ButtonDefaults.outlinedButtonColors(contentColor = Palette.danger)
            ) {
                Text("Disconnect")
            }

            Text(
                "Closes the relay connection and stops the background service. " +
                    "Weelay won't reconnect until you connect again.",
                style = MaterialTheme.typography.bodySmall,
                color = Palette.muted,
                modifier = Modifier.padding(top = 4.dp)
            )

            Spacer(modifier = Modifier.height(24.dp))
        }
    }
}

/** Choose between compact IRC lines and chat bubbles for one kind of buffer. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun LayoutChoiceRow(title: String, compact: Boolean, onChange: (Boolean) -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(
            title,
            style = MaterialTheme.typography.bodyLarge,
            color = Palette.onSurface,
            modifier = Modifier.weight(1f)
        )
        SingleChoiceSegmentedButtonRow {
            listOf(true to "Lines", false to "Bubbles").forEachIndexed { index, (value, label) ->
                SegmentedButton(
                    selected = compact == value,
                    onClick = { onChange(value) },
                    shape = SegmentedButtonDefaults.itemShape(index = index, count = 2),
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
private fun SettingsSwitchRow(
    title: String,
    summary: String,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column(modifier = Modifier.weight(1f).padding(end = 12.dp)) {
            Text(
                title,
                style = MaterialTheme.typography.bodyLarge,
                color = Palette.onSurface
            )
            Text(
                summary,
                style = MaterialTheme.typography.bodySmall,
                color = Palette.muted
            )
        }
        Switch(
            checked = checked,
            onCheckedChange = onCheckedChange,
            colors = SwitchDefaults.colors(
                checkedTrackColor = Palette.accent,
                checkedThumbColor = Color.White
            )
        )
    }
}
