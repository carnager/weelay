package com.weelay.app.ui.components

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Chat
import androidx.compose.material.icons.filled.Block
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.PersonRemove
import androidx.compose.material.icons.automirrored.filled.VolumeOff
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

data class NickAction(
    val label: String,
    val icon: ImageVector,
    val tint: Color = Color(0xFFE9EDEF),
    val onClick: () -> Unit
)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun NickActionSheet(
    nick: String,
    nickColor: Color,
    isChannel: Boolean,
    onDismiss: () -> Unit,
    onQuery: () -> Unit,
    onWhois: () -> Unit,
    onBan: () -> Unit,
    onKick: () -> Unit,
    onQuiet: () -> Unit
) {
    val sheetState = rememberModalBottomSheetState()

    val actions = mutableListOf(
        NickAction(
            label = "Message $nick",
            icon = Icons.AutoMirrored.Filled.Chat,
            onClick = onQuery
        ),
        NickAction(
            label = "User info",
            icon = Icons.Default.Info,
            onClick = onWhois
        )
    )

    if (isChannel) {
        actions.addAll(
            listOf(
                NickAction(
                    label = "Kick",
                    icon = Icons.Default.PersonRemove,
                    tint = Color(0xFFE57373),
                    onClick = onKick
                ),
                NickAction(
                    label = "Ban",
                    icon = Icons.Default.Block,
                    tint = Color(0xFFE57373),
                    onClick = onBan
                ),
                NickAction(
                    label = "Quiet",
                    icon = Icons.AutoMirrored.Filled.VolumeOff,
                    tint = Color(0xFFFFB74D),
                    onClick = onQuiet
                )
            )
        )
    }

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState,
        containerColor = Color(0xFF1F2C34),
        dragHandle = null
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(top = 20.dp, bottom = 24.dp)
        ) {
            // Nick header
            Text(
                text = nick,
                style = MaterialTheme.typography.titleLarge.copy(
                    fontSize = 22.sp,
                    fontWeight = FontWeight.SemiBold
                ),
                color = nickColor,
                modifier = Modifier.padding(horizontal = 20.dp)
            )

            Spacer(modifier = Modifier.height(16.dp))

            HorizontalDivider(color = Color(0xFF233039))

            Spacer(modifier = Modifier.height(8.dp))

            actions.forEach { action ->
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable {
                            action.onClick()
                            onDismiss()
                        }
                        .padding(horizontal = 20.dp, vertical = 14.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Icon(
                        imageVector = action.icon,
                        contentDescription = action.label,
                        tint = action.tint,
                        modifier = Modifier.size(24.dp)
                    )
                    Spacer(modifier = Modifier.width(16.dp))
                    Text(
                        text = action.label,
                        style = MaterialTheme.typography.bodyLarge.copy(
                            fontSize = 16.sp
                        ),
                        color = action.tint
                    )
                }
            }
        }
    }
}
