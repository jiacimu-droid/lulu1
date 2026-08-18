package com.jiacimu.lulu

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

@Composable
internal fun MeetingToolButton(
    icon: ImageVector,
    contentDescription: String,
    onClick: () -> Unit,
    active: Boolean = false,
) {
    IconButton(onClick = onClick) {
        Surface(
            modifier = Modifier.size(34.dp),
            shape = RoundedCornerShape(11.dp),
            color = if (active) Color(0xFF242424) else Color(0xFFF7F6F3),
            border = BorderStroke(
                1.dp,
                if (active) Color(0xFF242424) else Color(0xFFD8D6D1),
            ),
            shadowElevation = if (active) 0.dp else 1.dp,
        ) {
            Box(contentAlignment = Alignment.Center) {
                Icon(
                    imageVector = icon,
                    contentDescription = contentDescription,
                    tint = if (active) Color.White else Color(0xFF353535),
                    modifier = Modifier.size(18.dp),
                )
            }
        }
    }
}

@Composable
internal fun MeetingVoiceToggleButton(
    enabled: Boolean,
    onToggle: () -> Unit,
) {
    MeetingToolButton(
        icon = if (enabled) Icons.Outlined.VolumeUp else Icons.Outlined.VolumeOff,
        contentDescription = if (enabled) "关闭见面语音" else "开启见面语音",
        onClick = onToggle,
        active = enabled,
    )
}

@Composable
internal fun MeetingOverflowMenu(
    expanded: Boolean,
    voiceEnabled: Boolean,
    onDismiss: () -> Unit,
    onToggleVoice: () -> Unit,
    onOpenHistory: () -> Unit,
    onOpenModelPicker: () -> Unit,
    onOpenWritingPicker: () -> Unit,
) {
    DropdownMenu(
        expanded = expanded,
        onDismissRequest = onDismiss,
        modifier = Modifier.width(220.dp),
        shape = RoundedCornerShape(19.dp),
        containerColor = Color(0xFFFEFEFD),
        tonalElevation = 0.dp,
        shadowElevation = 8.dp,
        border = BorderStroke(1.dp, Color(0xFF2C2B29)),
    ) {
        Row(
            modifier = Modifier.padding(start = 14.dp, end = 12.dp, top = 8.dp, bottom = 5.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                "见面设置",
                color = Color(0xFF777570),
                fontSize = 10.5.sp,
                fontWeight = FontWeight.Medium,
                modifier = Modifier.weight(1f),
            )
            Surface(
                shape = RoundedCornerShape(99.dp),
                color = Color(0xFFF2F1EE),
            ) {
                Text(
                    "数字世界",
                    color = Color(0xFF777570),
                    fontSize = 8.5.sp,
                    modifier = Modifier.padding(horizontal = 7.dp, vertical = 2.dp),
                )
            }
        }
        MeetingMenuRow(
            icon = if (voiceEnabled) Icons.Outlined.VolumeUp else Icons.Outlined.VolumeOff,
            title = "角色台词语音",
            trailing = if (voiceEnabled) "已开启" else "已关闭",
            selected = voiceEnabled,
            onClick = {
                onDismiss()
                onToggleVoice()
            },
        )
        MeetingMenuDivider()
        MeetingMenuRow(
            icon = Icons.Outlined.History,
            title = "见面记录",
            onClick = {
                onDismiss()
                onOpenHistory()
            },
        )
        MeetingMenuRow(
            icon = Icons.Outlined.Memory,
            title = "见面模型",
            onClick = {
                onDismiss()
                onOpenModelPicker()
            },
        )
        MeetingMenuRow(
            icon = Icons.Outlined.AutoStories,
            title = "见面写法",
            onClick = {
                onDismiss()
                onOpenWritingPicker()
            },
        )
        Spacer(Modifier.height(5.dp))
    }
}

@Composable
private fun MeetingMenuRow(
    icon: ImageVector,
    title: String,
    trailing: String? = null,
    selected: Boolean = false,
    onClick: () -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(horizontal = 11.dp, vertical = 7.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Surface(
            modifier = Modifier.size(32.dp),
            shape = RoundedCornerShape(10.dp),
            color = if (selected) Color(0xFF252525) else Color(0xFFF4F3F0),
            border = BorderStroke(
                1.dp,
                if (selected) Color(0xFF252525) else Color(0xFFE0DED9),
            ),
        ) {
            Box(contentAlignment = Alignment.Center) {
                Icon(
                    icon,
                    null,
                    tint = if (selected) Color.White else Color(0xFF3D3D3D),
                    modifier = Modifier.size(17.dp),
                )
            }
        }
        Text(
            title,
            color = Color(0xFF242424),
            fontSize = 13.sp,
            fontWeight = FontWeight.Medium,
            modifier = Modifier.weight(1f),
        )
        trailing?.let {
            Surface(
                shape = RoundedCornerShape(8.dp),
                color = if (selected) Color(0xFF252525) else Color(0xFFF1F1EF),
            ) {
                Text(
                    it,
                    color = if (selected) Color.White else Color(0xFF777777),
                    fontSize = 9.5.sp,
                    modifier = Modifier.padding(horizontal = 7.dp, vertical = 3.dp),
                )
            }
        }
    }
}

@Composable
private fun MeetingMenuDivider() {
    HorizontalDivider(
        modifier = Modifier.padding(horizontal = 12.dp, vertical = 3.dp),
        color = Color(0xFFE8E8E4),
        thickness = 1.dp,
    )
}
