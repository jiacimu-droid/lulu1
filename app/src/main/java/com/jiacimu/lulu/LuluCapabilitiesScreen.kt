package com.jiacimu.lulu

import android.Manifest
import android.app.AlarmManager
import android.app.AppOpsManager
import android.app.usage.UsageEvents
import android.app.usage.UsageStatsManager
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.location.LocationManager
import android.net.Uri
import android.os.BatteryManager
import android.os.Build
import android.os.PowerManager
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
import com.jiacimu.lulu.system.LuluAccessibilityService
import com.jiacimu.lulu.system.LuluNotificationListenerService
import java.time.Instant

private val CapabilityPaper = Color(0xFFF8FAF8)
private val CapabilityCard = Color(0xFFFCFDFC)
private val CapabilityBorder = Color(0xFFDDE7E3)
private val CapabilityInk = Color(0xFF34413F)
private val CapabilityMuted = Color(0xFF7D8C88)
private val CapabilityOn = Color(0xFFDCEAE6)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun LuluCapabilitiesScreen(onBack: () -> Unit) {
    val context = LocalContext.current
    var refreshKey by remember { mutableIntStateOf(0) }
    val accessibility by LuluAccessibilityService.state.collectAsState()
    val notificationConnected by LuluNotificationListenerService.isConnected.collectAsState()

    val runtimeLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions(),
    ) { refreshKey++ }
    val backgroundLocationLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission(),
    ) { refreshKey++ }

    fun open(intent: Intent) {
        runCatching { context.startActivity(intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }
        refreshKey++
    }

    val snapshot = remember(refreshKey, accessibility.connected, notificationConnected) {
        CapabilitySnapshot.read(context, accessibility.connected, notificationConnected)
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
            modifier = Modifier.fillMaxSize().padding(padding),
            contentPadding = PaddingValues(16.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            item {
                CapabilitySummaryCard(snapshot)
            }
            item {
                CapabilityRow(
                    Icons.Outlined.LocationOn,
                    "精确位置",
                    snapshot.locationLabel,
                    snapshot.preciseLocation,
                ) {
                    runtimeLauncher.launch(arrayOf(Manifest.permission.ACCESS_COARSE_LOCATION, Manifest.permission.ACCESS_FINE_LOCATION))
                }
            }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                item {
                    CapabilityRow(
                        Icons.Outlined.MyLocation,
                        "后台位置",
                        "允许露露在后台判断你是否到达常用地点",
                        snapshot.backgroundLocation,
                    ) { backgroundLocationLauncher.launch(Manifest.permission.ACCESS_BACKGROUND_LOCATION) }
                }
            }
            item {
                CapabilityRow(Icons.Outlined.Alarm, "精确闹钟", "用于准时叫醒、提醒和监督", snapshot.exactAlarm) {
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                        open(Intent(Settings.ACTION_REQUEST_SCHEDULE_EXACT_ALARM, Uri.parse("package:${context.packageName}")))
                    }
                }
            }
            item {
                CapabilityRow(Icons.Outlined.Apps, "应用使用情况", snapshot.foregroundAppLabel, snapshot.usageAccess) {
                    open(Intent(Settings.ACTION_USAGE_ACCESS_SETTINGS))
                }
            }
            item {
                CapabilityRow(Icons.Outlined.NotificationsActive, "通知读取", "感知其他 App 的新通知", snapshot.notificationAccess) {
                    open(Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS))
                }
            }
            item {
                CapabilityRow(Icons.Outlined.TouchApp, "屏幕感知与控制", snapshot.accessibilityLabel, snapshot.accessibility) {
                    open(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
                }
            }
            item {
                CapabilityRow(Icons.Outlined.PictureInPictureAlt, "悬浮窗", "允许露露在其他 App 上方陪伴和提示", snapshot.overlay) {
                    open(Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:${context.packageName}")))
                }
            }
            item {
                CapabilityRow(Icons.Outlined.CalendarMonth, "日历", "读取和创建日程、考试与学习计划", snapshot.calendar) {
                    runtimeLauncher.launch(arrayOf(Manifest.permission.READ_CALENDAR, Manifest.permission.WRITE_CALENDAR))
                }
            }
            item {
                CapabilityRow(Icons.Outlined.DirectionsWalk, "活动识别", "识别静止、步行、跑步、骑行等状态", snapshot.activityRecognition) {
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                        runtimeLauncher.launch(arrayOf(Manifest.permission.ACTIVITY_RECOGNITION))
                    }
                }
            }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                item {
                    CapabilityRow(Icons.Outlined.Bluetooth, "附近设备", "感知耳机、手表和已连接蓝牙设备", snapshot.nearbyDevices) {
                        runtimeLauncher.launch(arrayOf(Manifest.permission.BLUETOOTH_CONNECT, Manifest.permission.BLUETOOTH_SCAN))
                    }
                }
            }
            item {
                CapabilityRow(Icons.Outlined.Notifications, "发送通知", "主动消息、来电和闹钟提醒", snapshot.notifications) {
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                        runtimeLauncher.launch(arrayOf(Manifest.permission.POST_NOTIFICATIONS))
                    }
                }
            }
            item {
                CapabilityRow(Icons.Outlined.BatteryChargingFull, "后台运行", snapshot.batteryOptimizationLabel, snapshot.ignoreBatteryOptimizations) {
                    open(Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS, Uri.parse("package:${context.packageName}")))
                }
            }
            item {
                Text(
                    "打开无障碍权限后，露露已具备读取当前前台应用、提取可见文字以及执行返回、主页、最近任务、通知栏和界面点击的本机能力。后续角色工具层会直接调用这些真实桥接，不再假装执行。",
                    color = CapabilityMuted,
                    fontSize = 12.sp,
                    lineHeight = 18.sp,
                    modifier = Modifier.padding(6.dp),
                )
            }
        }
    }
}

