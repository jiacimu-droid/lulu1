package com.jiacimu.lulu

import android.Manifest
import android.app.AppOpsManager
import android.app.usage.UsageEvents
import android.app.usage.UsageStatsManager
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.net.Uri
import android.os.BatteryManager
import android.os.Build
import android.os.PowerManager
import android.provider.AlarmClock
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import androidx.core.app.NotificationManagerCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.jiacimu.lulu.ai.ModelUsage
import com.jiacimu.lulu.ai.archiveIdFor
import com.jiacimu.lulu.data.CompanionPresenceStore
import com.jiacimu.lulu.health.GadgetbridgeHealthStore
import com.jiacimu.lulu.system.LuluAccessibilityService
import com.jiacimu.lulu.system.LuluScreenAwareness
import com.jiacimu.lulu.system.LuluLocationProvider
import com.jiacimu.lulu.system.LuluMediaPlayback
import com.jiacimu.lulu.system.LuluNotificationListenerService
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

private val CapabilityPaper = Color.White
private val CapabilityCard = Color(0xFFFCFCFC)
private val CapabilityBorder = Color(0xFFE7E7E7)
private val CapabilityInk = Color(0xFF1D1D1F)
private val CapabilityMuted = Color(0xFF7A7A7E)
private val CapabilityOn = Color(0xFFF4F4F4)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun LuluCapabilitiesScreen(onBack: () -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var refreshKey by remember { mutableIntStateOf(0) }
    var freshLocationLabel by remember { mutableStateOf<String?>(null) }
    var locating by remember { mutableStateOf(false) }
    var musicTestReport by remember { mutableStateOf("") }
    var wakeQueueStatus by remember { mutableStateOf("正在读取 Android 后台任务队列…") }
    val accessibility by LuluAccessibilityService.state.collectAsState()
    val notificationConnected by LuluNotificationListenerService.isConnected.collectAsState()
    val presenceStates by CompanionPresenceStore.states.collectAsState()
    val companionStatus by com.jiacimu.lulu.data.BackgroundCompanionControl.status.collectAsState()
    var companionEnabled by remember(refreshKey) {
        mutableStateOf(com.jiacimu.lulu.data.BackgroundCompanionControl.enabled(context))
    }
    val lifecycleOwner = LocalLifecycleOwner.current
    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) refreshKey++
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    remember(context) {
        GadgetbridgeHealthStore.initialize(context.applicationContext)
        Unit
    }
    val gadgetbridgeState by GadgetbridgeHealthStore.state.collectAsState()

    val runtimeLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions(),
    ) { refreshKey++ }
    val gadgetbridgePicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) {
            scope.launch { GadgetbridgeHealthStore.connect(context, uri) }
        }
    }

    fun open(intent: Intent) {
        runCatching { context.startActivity(intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }
        refreshKey++
    }

    val snapshot = remember(refreshKey, accessibility.connected, notificationConnected) {
        CapabilitySnapshot.read(context, accessibility.connected, notificationConnected)
    }
    val musicDiagnostic = remember(refreshKey, notificationConnected) {
        LuluMediaPlayback.observation(context)
    }
    val library by com.jiacimu.lulu.ai.LuluAiServices.connectionStore.library.collectAsState()
    val chatArchiveId = library.archiveIdFor(ModelUsage.Chat)
    val activeArchive = library.archives.firstOrNull { it.id == chatArchiveId }
    val backgroundModelLabel = activeArchive?.let(com.jiacimu.lulu.ai.LuluAiServices.connectionStore::archiveLabel)
        ?: "尚未选择聊天模型"
    val latestImageTransport = remember(refreshKey) {
        QqChatImageModelInput.lastReadStatus(context)
    }
    val latestPerception = presenceStates.values
        .filter { it.lastPerceptionAt != null }
        .maxByOrNull { it.lastPerceptionAt ?: Instant.EPOCH }
    val latestPerceptionLabel = latestPerception?.let { state ->
        val time = state.lastPerceptionAt
            ?.atZone(ZoneId.systemDefault())
            ?.format(DateTimeFormatter.ofPattern("MM-dd HH:mm"))
            .orEmpty()
        val actor = com.jiacimu.lulu.data.MigratedDomainStores.characters.settings.value
            .get(state.characterId)?.displayName?.takeIf(String::isNotBlank)
            ?: state.characterId.take(20)
        "$actor · $time · ${state.lastPerceptionNote.ifBlank { "已运行" }}"
    } ?: "安装新版后尚未留下感知运行记录"

    LaunchedEffect(refreshKey, latestPerceptionLabel) {
        wakeQueueStatus = withContext(Dispatchers.IO) {
            com.jiacimu.lulu.data.ProactivePerceptionScheduler.backgroundWakeStatus(context)
        }
    }

    fun refreshLocation() {
        if (!snapshot.preciseLocation || locating) return
        locating = true
        scope.launch {
            freshLocationLabel = LuluLocationProvider.freshLocation(context)?.let(LuluLocationProvider::label)
                ?: "暂时没有获取到新位置，请确认系统定位已开启"
            locating = false
        }
    }

    LaunchedEffect(refreshKey, snapshot.preciseLocation) {
        if (snapshot.preciseLocation) refreshLocation()
    }

    Scaffold(
        containerColor = CapabilityPaper,
        topBar = {
            TopAppBar(
                title = { Text("权限与能力", fontWeight = FontWeight.SemiBold) },
                navigationIcon = { IconButton(onClick = onBack) { Icon(Icons.Outlined.ArrowBack, "返回") } },
                actions = { IconButton(onClick = { refreshKey++ }) { Icon(Icons.Outlined.Refresh, "刷新") } },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = CapabilityPaper),
            )
        },
    ) { padding ->
        LazyColumn(
            modifier = Modifier.fillMaxSize().padding(padding).imePadding(),
            contentPadding = PaddingValues(16.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            item { CapabilitySummaryCard(snapshot, backgroundModelLabel) }
            item {
                CapabilityInfoCard(
                    title = "后台任务与权限 · 全局",
                    body = "全局聊天模型：$backgroundModelLabel\n跨角色最近一次感知：$latestPerceptionLabel\n跨角色最近一次图片准备：$latestImageTransport\n$wakeQueueStatus\n这里是全局任务状态，并不能证明某个角色成功。该角色的感知与识图结果请点其头像查看「此刻」。",
                )
            }

            item { CapabilitySectionTitle("授权信息", "允许角色读取真实信息，但不会因此修改手机内容") }
            item {
                CapabilityRow(
                    Icons.Outlined.LocationOn,
                    "精确位置",
                    if (locating) "正在主动获取高精度位置…" else freshLocationLabel ?: snapshot.locationLabel,
                    snapshot.preciseLocation,
                ) {
                    runtimeLauncher.launch(arrayOf(Manifest.permission.ACCESS_COARSE_LOCATION, Manifest.permission.ACCESS_FINE_LOCATION))
                }
            }
            item {
                CapabilityRow(
                    Icons.Outlined.Apps,
                    "当前使用的应用",
                    snapshot.foregroundAppLabel,
                    snapshot.usageAccess,
                ) { open(Intent(Settings.ACTION_USAGE_ACCESS_SETTINGS)) }
            }
            item {
                CapabilityRow(
                    Icons.Outlined.NotificationsActive,
                    "通知读取",
                    "读取近期通知，也用于音乐 App 的系统媒体会话访问",
                    snapshot.notificationAccess,
                ) { open(Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS)) }
            }
            item {
                CapabilityRow(
                    Icons.Outlined.MusicNote,
                    "音乐播放识别 / 控制",
                    musicDiagnostic + "\n当前只控制支持 Android MediaSession 的播放器；没有会话不代表没有声音。点击可检查通知使用权。",
                    snapshot.notificationAccess,
                ) { open(Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS)) }
            }
            item {
                Column(verticalArrangement = Arrangement.spacedBy(5.dp)) {
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        OutlinedButton(onClick = {
                            val report = org.json.JSONObject(LuluMediaPlayback.control(context, "play"))
                            musicTestReport = if (report.optBoolean("accepted"))
                                "播放请求已发送到${report.optString("packageName")}。 ${report.optString("verification")}"
                                else "播放失败：${report.optString("error")}"
                            refreshKey++
                        }) { Text("测试播放") }
                        OutlinedButton(onClick = {
                            val report = org.json.JSONObject(LuluMediaPlayback.control(context, "pause"))
                            musicTestReport = if (report.optBoolean("accepted"))
                                "暂停请求已发送到${report.optString("packageName")}。 ${report.optString("verification")}"
                                else "暂停失败：${report.optString("error")}"
                            refreshKey++
                        }) { Text("测试暂停") }
                    }
                    if (musicTestReport.isNotBlank()) {
                        Text(musicTestReport, color = CapabilityMuted, fontSize = 12.sp)
                    }
                    Text("直接调用 Android 媒体控制，不产生模型费用。建议在音乐App开始播放后测试；如仍无法暂停，请查看返回的播放器状态。",
                        color = CapabilityMuted, fontSize = 11.sp)
                }
            }
            item {
                CapabilityRow(
                    Icons.Outlined.Storage,
                    "Gadgetbridge 健康数据",
                    when {
                        gadgetbridgeState.importing -> "正在解析 ${gadgetbridgeState.sourceName.ifBlank { "Gadgetbridge.db" }}…"
                        gadgetbridgeState.connected -> "已授权：${gadgetbridgeState.sourceName.ifBlank { "Gadgetbridge.db" }}；每小时自动刷新"
                        else -> "选择 /Download/手环/Gadgetbridge.db，只读取该文件"
                    },
                    gadgetbridgeState.connected,
                ) {
                    gadgetbridgePicker.launch(
                        arrayOf(
                            "application/vnd.sqlite3",
                            "application/x-sqlite3",
                            "application/octet-stream",
                            "*/*",
                        ),
                    )
                }
            }

            item { CapabilitySectionTitle("授权工具", "允许角色执行动作，或保障主动能力在后台运行") }
            item {
                CapabilityRow(
                    Icons.Outlined.Alarm,
                    "系统闹钟",
                    "角色可以创建、查看和取消由露露设置的真实系统闹钟",
                    true,
                ) { open(Intent(AlarmClock.ACTION_SHOW_ALARMS)) }
            }
            item {
                CapabilityRow(
                    Icons.Outlined.TouchApp,
                    "屏幕感知与控制",
                    snapshot.accessibilityLabel,
                    snapshot.accessibility,
                ) { open(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)) }
            }
            item { LuluScreenAwarenessSettings() }
            item {
                CapabilityRow(
                    Icons.Outlined.PictureInPictureAlt,
                    "悬浮窗",
                    "让角色请求查看屏幕、邀请返回时浮出小弹窗",
                    snapshot.overlay,
                ) { open(Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:${context.packageName}"))) }
            }
            item {
                CapabilityRow(
                    Icons.Outlined.Notifications,
                    "发送通知",
                    "主动消息、来电和闹钟提醒",
                    snapshot.notifications,
                ) {
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU && !snapshot.notifications) {
                        runtimeLauncher.launch(arrayOf(Manifest.permission.POST_NOTIFICATIONS))
                    } else open(Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS)
                        .putExtra(Settings.EXTRA_APP_PACKAGE, context.packageName))
                }
            }
            item {
                val channel = com.jiacimu.lulu.data.CharacterMessageNotifications.channelId(context)
                val importance = context.getSystemService(android.app.NotificationManager::class.java)
                    .getNotificationChannel(channel)?.importance ?: 0
                CapabilityRow(Icons.Outlined.ChatBubbleOutline, "角色消息弹窗", "消息横幅、声音与锁屏提醒",
                    snapshot.notifications && importance >= android.app.NotificationManager.IMPORTANCE_HIGH) {
                    open(Intent(Settings.ACTION_CHANNEL_NOTIFICATION_SETTINGS)
                        .putExtra(Settings.EXTRA_APP_PACKAGE, context.packageName)
                        .putExtra(Settings.EXTRA_CHANNEL_ID, channel))
                }
            }
            item {
                Surface(shape = RoundedCornerShape(18.dp), color = CapabilityCard,
                    border = BorderStroke(1.dp, CapabilityBorder)) {
                    Row(Modifier.fillMaxWidth().padding(14.dp), verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f)) {
                            Text("后台陪伴常驻", color = CapabilityInk, fontWeight = FontWeight.Bold)
                            Text(if (companionEnabled) companionStatus.note else "已关闭，保留系统后台任务",
                                color = CapabilityMuted, fontSize = 12.sp)
                        }
                        Switch(checked = companionEnabled, onCheckedChange = {
                            com.jiacimu.lulu.data.BackgroundCompanionControl.setEnabled(context, it)
                            companionEnabled = it
                        })
                    }
                }
            }
            item {
                CapabilityRow(
                    Icons.Outlined.BatteryChargingFull,
                    "后台运行",
                    snapshot.batteryOptimizationLabel,
                    snapshot.ignoreBatteryOptimizations,
                ) {
                    open(Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS, Uri.parse("package:${context.packageName}")))
                }
            }
        }
    }
}

