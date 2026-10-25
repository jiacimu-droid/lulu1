package com.jiacimu.lulu

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.jiacimu.lulu.design.LuluAlertDialog

/** Bundled with the installed APK, so notes cannot describe a newer uninstalled build. */
@Composable
internal fun LuluUpdateNotesDialog(onDismiss: () -> Unit) {
    LuluAlertDialog(onDismissRequest = onDismiss,
        title = { Text("更新内容") },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text("${BuildConfig.VERSION_NAME} · ${BuildConfig.BUILD_REVISION}",
                    fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Text("本次 · 探索恢复与行动衔接", fontWeight = FontWeight.SemiBold)
                Text("失败探索可直接重试，生活记录里的“正在琢磨”同步保留进展与失败原因。")
                Text("挂心、持续目标、研究和好奇共同进入行动选择；每个副动作的真实回执关联对应事项。")
                Text("聊天中可以同时选择发朋友圈；是否公开分享仍由角色自己决定。")
                Text("上一轮 · 内驱力与持续目标", fontWeight = FontWeight.SemiBold)
                Text("重要目标接入续醒和自选时间唤醒；保存完成依据、等待条件与下一步。旧兴趣和未尽问题参与轮换。")
                Text("查看进展：生活记录 → 心事。实际动作与失败原因：最近感知 → 详情。",
                    fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }, confirmButton = { TextButton(onClick = onDismiss) { Text("知道了") } })
}
