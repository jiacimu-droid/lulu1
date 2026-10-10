package com.jiacimu.lulu

import android.content.Context
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.jiacimu.lulu.data.CharacterDevelopmentStore

@Composable
internal fun CharacterExecutionSettings(characterId: String) {
    val context = LocalContext.current
    val prefs = remember { context.getSharedPreferences("lulu_execution_policy", Context.MODE_PRIVATE) }
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text("主动执行授权", style = MaterialTheme.typography.titleMedium)
        listOf("device_read" to "主动读取设备状态", "alarm" to "主动设置本地闹钟", "screen" to "主动操作屏幕", "cloud" to "主动提交云端任务").forEach { (key, label) ->
            var allowed by remember(characterId, key) { mutableStateOf(prefs.getBoolean("$characterId:$key", false)) }
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                Text(label)
                Switch(allowed, { allowed = it; prefs.edit().putBoolean("$characterId:$key", it).commit() })
            }
        }
        var packages by remember(characterId) { mutableStateOf(prefs.getString("$characterId:notification_packages", "").orEmpty()) }
        OutlinedTextField(packages, { packages = it; prefs.edit().putString("$characterId:notification_packages", it).commit() },
            label = { Text("重要通知的 App 包名，逗号分隔") }, modifier = Modifier.fillMaxWidth().keepFocusedFieldVisible())
        Text("可追溯成长", style = MaterialTheme.typography.titleMedium)
        val revision by CharacterDevelopmentStore.revisions.collectAsState()
        val records = remember(characterId, revision) { CharacterDevelopmentStore.active(characterId) }
        records.takeLast(15).forEach { record ->
            Text("${record.kind.label} · v${record.version}：${record.content}")
            com.jiacimu.lulu.data.SharedExperienceTimeline.eventsByIds(characterId, record.evidence.keys).take(3).forEach { event ->
                Text("依据：${event.channel} · ${event.content.take(100)}", style = MaterialTheme.typography.bodySmall)
            }
            TextButton(onClick = { CharacterDevelopmentStore.retire(characterId, record.id) }) { Text("撤销这项变化") }
        }
        if (records.none { it.active }) Text("尚无经验证的成长记录")
    }
}