@Composable
private fun CapabilitySectionTitle(title: String, subtitle: String) {
    Column(Modifier.padding(top = 10.dp, start = 2.dp, end = 2.dp, bottom = 2.dp)) {
        Text(title, color = CapabilityInk, fontSize = 18.sp, fontWeight = FontWeight.Bold)
        Text(subtitle, color = CapabilityMuted, fontSize = 11.sp, lineHeight = 16.sp)
    }
}

@Composable
private fun CapabilityInfoCard(title: String, body: String) {
    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(18.dp),
        color = CapabilityCard,
        border = BorderStroke(1.dp, CapabilityBorder),
    ) {
        Column(Modifier.padding(15.dp), verticalArrangement = Arrangement.spacedBy(5.dp)) {
            Text(title, color = CapabilityInk, fontWeight = FontWeight.Bold)
            Text(body, color = CapabilityMuted, fontSize = 12.sp, lineHeight = 17.sp)
        }
    }
}

@Composable
private fun CapabilitySummaryCard(snapshot: CapabilitySnapshot, backgroundModelLabel: String) {
    Surface(
        shape = RoundedCornerShape(22.dp),
        color = CapabilityCard,
        border = BorderStroke(1.dp, CapabilityBorder),
    ) {
        Column(Modifier.fillMaxWidth().padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text("手机状态", fontWeight = FontWeight.Bold, fontSize = 18.sp, color = CapabilityInk)
            Text("电量 ${snapshot.batteryPercent}%${if (snapshot.charging) " · 正在充电" else ""}", color = CapabilityInk)
            if (snapshot.preciseLocation) Text(snapshot.locationLabel, color = CapabilityMuted, fontSize = 12.sp)
            if (snapshot.usageAccess) Text(snapshot.foregroundAppLabel, color = CapabilityMuted, fontSize = 12.sp)
            Text("后台感知模型：$backgroundModelLabel", color = CapabilityMuted, fontSize = 12.sp)
        }
    }
}

