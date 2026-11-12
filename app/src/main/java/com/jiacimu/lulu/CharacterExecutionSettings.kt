package com.jiacimu.lulu

import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.graphics.asImageBitmap

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
import kotlinx.coroutines.isActive
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
            var pairAddress by remember {
                mutableStateOf(debug.suggestedWifiHost(context).orEmpty())
            }
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
                value = pairAddress,
                onValueChange = { pairAddress = it.trim().take(45) },
                singleLine = true,
                label = { Text("本机Wi-Fi IP（配对弹窗中的IP）") },
                supportingText = { Text("自动识别：" +
                    (debug.suggestedWifiHost(context) ?: "失败，请手动输入系统显示的IP") +
                    "；只连接本机，不连接其他设备") },
                modifier = Modifier.fillMaxWidth(),
            )
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
                        setupStatus = debug.pair(context, port, oneTimeCode, pairAddress)
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
                        try { setupStatus = debug.test(context, connectPort.toIntOrNull(), pairAddress) }
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
            // Explicit owner-initiated verification, not an inferred character
            // action. Both requests go through ToolRouter for durable receipts.
            // The verified virtual-display pixels are sent as a labeled private
            // chat image; never a screenshot of the owner's current screen.
            var testAppName by remember { mutableStateOf("") }
            OutlinedTextField(
                value = testAppName,
                onValueChange = { testAppName = it.take(80) },
                singleLine = true,
                label = { Text("后台屏自测：已安装的应用名称") },
                supportingText = {
                    Text("例如填「抖音」。不能与主人当前主屏正在使用的 App 相同。")
                },
                modifier = Modifier.fillMaxWidth(),
            )
            Button(enabled = !setupBusy && debug.hasPriorPairing(context) &&
                testAppName.trim().isNotEmpty(), onClick = {
                val name = testAppName.trim()
                setupBusy = true
                scope.launch {
                    try {
                        val id = "owner-virtual-test-" + java.util.UUID.randomUUID().toString()
                        val started = org.json.JSONObject(
                            com.jiacimu.lulu.data.ToolRouter.execute(
                                context, characterId, "background_phone",
                                org.json.JSONObject().put("action", "start"),
                                requestId = id + ":start", userRequested = true))
                        if (!started.optBoolean("success")) {
                            setupStatus = "后台屏未创建：" +
                                started.optString("error").ifBlank {
                                    started.optString("verification", started.optString("status"))
                                }.take(150)
                        } else {
                            setupStatus = if (started.optBoolean("ownFocusVerifiedByDisplayFlags"))
                                "后台屏已获得真正的独立焦点（ID=${started.optInt("displayId")}），正在验证目标 App"
                                else "后台屏没有独立焦点实证；不向主人主屏注入任何操作"
                            val launched = org.json.JSONObject(
                                com.jiacimu.lulu.data.ToolRouter.execute(
                                    context, characterId, "background_phone",
                                    org.json.JSONObject()
                                        .put("action", "launch")
                                        .put("appName", name)
                                        .put("sendToChat", true)
                                        .put("caption", "后台虚拟屏自测 · " + name),
                                    requestId = id + ":launch", userRequested = true))
                            setupStatus = if (launched.optBoolean("success")) {
                                "后台目标 App 已在独立屏核实（ID=${launched.optInt("displayId")}）；" +
                                    (if (launched.optBoolean("frameValidated")) "后台真实帧核验成功；"
                                    else "后台截图没有通过帧核验；") +
                                    (if (launched.optBoolean("sentToChat"))
                                        "后台画面已作为图片发进角色私聊"
                                    else "后台图片未发送：" +
                                        launched.optString("shareError").take(90))
                            } else {
                                "后台启动未通过真实核验：" +
                                    launched.optString("error").ifBlank {
                                        launched.optString("verification", launched.optString("status"))
                                    }.take(150)
                            }
                        }
                    } catch (e: Exception) {
                        setupStatus = "后台屏真机自测失败：" + e.message.orEmpty().take(160)
                    } finally { setupBusy = false }
                }
            }) { Text(if (setupBusy) "自测中…" else "测试独立后台屏并发真实截图") }
            BackgroundPhoneOwnerViewer(characterId)
            TextButton(enabled = !setupBusy, onClick = {
                setupBusy = true
                scope.launch {
                    try {
                        val stopped = com.jiacimu.lulu.system.LuluBackgroundPhoneSession.stop(context)
                        setupStatus = if (stopped) "后台虚拟屏幕已停止，新任务也已暂停"
                            else "后台操作已暂停，主人重新允许前不会启动"
                    } catch (e: Exception) {
                        setupStatus = "停止失败：" + e.message.orEmpty().take(120)
                    } finally { setupBusy = false }
                }
            }) { Text("立即停止后台手机操作") }
            TextButton(onClick = {
                com.jiacimu.lulu.system.LuluBackgroundPhoneSession.resumeByOwner(context)
                setupStatus = "已允许新后台操作会话；仍需按角色屏幕权限核实"
            }) { Text("重新允许后台操作") }
            Text(setupStatus, style = MaterialTheme.typography.bodySmall)
            Text("配对使用系统弹窗显示的Wi-Fi IP、配对端口、6位码，保持弹窗打开；连接端口来自无线调试主页面，与配对端口不同，可以留空自动发现。",
                style = MaterialTheme.typography.bodySmall)
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

