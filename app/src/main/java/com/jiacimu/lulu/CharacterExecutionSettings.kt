package com.jiacimu.lulu

import androidx.compose.ui.text.font.FontWeight

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
        listOf("device_read" to "主动读取设备状态", "alarm" to "主动设置本地闹钟", "media" to "主动控制手机音乐", "xhs_read" to "主动逛小红书（只读）", "health_read" to "读取手环健康 MCP（只读）", "screen" to "主动操作屏幕", "cloud" to "主动提交云端任务").forEach { (key, label) ->
            var allowed by remember(characterId, key) { mutableStateOf(prefs.getBoolean("$characterId:$key", key == "xhs_read")) }
            Row(Modifier.fillMaxWidth(), verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
                Text(label, modifier = Modifier.weight(1f))
                Switch(allowed, { allowed = it; prefs.edit().putBoolean("$characterId:$key", it).commit() })
            }
        }
        val exactAlarmGranted = remember { mutableStateOf(com.jiacimu.lulu.system.LuluAlarmSystem.canScheduleExact()) }
        val fullScreenGranted = remember { mutableStateOf(android.os.Build.VERSION.SDK_INT < 34 ||
            context.getSystemService(android.app.NotificationManager::class.java).canUseFullScreenIntent()) }
        val lifecycleOwner = androidx.lifecycle.compose.LocalLifecycleOwner.current
        DisposableEffect(lifecycleOwner) {
            val observer = androidx.lifecycle.LifecycleEventObserver { _, event ->
                if (event == androidx.lifecycle.Lifecycle.Event.ON_RESUME) {
                    exactAlarmGranted.value = com.jiacimu.lulu.system.LuluAlarmSystem.canScheduleExact()
                    fullScreenGranted.value = android.os.Build.VERSION.SDK_INT < 34 ||
                        context.getSystemService(android.app.NotificationManager::class.java).canUseFullScreenIntent()
                }
            }
            lifecycleOwner.lifecycle.addObserver(observer)
            onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
        }
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
        if (android.os.Build.VERSION.SDK_INT >= 34) {
            Row(Modifier.fillMaxWidth(), verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
                Text(if (fullScreenGranted.value) "锁屏来电全屏已开启" else "锁屏来电仅显示通知",
                    modifier = Modifier.weight(1f), style = MaterialTheme.typography.bodySmall)
                TextButton(onClick = {
                    runCatching { context.startActivity(android.content.Intent(
                        android.provider.Settings.ACTION_MANAGE_APP_USE_FULL_SCREEN_INTENT,
                        android.net.Uri.parse("package:" + context.packageName))) }
                }) { Text("设置") }
            }
        }
        Text("音乐控制需要先在手机权限页开启「通知读取」，并让音乐 App 建立播放会话；授权后可以暂停、继续、上一首和下一首。", style = MaterialTheme.typography.bodySmall)
        var wakeMusic by remember(characterId) { mutableStateOf(prefs.getBoolean("$characterId:wake_music_enabled", false)) }
        Row(Modifier.fillMaxWidth(), verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
            Text("到点叫醒后自动放歌", modifier = Modifier.weight(1f))
            Switch(wakeMusic, {
                wakeMusic = it
                prefs.edit().putBoolean("$characterId:wake_music_enabled", it).commit()
            })
        }
        Text("已开启时，叫醒电话会先自然叫你起床，电话保持接通，方便醒来再聊几句；等你或角色真正挂断后再播放音乐，避免声音重叠。",
            style = MaterialTheme.typography.bodySmall)
        var packages by remember(characterId) { mutableStateOf(prefs.getString("$characterId:notification_packages", "").orEmpty()) }
        OutlinedTextField(packages, { packages = it; prefs.edit().putString("$characterId:notification_packages", it).commit() },
            label = { Text("重要通知的 App 包名，逗号分隔") }, modifier = Modifier.fillMaxWidth().keepFocusedFieldVisible())



    }
}

