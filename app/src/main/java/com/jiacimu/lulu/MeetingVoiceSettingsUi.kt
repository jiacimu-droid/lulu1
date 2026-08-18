package com.jiacimu.lulu

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.weight
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.material3.AlertDialog
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
            Column(verticalArrangement = Arrangement.spacedBy(androidx.compose.ui.unit.dp(14f))) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Column(Modifier.weight(1f)) {
                        Text("角色台词语音", fontWeight = FontWeight.SemiBold)
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
                    "开启后，只有你翻到角色真正说话的那一页时才会播放这一页台词。翻到动作、环境、你的文字或下一页时，会立即停止上一页；不再设置阅读节奏，也不会提前把后面的台词排队播放。",
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