/**
 * The owner can look into the role's ALREADY running independent screen.
 * Reading the frame never starts a display, injects input, invokes the model,
 * records a second role history, or captures the phone's main screen.
 *
 * Refresh is opt-in and lifecycle-scoped: leaving this page or pausing the
 * Activity stops polling, avoiding permanent ADB/image/model traffic.
 */
@Composable
private fun BackgroundPhoneOwnerViewer(characterId: String) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val lifecycleOwner = androidx.lifecycle.compose.LocalLifecycleOwner.current
    var visible by remember(characterId) { mutableStateOf(false) }
    var live by remember(characterId) { mutableStateOf(false) }
    var loading by remember(characterId) { mutableStateOf(false) }
    var saving by remember(characterId) { mutableStateOf(false) }
    var status by remember(characterId) {
        mutableStateOf("只查看已存在的角色后台屏，不会偷偷打开 App 或截主人主屏。")
    }
    var currentFrame by remember(characterId) {
        mutableStateOf<com.jiacimu.lulu.system.LuluBackgroundPhoneSession.OwnerPreviewFrame?>(null)
    }
    var currentImage by remember(characterId) {
        mutableStateOf<androidx.compose.ui.graphics.ImageBitmap?>(null)
    }
    var isForeground by remember(lifecycleOwner) {
        mutableStateOf(lifecycleOwner.lifecycle.currentState.isAtLeast(
            androidx.lifecycle.Lifecycle.State.RESUMED))
    }
    DisposableEffect(lifecycleOwner) {
        val observer = androidx.lifecycle.LifecycleEventObserver { _, event ->
            when (event) {
                androidx.lifecycle.Lifecycle.Event.ON_RESUME -> isForeground = true
                androidx.lifecycle.Lifecycle.Event.ON_PAUSE,
                androidx.lifecycle.Lifecycle.Event.ON_STOP -> isForeground = false
                else -> Unit
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    suspend fun refreshFrame() {
        if (loading || saving) return
        loading = true
        try {
            val frame = com.jiacimu.lulu.system.LuluBackgroundPhoneSession.ownerPreview(
                context, characterId)
            val bytes = java.util.Base64.getDecoder().decode(
                frame.imageDataUrl.removePrefix("data:image/jpeg;base64,"))
            val pixels = android.graphics.BitmapFactory.decodeByteArray(bytes, 0, bytes.size)
                ?: error("虚拟屏画面无法解码")
            currentImage = pixels.asImageBitmap()
            currentFrame = frame
            status = "后台屏 ID=${frame.displayId} · ${frame.packageName} · " +
                java.time.LocalTime.now().format(java.time.format.DateTimeFormatter.ofPattern("HH:mm:ss")) +
                (if (frame.newerThanPreviousCapture) " · 新帧" else " · 当前帧")
        } catch (cancel: kotlinx.coroutines.CancellationException) {
            throw cancel
        } catch (failure: Exception) {
            currentFrame = null
            currentImage = null
            live = false
            status = failure.message.orEmpty().take(180).ifBlank { "后台画面暂不可见" }
        } finally {
            loading = false
        }
    }

    // Opening the owner viewer is an explicit, one-shot request for a real
    // background frame; live polling remains off until separately enabled.
    LaunchedEffect(visible, characterId) {
        if (visible) refreshFrame()
    }
    // No polling at all unless the owner explicitly enables it, and none
    // after navigation / activity pause. No additional model API call.
    LaunchedEffect(visible, live, isForeground, characterId) {
        if (visible && live && isForeground) {
            while (kotlinx.coroutines.currentCoroutineContext().isActive) {
                refreshFrame()
                kotlinx.coroutines.delay(3_000L)
            }
        }
    }

    HorizontalDivider()
    TextButton(onClick = {
        visible = !visible
        if (!visible) {
            live = false
            currentFrame = null
            currentImage = null
        }
    }) { Text(if (visible) "收起角色后台屏预览" else "看看角色正在后台看什么") }
    if (visible) {
        Text("主人专用 · 只看独立虚拟屏，不操作角色；预览不调用 AI、不保存图片。",
            style = MaterialTheme.typography.bodySmall)
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = androidx.compose.ui.Alignment.CenterVertically,
        ) {
            Text("自动刷新（每 3 秒）", modifier = Modifier.weight(1f),
                style = MaterialTheme.typography.bodySmall)
            Switch(checked = live, onCheckedChange = { live = it })
            TextButton(enabled = !loading && !saving, onClick = {
                scope.launch { refreshFrame() }
            }) { Text(if (loading) "查看中…" else "刷新") }
        }
        val bitmap = currentImage
        if (bitmap != null) {
            androidx.compose.foundation.Image(
                bitmap = bitmap,
                contentDescription = "当前角色独立后台虚拟屏画面（不是主人主屏）",
                modifier = Modifier.fillMaxWidth().height(390.dp),
                contentScale = androidx.compose.ui.layout.ContentScale.Fit,
            )
            TextButton(enabled = !loading && !saving && currentFrame != null, onClick = {
                val frame = currentFrame ?: return@TextButton
                saving = true
                scope.launch {
                    try {
                        check(com.jiacimu.lulu.system.LuluScreenAwareness.onDemandAllowed(context) &&
                            !com.jiacimu.lulu.system.LuluBackgroundPhoneSession.ownerHasPaused(context) &&
                            !com.jiacimu.lulu.system.LuluScreenAwareness.isExcluded(
                                context, frame.packageName)) {
                            "主人已撤销屏幕授权、暂停操作或排除了当前应用，不能再分享"
                        }
                        // Share precisely the already verified background frame
                        // the owner chose to save, not a new main-screen capture.
                        val bytes = java.util.Base64.getDecoder().decode(
                            frame.imageDataUrl.removePrefix("data:image/jpeg;base64,"))
                        val pixels = android.graphics.BitmapFactory.decodeByteArray(bytes, 0, bytes.size)
                            ?: error("后台截图解码失败")
                        try {
                            val delivered = com.jiacimu.lulu.data.CompanionActionRuntime
                                .sendCapturedScreenshot(
                                    context, characterId, pixels,
                                    "主人主动保存的后台虚拟屏（非角色自主分享）",
                                    frame.packageName, sourceDisplay = "virtual")
                            status = if (delivered.success) "这张后台画面已保存到角色私聊"
                                else delivered.summary
                        } finally {
                            pixels.recycle()
                        }
                    } catch (cancel: kotlinx.coroutines.CancellationException) {
                        throw cancel
                    } catch (failure: Exception) {
                        status = "保存失败：" + failure.message.orEmpty().take(140)
                    } finally { saving = false }
                }
            }) { Text(if (saving) "保存中…" else "把这张后台图保存进私聊（主人操作）") }
        }
        Text(status, style = MaterialTheme.typography.bodySmall)
        Text("只有当前页面展开时才刷新；关闭页面即暂停。不会自动替江渡分享，是否主动分享仍由他自己决定。后台 App 没有新画面时会如实提示，不会用主人主屏替代。",
            style = MaterialTheme.typography.bodySmall)
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
