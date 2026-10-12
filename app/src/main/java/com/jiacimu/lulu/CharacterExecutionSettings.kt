package com.jiacimu.lulu

import android.content.Context
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.jiacimu.lulu.data.CharacterDevelopmentStore
import com.jiacimu.lulu.data.WebSearchProvider
import androidx.compose.ui.text.input.PasswordVisualTransformation

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
        Text("网络搜索（所有角色共用）", style = MaterialTheme.typography.titleMedium)
        var searchKey by remember { mutableStateOf(WebSearchProvider.savedKey(context)) }
        var searchStatus by remember { mutableStateOf("") }
        OutlinedTextField(
            value = searchKey,
            onValueChange = { searchKey = it.take(256); searchStatus = "" },
            label = { Text("Tavily Search API Key") },
            visualTransformation = PasswordVisualTransformation(),
            singleLine = true,
            modifier = Modifier.fillMaxWidth().keepFocusedFieldVisible()
        )
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            Text(if (WebSearchProvider.isConfigured(context)) "已配置通用网页搜索" else "未配置：仍可使用百科搜索",
                style = MaterialTheme.typography.bodySmall)
            TextButton(onClick = {
                searchStatus = if (WebSearchProvider.saveKey(context, searchKey)) "已保存（仅本机）" else "保存失败"
            }) { Text("保存／清除") }
        }
        if (searchStatus.isNotBlank()) Text(searchStatus, style = MaterialTheme.typography.bodySmall)
        Text("搜索只发送角色自己决定的查询词。密钥保存在本机应用私有设置，不会上传到 GitHub；公开发布时建议改用服务端代理。",
            style = MaterialTheme.typography.bodySmall)
        Text("自主学习", style = MaterialTheme.typography.titleMedium)
        com.jiacimu.lulu.data.CharacterLearningAgenda.initialize(context)
        var learningLimit by remember(characterId) {
            mutableStateOf(prefs.getInt("$characterId:learning_daily_limit", 2).coerceIn(0, 10))
        }
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            Text("每天学习步骤：$learningLimit")
            Row {
                TextButton(enabled = learningLimit > 0, onClick = {
                    learningLimit--; prefs.edit().putInt("$characterId:learning_daily_limit", learningLimit).commit()
                }) { Text("减少") }
                TextButton(enabled = learningLimit < 10, onClick = {
                    learningLimit++; prefs.edit().putInt("$characterId:learning_daily_limit", learningLimit).commit()
                }) { Text("增加") }
            }
        }
        val learningRevision by com.jiacimu.lulu.data.CharacterLearningAgenda.revisions.collectAsState()
        val learningGoals = remember(characterId, learningRevision) {
            com.jiacimu.lulu.data.CharacterLearningAgenda.goals(characterId)
                .filter { it.optString("status") != "dropped" }
        }
        learningGoals.forEach { goal ->
            val stage = when (goal.optString("status")) {
                "planned" -> "尚未接触材料"; "running" -> "正在学习"; "failed" -> "待调整"
                "paused" -> "暂缓"; "research" -> "已读，待进一步核实"; else -> "待实践／复习"
            }
            Text("${goal.optString("topic")} · $stage")
            goal.optString("error").takeIf(String::isNotBlank)?.let {
                Text(it, style = MaterialTheme.typography.bodySmall)
            }
            Row {
                TextButton(enabled = goal.optString("status") != "running", onClick = {
                    com.jiacimu.lulu.data.CharacterLearningAgenda.update(characterId, goal.optString("id")) {
                        it.put("status", if (it.optString("status") == "paused")
                            if (it.optString("materialId").isBlank()) "planned" else "practice" else "paused")
                    }
                }) { Text(if (goal.optString("status") == "paused") "恢复" else "暂缓") }
                TextButton(enabled = goal.optString("status") != "running", onClick = {
                    com.jiacimu.lulu.data.CharacterLearningAgenda.update(characterId, goal.optString("id")) {
                        it.put("status", "dropped")
                    }
                }) { Text("放下") }
            }
        }
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