@Composable
private fun CapabilitySummaryCard(snapshot: CapabilitySnapshot) {
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
            Surface(shape = RoundedCornerShape(12.dp), color = if (enabled) CapabilityOn else Color(0xFFF0F2F1)) {
                Icon(icon, null, tint = CapabilityInk, modifier = Modifier.padding(9.dp).size(22.dp))
            }
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                Text(title, color = CapabilityInk, fontWeight = FontWeight.Bold)
                Text(subtitle, color = CapabilityMuted, fontSize = 12.sp, lineHeight = 16.sp)
            }
            Text(if (enabled) "已开启" else "去开启", color = if (enabled) Color(0xFF55766E) else CapabilityMuted, fontSize = 12.sp)
            Spacer(Modifier.width(4.dp))
            Icon(Icons.Outlined.ChevronRight, null, tint = CapabilityMuted)
        }
    }
}

private data class CapabilitySnapshot(
    val preciseLocation: Boolean,
    val backgroundLocation: Boolean,
    val locationLabel: String,
    val exactAlarm: Boolean,
    val usageAccess: Boolean,
    val foregroundAppLabel: String,
    val notificationAccess: Boolean,
    val accessibility: Boolean,
    val accessibilityLabel: String,
    val overlay: Boolean,
    val calendar: Boolean,
    val activityRecognition: Boolean,
    val nearbyDevices: Boolean,
    val notifications: Boolean,
    val ignoreBatteryOptimizations: Boolean,
    val batteryOptimizationLabel: String,
    val batteryPercent: Int,
    val charging: Boolean,
) {
    companion object {
        fun read(context: Context, accessibilityConnected: Boolean, notificationConnected: Boolean): CapabilitySnapshot {
            val fine = context.granted(Manifest.permission.ACCESS_FINE_LOCATION)
            val background = Build.VERSION.SDK_INT < Build.VERSION_CODES.Q || context.granted(Manifest.permission.ACCESS_BACKGROUND_LOCATION)
            val alarm = context.getSystemService(AlarmManager::class.java)
            val exactAlarm = Build.VERSION.SDK_INT < Build.VERSION_CODES.S || alarm.canScheduleExactAlarms()
            val usage = context.hasUsageAccess()
            val foreground = if (usage) context.recentForegroundPackage() else null
            val notification = notificationConnected || context.hasNotificationListenerAccess()
            val accessibility = accessibilityConnected || context.hasAccessibilityAccess()
            val overlay = Settings.canDrawOverlays(context)
            val calendar = context.granted(Manifest.permission.READ_CALENDAR) && context.granted(Manifest.permission.WRITE_CALENDAR)
            val activity = Build.VERSION.SDK_INT < Build.VERSION_CODES.Q || context.granted(Manifest.permission.ACTIVITY_RECOGNITION)
            val nearby = Build.VERSION.SDK_INT < Build.VERSION_CODES.S || (
                context.granted(Manifest.permission.BLUETOOTH_CONNECT) && context.granted(Manifest.permission.BLUETOOTH_SCAN)
            )
            val notifications = Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU || context.granted(Manifest.permission.POST_NOTIFICATIONS)
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
                backgroundLocation = background,
                locationLabel = if (fine) context.lastLocationLabel() else "允许后可读取具体经纬度和定位精度",
                exactAlarm = exactAlarm,
                usageAccess = usage,
                foregroundAppLabel = foreground?.let { "最近前台应用：$it" } ?: "允许后可判断当前和最近使用的 App",
                notificationAccess = notification,
                accessibility = accessibility,
                accessibilityLabel = if (accessibility) "已连接，可读取屏幕并执行手机控制" else "读取当前界面并执行点击、返回、主页等操作",
                overlay = overlay,
                calendar = calendar,
                activityRecognition = activity,
                nearbyDevices = nearby,
                notifications = notifications,
                ignoreBatteryOptimizations = ignored,
                batteryOptimizationLabel = if (ignored) "已允许后台稳定运行" else "避免主动联系和闹钟被系统省电中断",
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
    val manager = getSystemService(LocationManager::class.java)
    val location = manager.getProviders(true)
        .mapNotNull { provider -> runCatching { manager.getLastKnownLocation(provider) }.getOrNull() }
        .maxByOrNull { it.time }
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