/** Character growth and self-learning belong to companionship, not device permission management. */
@Composable
internal fun CharacterLearningSettings(characterId: String, showConfiguration: Boolean = true, showRecords: Boolean = true, showGrowthRecords: Boolean = true) {
    val context = LocalContext.current
    val prefs = remember { context.getSharedPreferences("lulu_execution_policy", Context.MODE_PRIVATE) }
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(if (showConfiguration) "学习设置" else "读过、学过、试过", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.SemiBold)
        if (!showConfiguration) Text("只展示角色真实接触过的材料、理解与尝试；不是重复的成长历程。",
            style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        com.jiacimu.lulu.data.CharacterLearningAgenda.initialize(context)
        val learningRevision by com.jiacimu.lulu.data.CharacterLearningAgenda.revisions.collectAsState()
        if (showConfiguration) {
        var learningLimit by remember(characterId) {
            mutableStateOf(prefs.getInt("$characterId:learning_daily_limit", 2).coerceIn(0, 10))
        }
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            Text("每日学习步骤上限 · $learningLimit", modifier = Modifier.weight(1f))
            Row {
                TextButton(enabled = learningLimit > 0, onClick = {
                    learningLimit--; prefs.edit().putInt("$characterId:learning_daily_limit", learningLimit).commit()
                }) { Text("减少") }
                TextButton(enabled = learningLimit < 10, onClick = {
                    learningLimit++; prefs.edit().putInt("$characterId:learning_daily_limit", learningLimit).commit()
                }) { Text("增加") }
            }
        }
        val todayBudget = remember(characterId, learningRevision, learningLimit) {
            com.jiacimu.lulu.data.CharacterLearningAgenda.budget(characterId)
        }
        Text("今日已用 ${todayBudget.used} 步 · 剩余 ${todayBudget.remaining} 步",
            style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        if (showRecords) {
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
                TextButton(enabled = goal.optString("status") != "running",
                    onClick = {
                        com.jiacimu.lulu.data.CharacterLearningAgenda.deleteGoal(
                            characterId, goal.optString("id"))
                    }) { Text("删除记录", color = MaterialTheme.colorScheme.error) }
            }
        }
        val insightRevision by com.jiacimu.lulu.data.CharacterLearningLedger.revisions.collectAsState()
        val insights = remember(characterId, insightRevision) {
            com.jiacimu.lulu.data.CharacterLearningLedger.listInsights(characterId)
        }
        if (learningGoals.isEmpty() && insights.isEmpty()) {
            Surface(color = MaterialTheme.colorScheme.surfaceVariant,
                shape = androidx.compose.foundation.shape.RoundedCornerShape(18.dp)) {
                Column(Modifier.fillMaxWidth().padding(20.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("还没有可追溯的学习记录", fontWeight = FontWeight.SemiBold)
                    Text("角色真实阅读、探索、练习后，留下理解或问题才会显示在这里。空白不代表角色没有人格或生活。",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
        }
        if (insights.isNotEmpty()) {
            Text("读后理解与学习笔记", style = MaterialTheme.typography.titleMedium)
            insights.take(30).forEach { insight ->
                HorizontalDivider()
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                    Column(Modifier.weight(1f)) {
                        Text(insight.topic, fontWeight = FontWeight.Medium)
                        Text(insight.understanding.take(220),
                            style = MaterialTheme.typography.bodySmall)
                    }
                    TextButton(onClick = {
                        com.jiacimu.lulu.data.CharacterLearningLedger.deleteInsight(
                            characterId, insight.sourceEvidenceId)
                    }) { Text("删除", color = MaterialTheme.colorScheme.error) }
                }
            }
        }
        if (showGrowthRecords) {
        Text("可追溯成长", style = MaterialTheme.typography.titleMedium)
        val revision by CharacterDevelopmentStore.revisions.collectAsState()
        val records = remember(characterId, revision) { CharacterDevelopmentStore.active(characterId) }
        records.takeLast(15).asReversed().forEach { record ->
            HorizontalDivider()
            Text(record.kind.label, style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant)
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                Text(record.content, modifier = Modifier.weight(1f))
                TextButton(onClick = { CharacterDevelopmentStore.retire(characterId, record.id) }) {
                    Text("删除变化", color = MaterialTheme.colorScheme.error)
                }
            }
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
    }
}
