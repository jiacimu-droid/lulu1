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
import com.jiacimu.lulu.data.CharacterLearningLedger
import com.jiacimu.lulu.data.CharacterLearningAgenda
import com.jiacimu.lulu.data.SharedExperienceTimeline
import java.time.ZoneId
import kotlinx.coroutines.launch
import java.time.format.DateTimeFormatter

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
        // Device-wide local shell consent belongs to the owner, never to a character model.
        // The per-character "screen" toggle above still gates actual actions.
        var advancedPhoneOpen by remember { mutableStateOf(false) }
        TextButton(onClick = { advancedPhoneOpen = !advancedPhoneOpen }) {
            Text(if (advancedPhoneOpen) "收起后台手机操作设置" else "后台手机操作（无线调试）")
        }
        if (advancedPhoneOpen) {
            val debug = com.jiacimu.lulu.system.LuluWirelessDebugging
            val scope = rememberCoroutineScope()
            var pairPort by remember { mutableStateOf("") }
            var connectPort by remember { mutableStateOf("") }
            var pairingCode by remember { mutableStateOf("") }
            var setupBusy by remember { mutableStateOf(false) }
            var setupStatus by remember { mutableStateOf(
                if (debug.hasPriorPairing(context)) "本机保存过配对身份，需重新核实无线调试是否已连接" else "尚未配对"
            ) }
            Text("只在本机配对 Android 无线调试。它授权的是手机操作引擎，不是另一个 AI；配对码不会发送给角色模型。",
                style = MaterialTheme.typography.bodySmall)
            TextButton(onClick = {
                runCatching { context.startActivity(android.content.Intent(
                    android.provider.Settings.ACTION_APPLICATION_DEVELOPMENT_SETTINGS)) }
                    .onFailure { setupStatus = "无法自动打开开发者选项，请手动进入系统设置" }
            }) { Text("打开系统开发者选项") }
            OutlinedTextField(
                value = pairPort, onValueChange = { pairPort = it.filter(Char::isDigit).take(5) },
                singleLine = true, label = { Text("系统显示的配对端口") },
                modifier = Modifier.fillMaxWidth(),
            )
            OutlinedTextField(
                value = pairingCode, onValueChange = { pairingCode = it.filter(Char::isDigit).take(6) },
                singleLine = true, label = { Text("无线配对码（仅本机使用）") },
                modifier = Modifier.fillMaxWidth(),
                visualTransformation = androidx.compose.ui.text.input.PasswordVisualTransformation(),
            )
            Button(enabled = !setupBusy && (pairPort.toIntOrNull()?.let { it in 1..65535 } == true) &&
                pairingCode.length == 6, onClick = {
                val port = pairPort.toIntOrNull() ?: return@Button
                val oneTimeCode = pairingCode
                pairingCode = ""
                setupBusy = true
                scope.launch {
                    try {
                        setupStatus = debug.pair(context, port, oneTimeCode)
                    } catch (e: Exception) {
                        setupStatus = "无线配对失败：" + e.message.orEmpty().take(150)
                    } finally { setupBusy = false }
                }
            }) { Text(if (setupBusy) "正在配对…" else "配对本机无线调试") }
            OutlinedTextField(
                value = connectPort, onValueChange = { connectPort = it.filter(Char::isDigit).take(5) },
                singleLine = true, label = { Text("连接端口（可留空自动发现，与配对端口不同）") },
                modifier = Modifier.fillMaxWidth(),
            )
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                TextButton(enabled = !setupBusy && debug.hasPriorPairing(context), onClick = {
                    setupBusy = true
                    scope.launch {
                        try { setupStatus = debug.test(context, connectPort.toIntOrNull()) }
                        catch (e: Exception) {
                            setupStatus = "连接失败：" + e.message.orEmpty().take(150)
                        } finally { setupBusy = false }
                    }
                }) { Text("检查连接") }
                TextButton(enabled = !setupBusy && debug.hasPriorPairing(context), onClick = {
                    runCatching { debug.forget(context) }
                        .onSuccess { setupStatus = "已清除露露机本地密钥；请到系统无线调试中撤销已配对设备" }
                        .onFailure { setupStatus = "本地撤销失败：" + it.message.orEmpty().take(100) }
                }) { Text("撤销本机密钥") }
            }
            Text(setupStatus, style = MaterialTheme.typography.bodySmall)
            Text("连接通过只证明 shell 权限可用；后台独立屏幕和第三方 App 操作仍需完成助手安装、启动和真机验证。关闭无线调试或撤销配对后不可继续控制。",
                style = MaterialTheme.typography.bodySmall)
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
    var limit by remember(characterId) { mutableIntStateOf(prefs.getInt("$characterId:learning_daily_limit", 5).coerceIn(0, 30)) }
    val revision by CharacterLearningAgenda.revisions.collectAsState()
    val timelineRevision by SharedExperienceTimeline.revisions.collectAsState()
    val learnedRevision by CharacterLearningLedger.revisions.collectAsState()
    val budget = remember(characterId, revision, limit) { CharacterLearningAgenda.budget(characterId) }
    val goals = remember(characterId, revision, timelineRevision) {
        CharacterLearningAgenda.goals(characterId).filter { it.optString("status") != "dropped" }
    }
    val actualSteps = remember(characterId, timelineRevision) {
        SharedExperienceTimeline.recentEvents(characterId, 200)
            .asSequence().filter { event ->
                event.source.startsWith("learning-material:") ||
                    event.source.startsWith("learning-search:") ||
                    event.source.startsWith("learning-practice:") ||
                    event.source.startsWith("learning-review:")
            }.sortedByDescending { it.occurredAt }.take(4).toList()
    }
    val insights = remember(characterId, learnedRevision, timelineRevision) {
        CharacterLearningLedger.listInsights(characterId)
    }
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text("自主成长", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
        var quotaOpen by remember(characterId) { mutableStateOf(false) }
        TextButton(onClick = { quotaOpen = !quotaOpen }) {
            Text(if (quotaOpen) "收起研究调用额度" else "研究调用额度")
        }
        if (quotaOpen) {
            Row(Modifier.fillMaxWidth(), verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
                Text("每天最多 $limit 次研究操作", Modifier.weight(1f))
                TextButton(enabled = limit > 0, onClick = {
                    limit--; prefs.edit().putInt("$characterId:learning_daily_limit", limit).commit()
                }) { Text("−") }
                TextButton(enabled = limit < 30, onClick = {
                    limit++; prefs.edit().putInt("$characterId:learning_daily_limit", limit).commit()
                }) { Text("+") }
            }
            Text("今日已用 ${budget.used} 次", style = MaterialTheme.typography.bodySmall)
        }
        HorizontalDivider()
        Text("自己想改进的事 · ${goals.size}", style = MaterialTheme.typography.titleSmall)
        goals.take(4).forEach { goal ->
            val stage = when (goal.optString("status")) {
                "planned" -> "准备了解"
                "running" -> "正在学习"
                "research" -> "已读，待理解"
                "practice" -> "已理解，可练习"
                "failed" -> "上次失败"
                "paused" -> "暂缓"
                else -> "待继续"
            }
            Text("${goal.optString("topic")} · $stage", style = MaterialTheme.typography.bodySmall)
            goal.optString("error").takeIf(String::isNotBlank)?.let {
                Text(it, style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.error)
            }
        }
        if (goals.isEmpty()) Text("暂无", style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant)
        HorizontalDivider()
        Text("真实探索与尝试", style = MaterialTheme.typography.titleSmall)
        actualSteps.forEach { event ->
            val time = event.occurredAt.atZone(ZoneId.systemDefault())
                .format(DateTimeFormatter.ofPattern("M/d HH:mm"))
            Text("$time · ${event.channel}", style = MaterialTheme.typography.bodySmall)
        }
        if (actualSteps.isEmpty()) Text("暂无真实执行记录",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant)
        Text("有效理解 · ${insights.size}", style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}
