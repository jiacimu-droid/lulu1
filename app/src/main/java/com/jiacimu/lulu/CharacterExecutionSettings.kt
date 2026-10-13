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
        listOf("device_read" to "主动读取设备状态", "alarm" to "主动设置本地闹钟", "media" to "主动控制手机音乐", "screen" to "主动操作屏幕", "cloud" to "主动提交云端任务").forEach { (key, label) ->
            var allowed by remember(characterId, key) { mutableStateOf(prefs.getBoolean("$characterId:$key", false)) }
            Row(Modifier.fillMaxWidth(), verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
                Text(label, modifier = Modifier.weight(1f))
                Switch(allowed, { allowed = it; prefs.edit().putBoolean("$characterId:$key", it).commit() })
            }
        }
        val exactAlarmGranted = remember { mutableStateOf(com.jiacimu.lulu.system.LuluAlarmSystem.canScheduleExact()) }
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.S) {
            Row(Modifier.fillMaxWidth(), verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
                Text(if (exactAlarmGranted.value) "精确闹钟权限已开启" else "精确闹钟权限未开启：仅能尝试非精确提醒",
                    modifier = Modifier.weight(1f), style = MaterialTheme.typography.bodySmall)
                TextButton(onClick = {
                    runCatching {
                        context.startActivity(android.content.Intent(
                            android.provider.Settings.ACTION_REQUEST_SCHEDULE_EXACT_ALARM,
                            android.net.Uri.parse("package:" + context.packageName),
                        ))
                    }
                    exactAlarmGranted.value = com.jiacimu.lulu.system.LuluAlarmSystem.canScheduleExact()
                }) { Text("授权") }
            }
        }
        Text("音乐控制需要先在手机权限页开启「通知读取」，并让音乐 App 建立播放会话；授权后可以暂停、继续、上一首和下一首。", style = MaterialTheme.typography.bodySmall)
        var wakeMusic by remember(characterId) { mutableStateOf(prefs.getBoolean("$characterId:wake_music_enabled", false)) }
        Row(Modifier.fillMaxWidth(), verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
            Text("确认醒来后自动播放音乐", modifier = Modifier.weight(1f))
            Switch(wakeMusic, {
                wakeMusic = it
                prefs.edit().putBoolean("$characterId:wake_music_enabled", it).commit()
            })
        }
        Text("只有你明确说已醒、对应叫醒任务仍有效且媒体控制已授权时才执行。正在通话会等挂断后再播放；没有可用音乐会话时不会假装成功。",
            style = MaterialTheme.typography.bodySmall)
        var packages by remember(characterId) { mutableStateOf(prefs.getString("$characterId:notification_packages", "").orEmpty()) }
        OutlinedTextField(packages, { packages = it; prefs.edit().putString("$characterId:notification_packages", it).commit() },
            label = { Text("重要通知的 App 包名，逗号分隔") }, modifier = Modifier.fillMaxWidth().keepFocusedFieldVisible())
        HorizontalDivider()
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

    }
}

/** Character growth and self-learning belong to companionship, not device permission management. */
@Composable
internal fun CharacterLearningSettings(characterId: String) {
    val context = LocalContext.current
    val prefs = remember { context.getSharedPreferences("lulu_execution_policy", Context.MODE_PRIVATE) }
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text("学习与成长", style = MaterialTheme.typography.titleLarge, fontWeight = androidx.compose.ui.text.font.FontWeight.SemiBold)
        com.jiacimu.lulu.data.CharacterLearningAgenda.initialize(context)
        var learningLimit by remember(characterId) {
            mutableStateOf(prefs.getInt("$characterId:learning_daily_limit", 2).coerceIn(0, 10))
        }
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            Text("每日学习上限 · $learningLimit", modifier = Modifier.weight(1f))
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
            HorizontalDivider()
            val stage = when (goal.optString("status")) {
                "planned" -> "待开始"; "running" -> "正在学习"; "failed" -> "待调整"
                "paused" -> "暂缓"; "research" -> "待核实"; else -> "待实践／复习"
            }
            Text("${goal.optString("topic")} · $stage")
            goal.optString("error").takeIf(String::isNotBlank)?.let {
                Text(it, style = MaterialTheme.typography.bodySmall)
            }
            Row {
                TextButton(enabled = goal.optString("status") != "running", onClick = {
                    com.jiacimu.lulu.data.CharacterLearningAgenda.update(characterId, goal.optString("id")) {
                        it.put("status", if (it.optString("status") == "paused")
                            if (it.optString("materialId").isBlank()) "planned"
                            else if (!com.jiacimu.lulu.data.CharacterLearningLedger.hasInsight(characterId, it.optString("materialId"))) "research"
                            else "practice" else "paused")
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
        records.takeLast(15).asReversed().forEach { record ->
            HorizontalDivider()
            Text(record.kind.label, style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant)
            Text(record.content)
            var showingEvidence by remember(characterId, record.id) { mutableStateOf(false) }
            TextButton(onClick = { showingEvidence = !showingEvidence }) { Text(if (showingEvidence) "收起依据" else "查看依据") }
            if (showingEvidence) {
            com.jiacimu.lulu.data.SharedExperienceTimeline.eventsByIds(characterId, record.evidence.keys).take(3).forEach { event ->
                Text("依据：${event.channel} · ${event.content.take(100)}", style = MaterialTheme.typography.bodySmall)
            }
            TextButton(onClick = { CharacterDevelopmentStore.retire(characterId, record.id) }) { Text("撤销变化") }
            }
        }
        if (records.none { it.active }) Text("暂无成长记录")
    }
}