@Composable
private fun CapabilityRow(
    icon: ImageVector,
    title: String,
    subtitle: String,
    enabled: Boolean,
    onClick: () -> Unit,
) {
    Surface(
        modifier = Modifier.fillMaxWidth().clickable(onClick = onClick),
        shape = RoundedCornerShape(18.dp),
        color = CapabilityCard,
        border = BorderStroke(1.dp, CapabilityBorder),
    ) {
        Row(Modifier.fillMaxWidth().padding(14.dp), verticalAlignment = Alignment.CenterVertically) {
            Surface(shape = RoundedCornerShape(12.dp), color = CapabilityOn) {
                Icon(icon, null, tint = CapabilityInk, modifier = Modifier.padding(9.dp).size(22.dp))
            }
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                Text(title, color = CapabilityInk, fontWeight = FontWeight.Bold)
                Text(subtitle, color = CapabilityMuted, fontSize = 12.sp, lineHeight = 16.sp)
            }
            Text(if (enabled) "已开启" else "去开启", color = if (enabled) CapabilityInk else CapabilityMuted, fontSize = 12.sp)
            Spacer(Modifier.width(4.dp))
            Icon(Icons.Outlined.ChevronRight, null, tint = CapabilityMuted)
        }
    }
}

private data class CapabilitySnapshot(
    val preciseLocation: Boolean,
    val locationLabel: String,
    val usageAccess: Boolean,
    val foregroundAppLabel: String,
    val notificationAccess: Boolean,
    val accessibility: Boolean,
    val accessibilityLabel: String,
    val overlay: Boolean,
    val notifications: Boolean,
    val ignoreBatteryOptimizations: Boolean,
    val batteryOptimizationLabel: String,
    val batteryPercent: Int,
    val charging: Boolean,
) {
    companion object {
        fun read(context: Context, accessibilityConnected: Boolean, notificationConnected: Boolean): CapabilitySnapshot {
            val fine = context.granted(Manifest.permission.ACCESS_FINE_LOCATION)
            val usage = context.hasUsageAccess()
            val foreground = if (usage) context.recentForegroundPackage() else null
            val notification = notificationConnected || context.hasNotificationListenerAccess()
            val accessibility = accessibilityConnected || context.hasAccessibilityAccess()
            val overlay = Settings.canDrawOverlays(context)
            val notifications = NotificationManagerCompat.from(context).areNotificationsEnabled() &&
                (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU || context.granted(Manifest.permission.POST_NOTIFICATIONS))
            val power = context.getSystemService(PowerManager::class.java)
            val ignored = power.isIgnoringBatteryOptimizations(context.packageName)
            val batteryIntent = context.registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED))
            val level = batteryIntent?.getIntExtra(BatteryManager.EXTRA_LEVEL, -1) ?: -1
            val scale = batteryIntent?.getIntExtra(BatteryManager.EXTRA_SCALE, 100) ?: 100
            val percent = if (level >= 0 && scale > 0) level * 100 / scale else 0
            val status = batteryIntent?.getIntExtra(BatteryManager.EXTRA_STATUS, -1) ?: -1
            val charging = status == BatteryManager.BATTERY_STATUS_CHARGING || status == BatteryManager.BATTERY_STATUS_FULL
            return CapabilitySnapshot(
                preciseLocation = fine,
                locationLabel = if (fine) context.lastLocationLabel() else "允许后可主动读取当前位置、定位精度和更新时间",
                usageAccess = usage,
                foregroundAppLabel = foreground?.let { "最近前台应用：$it" } ?: "允许后只判断当前或最近使用的 App，不统计使用时长",
                notificationAccess = notification,
                accessibility = accessibility,
                accessibilityLabel = if (accessibility) "已连接；可读取当前界面，并执行返回、主页、通知栏和文字点击" else "读取当前界面，并执行返回、主页、通知栏和文字点击",
                overlay = overlay,
                notifications = notifications,
                ignoreBatteryOptimizations = ignored,
                batteryOptimizationLabel = if (ignored) "已解除电池优化限制" else "允许后台耗电，减少锁屏后的感知延迟",
                batteryPercent = percent,
                charging = charging,
            )
        }
    }
}

