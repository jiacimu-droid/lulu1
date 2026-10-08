package com.jiacimu.lulu

import com.jiacimu.lulu.design.LuluAlertDialog as AlertDialog

import androidx.compose.foundation.layout.*
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.jiacimu.lulu.design.LuluColors

@Composable
internal fun MeetingPageVoiceSettingsDialog(onDismiss: () -> Unit) {
    val context = LocalContext.current
    val enabled by MeetingVoicePlayback.enabled.collectAsState()

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("见面语音") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(14.dp)) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Column(Modifier.weight(1f)) {
                        Text("角色语音与音效", fontWeight = FontWeight.SemiBold)
                        Text(
                            if (enabled) "已开启" else "已关闭",
                            color = LuluColors.Muted,
                            fontSize = 12.sp,
                        )
                    }
                    Switch(
                        checked = enabled,
                        onCheckedChange = { MeetingVoicePlayback.setEnabled(context, it) },
                    )
                }
                Text(
                    "开启后播放当前页的角色台词、情绪发声与动作音效。翻页立即停止上一页；动作描写不会被朗读。",
                    color = LuluColors.Muted,
                    fontSize = 12.sp,
                    lineHeight = 18.sp,
                )
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) { Text("完成") }
        },
    )
}
