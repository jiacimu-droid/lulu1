package com.jiacimu.lulu.data

import android.Manifest
import android.app.usage.UsageEvents
import android.app.usage.UsageStatsManager
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.location.Geocoder
import android.os.BatteryManager
import androidx.core.content.ContextCompat
import com.jiacimu.lulu.health.HealthRolePerception
import com.jiacimu.lulu.study.PostgraduateExamStores
import com.jiacimu.lulu.study.roleStudyContext
import com.jiacimu.lulu.system.LuluAccessibilityService
import com.jiacimu.lulu.system.LuluLocationProvider
import com.jiacimu.lulu.system.LuluNotificationListenerService
import java.time.Duration
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale
import kotlin.math.max
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** Shared observed user state. Missing permissions remain unknown; chat uses cached location. */
internal object UserDevicePerception {
    suspend fun context(
        context: Context,
        characterId: String,
        now: Instant = Instant.now(),
        refreshLocation: Boolean = false,
    ): String = withContext(Dispatchers.IO) { buildString {
        UserInteractionPresenceStore.initialize(context)
        HealthRolePerception.initialize(context)
        HealthRolePerception.recordLatestSleep(characterId)
        appendLine("用户现实时间：${now.atZone(ZoneId.systemDefault()).format(DateTimeFormatter.ISO_OFFSET_DATE_TIME)}")
        val isScreenInteractive = (context.getSystemService(Context.POWER_SERVICE) as? android.os.PowerManager)?.isInteractive
        appendLine("用户屏幕交互状态：${when (isScreenInteractive) { true -> "屏幕可交互（亮屏）"; false -> "屏幕未处于交互状态（可能锁屏或熄屏，绝非睡眠证明）"; null -> "未知" }}")
        appendLine("用户手机电量：${runCatching { batteryContext(context) }.getOrDefault("暂时不可用")}")
        appendLine("用户设备最近前台应用：${foregroundAppContext(context, now)}")
        appendLine("用户设备位置：${locationContext(context, refreshLocation)}")
        appendLine("用户设备最近通知（总摘录最多500字）：${notificationContext(now)}")
        appendLine("用户健康/手环数据：${HealthRolePerception.context(now).ifBlank { "未连接健康 App" }}")
        appendLine("用户学习状态：${studyContext(characterId)}")
        appendLine(UserInteractionPresenceStore.context(characterId, now))
    }.trim() }

    private fun batteryContext(context: Context): String {
        val intent = context.registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED))
        val level = intent?.getIntExtra(BatteryManager.EXTRA_LEVEL, -1) ?: -1
        val scale = intent?.getIntExtra(BatteryManager.EXTRA_SCALE, 100) ?: 100
        if (level < 0 || scale <= 0) return "暂时不可用"
        val percent = level * 100 / scale
        val status = intent?.getIntExtra(BatteryManager.EXTRA_STATUS, -1) ?: -1
        val charging = status == BatteryManager.BATTERY_STATUS_CHARGING ||
            status == BatteryManager.BATTERY_STATUS_FULL
        return "$percent%${if (charging) "，正在充电" else ""}"
    }

    private fun foregroundAppContext(context: Context, now: Instant): String {
        val accessibility = LuluAccessibilityService.state.value
        val freshAccessibility = accessibility.capturedAt?.let {
            Duration.between(it, now).abs().toMinutes() <= 15
        } == true
        val packageName = if (
            accessibility.connected && freshAccessibility && accessibility.packageName.isNotBlank()
        ) {
            accessibility.packageName
        } else runCatching {
            val usage = context.getSystemService(UsageStatsManager::class.java)
            val end = System.currentTimeMillis()
            val events = usage.queryEvents(end - 15 * 60_000L, end)
            val event = UsageEvents.Event()
            var latestPackage = ""
            var latestTime = 0L
            while (events.hasNextEvent()) {
                events.getNextEvent(event)
                if (event.eventType == UsageEvents.Event.ACTIVITY_RESUMED && event.timeStamp >= latestTime) {
                    latestPackage = event.packageName.orEmpty()
                    latestTime = event.timeStamp
                }
            }
            latestPackage
        }.getOrDefault("")
        if (packageName.isBlank()) return "未授权或近期没有记录"
        val appLabel = runCatching {
            val info = context.packageManager.getApplicationInfo(packageName, 0)
            context.packageManager.getApplicationLabel(info).toString()
        }.getOrNull()
        return if (appLabel.isNullOrBlank() || appLabel == packageName) packageName
        else "$appLabel（$packageName）"
    }

    private suspend fun locationContext(context: Context, refresh: Boolean): String {
        if (
            ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_FINE_LOCATION) !=
            PackageManager.PERMISSION_GRANTED
        ) return "未授权"
        val location = runCatching { if (refresh) LuluLocationProvider.freshLocation(context) else LuluLocationProvider.bestSystemLocation(context) }.getOrNull()
            ?: return "暂时没有新位置"
        val ageMinutes = (System.currentTimeMillis() - location.time).coerceAtLeast(0L) / 60_000L
        val readable = runCatching {
            if (!Geocoder.isPresent()) return@runCatching ""
            Geocoder(context, Locale.getDefault())
                .getFromLocation(location.latitude, location.longitude, 1)
                ?.firstOrNull()
                ?.let { address ->
                    listOfNotNull(address.subLocality, address.locality, address.adminArea, address.countryName)
                        .map(String::trim).filter(String::isNotBlank).distinct().joinToString("，")
                }.orEmpty()
        }.getOrDefault("")
        return "${readable.ifBlank { "仅获得坐标，未获得可靠行政区地址" }}；精度约${location.accuracy.toInt()}米；数据约${ageMinutes}分钟前"
    }

    private fun notificationContext(now: Instant): String {
        if (!LuluNotificationListenerService.isConnected.value) return "未授权"
        return LuluNotificationListenerService.notifications.value.asSequence()
            .filter { Duration.between(it.postedAt, now).abs().toMinutes() <= 180 }
            .filter { it.packageName != "app.lulu" }
            .take(8)
            .joinToString("；") { "${it.packageName}｜${it.title.take(60)}｜${it.text.take(120)}" }
            .replace(Regex("\\s+"), " ")
            .take(500)
            .ifBlank { "近3小时没有可读通知" }
    }

    private fun studyContext(characterId: String): String {
        val state = PostgraduateExamStores.main.state.value
        if (state.profile.selectedCharacterId != characterId) {
            return "当前角色不是学习 App 的陪同角色，无权读取学习状态"
        }
        val pomodoro = state.pomodoro
        val current = if (pomodoro.running) {
            "番茄钟进行中，剩余约${max(0, pomodoro.remainingSeconds) / 60}分钟"
        } else "当前没有进行中的番茄钟"
        return "$current；${state.roleStudyContext().replace("\n", "；")}"
    }

}