private fun Context.granted(permission: String): Boolean =
    ContextCompat.checkSelfPermission(this, permission) == PackageManager.PERMISSION_GRANTED

private fun Context.hasUsageAccess(): Boolean {
    val appOps = getSystemService(AppOpsManager::class.java)
    val mode = appOps.unsafeCheckOpNoThrow(AppOpsManager.OPSTR_GET_USAGE_STATS, android.os.Process.myUid(), packageName)
    return mode == AppOpsManager.MODE_ALLOWED
}

private fun Context.hasNotificationListenerAccess(): Boolean {
    val flat = Settings.Secure.getString(contentResolver, "enabled_notification_listeners").orEmpty()
    return flat.split(':').mapNotNull(ComponentName::unflattenFromString).any { it.packageName == packageName }
}

private fun Context.hasAccessibilityAccess(): Boolean {
    val flat = Settings.Secure.getString(contentResolver, Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES).orEmpty()
    return flat.split(':').mapNotNull(ComponentName::unflattenFromString).any {
        it.packageName == packageName && it.className.endsWith("LuluAccessibilityService")
    }
}

private fun Context.lastLocationLabel(): String {
    if (!granted(Manifest.permission.ACCESS_FINE_LOCATION)) return "未授权"
    val location = LuluLocationProvider.bestSystemLocation(this)
    return location?.let {
        "位置：%.6f, %.6f · 精度约 %.0f 米".format(it.latitude, it.longitude, it.accuracy)
    } ?: "已授权，等待设备产生新的定位"
}

