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
import com.jiacimu.lulu.system.LuluAccessibilityService
import com.jiacimu.lulu.system.LuluLocationProvider
import com.jiacimu.lulu.system.LuluScreenAwareness
import com.jiacimu.lulu.system.LuluMediaPlayback
import com.jiacimu.lulu.ai.LuluMusicSensoryRuntime
import com.jiacimu.lulu.system.LuluNotificationListenerService
import java.time.Duration
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale
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
        // Lead with observed user contact/time, before lower-priority device
        // telemetry; the character still decides for itself what matters.
        appendLine(UserInteractionPresenceStore.context(characterId, now))
        val isScreenInteractive = (context.getSystemService(Context.POWER_SERVICE) as? android.os.PowerManager)?.isInteractive
        appendLine("用户屏幕交互状态：${when (isScreenInteractive) { true -> "屏幕可交互（亮屏）"; false -> "屏幕未处于交互状态（可能锁屏或熄屏，绝非睡眠证明）"; null -> "未知" }}")
        appendLine("用户手机电量：${runCatching { batteryContext(context, now) }.getOrDefault("暂时不可用")}")
        appendLine("【设备观察边界】前台应用与应用时长仅是来源和时间有限的使用记录，不能直接推断用户此刻在忙、正在学习或需要独处。她没有回复，也不等于她已经读过消息或不需要关心；学习情况以真实学习状态及获准的屏幕观察为准，缺少证据就保持未知。")
        appendLine("用户设备最近前台应用：${if (LuluScreenAwareness.appTracking(context)) foregroundAppContext(context, now) else "已关闭应用感知"}")
        appendLine("用户设备真实使用事件：${runCatching { recentInteractionContext(context, now) }.getOrDefault("暂时无法读取使用事件；不代表用户没有操作手机")}")
        appendLine("用户最近两小时的应用活动：" + runCatching {
            LuluScreenAwareness.recentAppActivity(context, now.toEpochMilli())
        }.getOrDefault("暂时无法读取系统使用记录"))
        appendLine("用户设备位置：${locationContext(context, refreshLocation)}")
        appendLine("用户设备最近通知（总摘录最多500字）：${notificationContext(now)}")
        val musicAllowed = listOfNotNull(
            CapabilityRegistry.find("list_media_sessions"),
            CapabilityRegistry.find("control_music"),
        ).any { CapabilityRegistry.allows(context, characterId, it, userRequested = false) }
        appendLine("用户设备媒体播放：" + if (musicAllowed)
            LuluMediaPlayback.observation(context) else "角色尚未得到主动读取或控制音乐授权；当前状态未知")
        LuluMusicSensoryRuntime.roleContext(context, characterId, now)
            .takeIf(String::isNotBlank)?.let(::appendLine)
        appendLine("用户健康/手环数据：${HealthRolePerception.context(now).ifBlank { "未连接健康 App" }}")
    }.trim() }

    /**
     * The commitment alarm uses a small, evidence-first observation, not the
     * first N characters of the generic long device report. Previously that
     * truncation could remove the actual usage events and health source after
     * the lengthy conversation/presence preamble.
     *
     * Only permitted Android/health signals; no screenshot is taken and no
     * user sleep or intention is inferred by the system.
     */
    suspend fun commitmentReviewContext(
        context: Context, characterId: String, now: Instant = Instant.now(),
    ): String = withContext(Dispatchers.IO) {
        UserInteractionPresenceStore.initialize(context)
        HealthRolePerception.initialize(context)
        HealthRolePerception.recordLatestSleep(characterId)
        val screen = (context.getSystemService(Context.POWER_SERVICE)
            as? android.os.PowerManager)?.isInteractive
        val tracking = LuluScreenAwareness.appTracking(context)
        val interaction = if (tracking)
            runCatching { recentInteractionContext(context, now) }
                .getOrDefault("Android 使用事件暂时不可用，活动未知")
        else "主人未授权应用使用感知，近期交互未知"
        val foreground = if (tracking)
            runCatching { foregroundAppContext(context, now) }
                .getOrDefault("前台应用暂不可读")
        else "未开启应用使用感知"
        val lastTouch = UserInteractionPresenceStore.state.value.lastTouchAt
            ?.takeIf { !it.isAfter(now) && Duration.between(it, now).toMinutes() in 0L..30L }
            ?.let { "露露机确有触摸事件；距今${Duration.between(it, now).toMinutes()}分钟" }
            ?: "近30分钟无可核实的露露机触摸记录（不等于没有操作其他应用）"
        val health = HealthRolePerception.context(now).ifBlank { "未连接健康 App" }
        commitmentReviewEvidenceCue(
            now.atZone(ZoneId.systemDefault()).format(DateTimeFormatter.ISO_OFFSET_DATE_TIME),
            screen, interaction, foreground, lastTouch, health,
        )
    }

    /** Pure and bounded projection: strong time-stamped facts go first. */
    internal fun commitmentReviewEvidenceCue(
        time: String, screenInteractive: Boolean?, usage: String,
        foreground: String, appTouch: String, health: String,
    ): String = buildString {
        appendLine("【约定结果复查｜真实观测；不等同于用户睡眠鉴定】")
        appendLine("观测时刻=$time")
        appendLine("当前屏幕=${when (screenInteractive) {
            true -> "设备报告可交互/亮屏（不能单独证明仍醒着）"
            false -> "设备报告不可交互/可能熄屏（不能证明已经睡着）"
            null -> "无法取得屏幕状态"
        }}")
        appendLine("真实使用事件=${usage.take(500)}")
        appendLine("露露机实际触摸=${appTouch.take(160)}")
        appendLine("最近前台应用=${foreground.take(210)}")
        appendLine("健康/穿戴来源=${health.take(550)}")
        appendLine("判断边界：近期真实交互或触摸是该时段手机被操作的正向线索，" +
            "与‘没有任何使用活动’相矛盾；前台切换只证明切换。屏幕亮、手机在用" +
            "仍无法单独判定是谁操作、此刻是否还醒着；无事件或息屏也不能当作入睡成功。")
        appendLine("若已有仍待达成的时间性承诺，角色须结合这些实际时间线索、原目标、" +
            "上次行动及真实权限选择继续做、取得新证据、明确暂缓或复查；" +
            "不能只凭猜测‘正在入睡所以不要打扰’就宣告目标改善。")
    }.trim()

    /** Minimal, local device samples; not a user sleep state or a character memory. */
    private fun batteryContext(context: Context, now: Instant): String {
        val intent = context.registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED))
        val level = intent?.getIntExtra(BatteryManager.EXTRA_LEVEL, -1) ?: -1
        val scale = intent?.getIntExtra(BatteryManager.EXTRA_SCALE, 100) ?: 100
        if (level < 0 || scale <= 0) return "暂时不可用"
        val percent = level * 100 / scale
        val status = intent?.getIntExtra(BatteryManager.EXTRA_STATUS, -1) ?: -1
        val charging = status == BatteryManager.BATTERY_STATUS_CHARGING ||
            status == BatteryManager.BATTERY_STATUS_FULL
        val sample = context.getSharedPreferences("lulu_device_battery_sample_v1", Context.MODE_PRIVATE)
        val lastAt = sample.getLong("observedAt", 0L)
        val lastLevel = sample.getInt("level", -1)
        val previousCharging = sample.getBoolean("charging", false)
        val observation = batteryDeltaCue(
            percent, charging, now.toEpochMilli(), lastLevel, previousCharging, lastAt)
        if (now.toEpochMilli() > lastAt) sample.edit()
            .putLong("observedAt", now.toEpochMilli())
            .putInt("level", percent).putBoolean("charging", charging).apply()
        return "$percent%${if (charging) "，正在充电" else "，未充电"}$observation"
    }

    /** A falling battery cannot be promoted into evidence that anyone is awake. */
    internal fun batteryDeltaCue(
        level: Int, charging: Boolean, nowMillis: Long,
        previousLevel: Int, previousCharging: Boolean, previousAt: Long,
    ): String {
        val elapsed = nowMillis - previousAt
        // A zero/negative timestamp is the sentinel for "no previous sample", not
        // a real battery reading at the Unix epoch. Never fabricate an elapsed interval.
        if (previousAt <= 0L || nowMillis <= previousAt ||
            previousLevel !in 0..100 || level !in 0..100 ||
            elapsed !in 60_000L..(6L * 60 * 60_000L)) return ""
        val minutes = elapsed / 60_000L
        val delta = level - previousLevel
        val changed = when {
            delta > 0 -> "增加了$delta 个百分点"
            delta < 0 -> "减少了${-delta} 个百分点"
            else -> "没有可分辨的百分比变化"
        }
        val chargingChanged = if (charging != previousCharging) "；期间充电状态可能改变" else ""
        return "；距上次设备采样约$minutes 分钟，电量$changed$chargingChanged（只能说明设备耗电/充电，不能推断主人是否睡着）"
    }

    /** Android's actual usage-event evidence. No events means UNKNOWN, never asleep. */
    private fun recentInteractionContext(context: Context, now: Instant): String {
        if (!LuluScreenAwareness.appTracking(context)) return "主人已关闭应用使用感知，活动未知"
        val manager = context.getSystemService(UsageStatsManager::class.java)
            ?: return "系统使用事件不可用，活动未知"
        val end = now.toEpochMilli()
        val events = manager.queryEvents(end - 30L * 60_000L, end)
        val event = UsageEvents.Event()
        var interactionAt = 0L
        var foregroundChangeAt = 0L
        var foregroundPackage = ""
        while (events.hasNextEvent()) {
            events.getNextEvent(event)
            if (event.timeStamp > end || event.timeStamp <= end - 30L * 60_000L) continue
            when (event.eventType) {
                UsageEvents.Event.USER_INTERACTION ->
                    interactionAt = maxOf(interactionAt, event.timeStamp)
                UsageEvents.Event.ACTIVITY_RESUMED -> if (event.timeStamp >= foregroundChangeAt) {
                    foregroundChangeAt = event.timeStamp
                    foregroundPackage = event.packageName.orEmpty()
                }
            }
        }
        return activityEvidenceCue(end, interactionAt, foregroundChangeAt,
            foregroundPackage.isNotBlank())
    }

    /** Portable rendering also tested without any Android usage service. */
    internal fun activityEvidenceCue(
        nowMillis: Long, lastInteractionAt: Long,
        lastForegroundChangeAt: Long, foregroundAllowed: Boolean,
    ): String {
        fun age(at: Long): Long? =
            (nowMillis - at).takeIf { at > 0L && it in 0L..(30L * 60_000L) }?.div(60_000L)
        val interactionAge = age(lastInteractionAt)
        val switchAge = if (foregroundAllowed) age(lastForegroundChangeAt) else null
        return buildString {
            if (interactionAge != null) append("Android 记录约$interactionAge 分钟前有应用交互事件")
            if (switchAge != null) {
                if (isNotEmpty()) append("；")
                append("约$switchAge 分钟前发生应用前台切换")
            }
            if (isEmpty()) append("近30分钟没有可用的应用交互/切换事件记录（可能无权限、系统未上报或应用持续打开）")
            if (interactionAge != null)
                append("；这意味着上述时刻确有应用交互活动，不能把那一时刻描写成完全没有手机活动或已确认进入睡眠")
            append("；这些是设备事件，不等于持续使用、屏幕内容或用户睡眠状态；没有事件不代表主人睡了")
        }
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
        val observed = if (appLabel.isNullOrBlank() || appLabel == packageName) packageName
            else "$appLabel（$packageName）"
        val source = if (accessibility.connected && freshAccessibility && accessibility.packageName == packageName)
            "无障碍近期观察" else "最近15分钟的使用记录"
        return "$observed（来源：$source；不保证仍是此刻前台应用）"
    }

    private suspend fun locationContext(context: Context, refresh: Boolean): String {
        if (
            ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_FINE_LOCATION) !=
            PackageManager.PERMISSION_GRANTED
        ) return "未授权"
        val location = runCatching { if (refresh) LuluLocationProvider.freshLocation(context) else LuluLocationProvider.bestSystemLocation(context) }.getOrNull()
            ?: return "暂时没有新位置"
        val ageMinutes = (System.currentTimeMillis() - location.time).coerceAtLeast(0L) / 60_000L
        // Cached coordinates are an observation, not a reason to wait for a
        // network geocoder before every conversational model call.
        val locale = Locale.getDefault()
        val readable = LocationAddressCache.shared.peekOrRefresh(
            LocationAddressCache.Key(location.latitude, location.longitude, locale.toLanguageTag()),
        ) {
            if (!Geocoder.isPresent()) return@peekOrRefresh ""
            Geocoder(context.applicationContext, locale)
                .getFromLocation(location.latitude, location.longitude, 1)
                ?.firstOrNull()
                ?.let { address ->
                    listOfNotNull(address.subLocality, address.locality, address.adminArea, address.countryName)
                        .map(String::trim).filter(String::isNotBlank).distinct().joinToString("，")
                }.orEmpty()
        }
        return "${readable.ifBlank { "仅获得坐标，未获得可靠行政区地址" }}；精度约${location.accuracy.toInt()}米；数据约${ageMinutes}分钟前"
    }

    private fun notificationContext(now: Instant): String {
        if (!LuluNotificationListenerService.isConnected.value) return "未授权"
        return LuluNotificationListenerService.notifications.value.asSequence()
            .filter { Duration.between(it.postedAt, now).abs().toMinutes() <= 180 }
            .filter { it.packageName != "app.lulu" }
            .take(8)
            .joinToString("；") {
                val age = Duration.between(it.postedAt, now).toMinutes()
                "${it.packageName}｜${it.title.take(60)}｜${it.text.take(120)}（约${age.coerceAtLeast(0)}分钟前的通知，不代表当前）"
            }
            .replace(Regex("\\s+"), " ")
            .take(500)
            .ifBlank { "近3小时没有可读通知" }
    }



}
