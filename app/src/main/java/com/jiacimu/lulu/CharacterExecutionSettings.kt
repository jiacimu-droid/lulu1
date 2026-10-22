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
        Text("音乐控制需要在手机权限页开启「通知读取」，并让音乐 App 建立播放会话；角色获媒体授权后可按实际情况播放、暂停、切歌及有限度调节媒体音量。", style = MaterialTheme.typography.bodySmall)
        var wakeMusic by remember(characterId) { mutableStateOf(prefs.getBoolean("$characterId:wake_music_enabled", false)) }
        Row(Modifier.fillMaxWidth(), verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
            Text("允许角色自主选择音乐叫醒", modifier = Modifier.weight(1f))
            Switch(wakeMusic, {
                wakeMusic = it
                prefs.edit().putBoolean("$characterId:wake_music_enabled", it).commit()
            })
        }
        Text("开启表示允许选择，并不意味着每次叫醒都放音乐。角色会依据当时情况选择铃声、自然叫醒或已有播放器；正在通话时优先沿用原电话。媒体音量会受安全上限约束。",
            style = MaterialTheme.typography.bodySmall)
        CharacterMusicSensingSettings(characterId)
        var packages by remember(characterId) { mutableStateOf(prefs.getString("$characterId:notification_packages", "").orEmpty()) }
        OutlinedTextField(packages, { packages = it; prefs.edit().putString("$characterId:notification_packages", it).commit() },
            label = { Text("重要通知的 App 包名，逗号分隔") }, modifier = Modifier.fillMaxWidth().keepFocusedFieldVisible())



    }
}

/** Configuration only: knowledge is in memory, active questions are in 此刻. */
@Composable
internal fun CharacterLearningSettings(characterId: String) {
    val context = LocalContext.current
    val prefs = remember { context.getSharedPreferences("lulu_execution_policy", Context.MODE_PRIVATE) }
    var limit by remember(characterId) { mutableIntStateOf(prefs.getInt("$characterId:learning_daily_limit", 2).coerceIn(0, 10)) }
    val revision by com.jiacimu.lulu.data.CharacterLearningAgenda.revisions.collectAsState()
    val budget = remember(characterId, revision, limit) { com.jiacimu.lulu.data.CharacterLearningAgenda.budget(characterId) }
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text("学习与探索", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
        Row(Modifier.fillMaxWidth(), verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
            Text("每日学习步骤上限 · $limit", Modifier.weight(1f))
            TextButton(enabled = limit > 0, onClick = {
                limit--; prefs.edit().putInt("$characterId:learning_daily_limit", limit).commit()
            }) { Text("减少") }
            TextButton(enabled = limit < 10, onClick = {
                limit++; prefs.edit().putInt("$characterId:learning_daily_limit", limit).commit()
            }) { Text("增加") }
        }
        Text("今日已用 ${budget.used} 步 · 剩余 ${budget.remaining} 步",
            style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}