private fun Context.recentForegroundPackage(): String? {
    val manager = getSystemService(UsageStatsManager::class.java)
    val end = System.currentTimeMillis()
    val events = manager.queryEvents(end - 10 * 60_000L, end)
    val event = UsageEvents.Event()
    var latestPackage: String? = null
    var latestTime = 0L
    while (events.hasNextEvent()) {
        events.getNextEvent(event)
        if ((event.eventType == UsageEvents.Event.ACTIVITY_RESUMED || event.eventType == UsageEvents.Event.MOVE_TO_FOREGROUND) && event.timeStamp >= latestTime) {
            latestTime = event.timeStamp
            latestPackage = event.packageName
        }
    }
    return latestPackage
}


/** Explicit owner consent is separate from Android's accessibility grant. */
@Composable
private fun LuluScreenAwarenessSettings() {
    val context = LocalContext.current
    val prefs = remember(context) { LuluScreenAwareness.preferences(context) }
    var consent by remember { mutableStateOf(LuluScreenAwareness.consented(context)) }
    var tracking by remember { mutableStateOf(LuluScreenAwareness.appTracking(context)) }
    var onDemand by remember { mutableStateOf(prefs.getBoolean(LuluScreenAwareness.ON_DEMAND, true)) }
    var autonomy by remember { mutableStateOf(prefs.getBoolean(LuluScreenAwareness.AUTONOMOUS, true)) }
    var summon by remember { mutableStateOf(prefs.getBoolean(LuluScreenAwareness.SUMMON, true)) }
    var excluded by remember { mutableStateOf(prefs.getString(LuluScreenAwareness.EXCLUDED, "").orEmpty()) }
    var showConsent by remember { mutableStateOf(false) }
    var diagnostic by remember { mutableStateOf(LuluScreenAwareness.diagnostics(context)) }
    Surface(shape = RoundedCornerShape(18.dp), color = CapabilityCard,
        border = BorderStroke(1.dp, CapabilityBorder)) {
        Column(Modifier.fillMaxWidth().padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Text("角色屏幕视觉", fontWeight = FontWeight.Bold, color = CapabilityInk)
            Text("一次截图会发送到你配置的识图模型提供商；原图仅在内存中使用。",
                fontSize = 12.sp, color = CapabilityMuted)
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("应用使用感知", Modifier.weight(1f), color = CapabilityInk)
                Switch(tracking, { tracking = it; prefs.edit().putBoolean(LuluScreenAwareness.TRACK_APPS, it).commit() })
            }
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("按需屏幕识图", Modifier.weight(1f), color = CapabilityInk)
                Switch(onDemand, { onDemand = it; prefs.edit().putBoolean(LuluScreenAwareness.ON_DEMAND, it).commit() })
            }
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("自主观察 · 最短间隔5分钟", Modifier.weight(1f), color = CapabilityInk)
                Switch(autonomy, { autonomy = it; prefs.edit().putBoolean(LuluScreenAwareness.AUTONOMOUS, it).commit() })
            }
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("允许邀请返回露露机", Modifier.weight(1f), color = CapabilityInk)
                Switch(summon, { summon = it; prefs.edit().putBoolean(LuluScreenAwareness.SUMMON, it).commit() })
            }
            OutlinedTextField(value = excluded, onValueChange = {
                excluded = it
                prefs.edit().putString(LuluScreenAwareness.EXCLUDED, it).commit()
            }, label = { Text("禁止查看的 App 包名") },
                supportingText = { Text("多个包名用逗号或换行分隔；支付、密码、系统授权页另有默认保护") },
                modifier = Modifier.fillMaxWidth(), minLines = 2, maxLines = 4)
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(if (consent) "屏幕视觉已授权" else "尚未授权屏幕视觉",
                    color = CapabilityInk, modifier = Modifier.weight(1f), fontSize = 13.sp)
                OutlinedButton(onClick = {
                    if (consent) {
                        prefs.edit().putBoolean(LuluScreenAwareness.CONSENT, false).commit()
                        consent = false
                    } else showConsent = true
                }) { Text(if (consent) "立即撤销" else "阅读并授权") }
            }
            Text("诊断：" + diagnostic, fontSize = 11.sp, color = CapabilityMuted)
            TextButton(onClick = { diagnostic = LuluScreenAwareness.diagnostics(context) }) { Text("刷新诊断") }
        }
    }
    if (showConsent) {
        AlertDialog(onDismissRequest = { showConsent = false }, title = { Text("允许角色看见手机屏幕？") },
            text = { Text("同意后，角色可以根据真实理由申请查看当前一帧，不会持续录像。画面可能包含个人信息，获准后会发送给你选择的识图模型；锁屏、敏感页和排除的 App 无法读取。开启下方「悬浮窗」权限后优先弹出可拒绝的小窗；未开启则使用系统通知。返回邀请也不会强制切换 App，只有你点击才会打开。") },
            confirmButton = { TextButton(onClick = {
                prefs.edit().putBoolean(LuluScreenAwareness.CONSENT, true).commit()
                consent = true
                showConsent = false
            }) { Text("同意开启") } },
            dismissButton = { TextButton(onClick = { showConsent = false }) { Text("取消") } })
    }
}
