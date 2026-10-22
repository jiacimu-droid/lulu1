package com.jiacimu.lulu.system

import android.Manifest
import android.app.KeyguardManager
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.usage.UsageEvents
import android.app.usage.UsageStatsManager
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.content.ContextCompat
import com.jiacimu.lulu.ai.VisionModelService
import java.time.Instant
import java.util.concurrent.atomic.AtomicBoolean
import org.json.JSONObject

/** Independent Android implementation. No Veglia server, polling loop, or screenshot files. */
object LuluScreenAwareness {
    private const val PREFS = "lulu_screen_awareness_v1"
    const val CONSENT = "owner_consented"
    const val TRACK_APPS = "track_apps"
    const val ON_DEMAND = "on_demand"
    const val AUTONOMOUS = "autonomous"
    const val SUMMON = "summon"
    const val EXCLUDED = "excluded_packages"
    private const val LAST_AUTO = "last_autonomous_attempt"
    private const val LAST_DIAG = "last_diagnostic"
    const val MIN_AUTONOMOUS_GAP_MS = 300_000L
    private val busy = AtomicBoolean(false)
    private val blockedPrefixes = listOf(
        "com.android.systemui", "com.android.settings", "com.android.permissioncontroller",
        "com.google.android.permissioncontroller", "com.google.android.apps.authenticator",
        "com.google.android.gms.auth", "com.google.android.apps.wallet",
        "com.android.vending.billing", "com.miui.securitycenter",
    )

    fun preferences(context: Context) = context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
    fun consented(context: Context) = preferences(context).getBoolean(CONSENT, false)
    fun appTracking(context: Context) = preferences(context).getBoolean(TRACK_APPS, true)
    fun onDemandAllowed(context: Context): Boolean {
        val p = preferences(context)
        return p.getBoolean(CONSENT, false) && p.getBoolean(ON_DEMAND, true)
    }
    fun autonomousAllowed(context: Context) = onDemandAllowed(context) &&
        preferences(context).getBoolean(AUTONOMOUS, true)
    fun summonAllowed(context: Context) = consented(context) &&
        preferences(context).getBoolean(SUMMON, true)

    fun excluded(context: Context): Set<String> = preferences(context).getString(EXCLUDED, "")
        .orEmpty().split(Regex("[,;\\s]+")).map(String::trim)
        .filter { it.matches(Regex("[a-zA-Z0-9_]+(\\.[a-zA-Z0-9_]+)+")) }.toSet()

    fun isExcluded(context: Context, packageName: String): Boolean =
        packageName.isBlank() ||
        blockedPrefixes.any { packageName == it || packageName.startsWith(it + ".") } ||
        packageName.contains("bank", ignoreCase = true) ||
        packageName.contains("password", ignoreCase = true) ||
        packageName.contains("authenticator", ignoreCase = true) ||
        packageName in excluded(context)

    fun cooldownRemaining(context: Context, nowMillis: Long = System.currentTimeMillis()): Long {
        val last = preferences(context).getLong(LAST_AUTO, 0L)
        return if (last <= 0L || nowMillis < last) 0L
        else (last + MIN_AUTONOMOUS_GAP_MS - nowMillis).coerceAtLeast(0L)
    }
    fun diagnostics(context: Context) =
        preferences(context).getString(LAST_DIAG, "尚未尝试获取屏幕图像").orEmpty()
    private fun report(context: Context, state: String) {
        preferences(context).edit().putString(LAST_DIAG,
            Instant.now().toString() + " · " + state.take(130)).apply()
    }
    private fun rejected(context: Context, reason: String, status: String = "waiting_user"): String {
        report(context, reason)
        return JSONObject().put("success", false).put("status", status).put("error", reason).toString()
    }

    /** One requested frame: never silently start a video stream or store raw pixels. */
    suspend fun capture(context: Context, userRequested: Boolean): String {
        if (!onDemandAllowed(context)) return rejected(context, "请先在权限与能力页同意屏幕视觉")
        if (!userRequested && !autonomousAllowed(context))
            return rejected(context, "角色自主屏幕观察尚未获授权")
        if (Build.VERSION.SDK_INT < 30) return rejected(context, "Android 11 以下不支持", "failed")
        if (context.getSystemService(KeyguardManager::class.java)?.isDeviceLocked == true)
            return rejected(context, "锁屏时禁止截图")
        val first = LuluAccessibilityService.observe()
        if (!first.connected) return rejected(context, "无障碍服务未连接")
        if (isExcluded(context, first.packageName)) return rejected(context, "当前 App 被隐私规则排除")
        if (!userRequested && cooldownRemaining(context) > 0L)
            return rejected(context, "距上次自主截图不足五分钟", "cooldown")
        if (!busy.compareAndSet(false, true)) return rejected(context, "当前已有识图请求", "busy")
        try {
            if (!userRequested && cooldownRemaining(context) > 0L)
                return rejected(context, "距上次自主截图不足五分钟", "cooldown")
            val bitmap = try { LuluAccessibilityService.captureBitmap() }
                catch (error: Exception) {
                    return rejected(context, "Android 截图失败：" +
                        error.message.orEmpty().take(90), "failed")
                }
            try {
                val fresh = LuluAccessibilityService.observe()
                if (!onDemandAllowed(context) || (!userRequested && !autonomousAllowed(context)) ||
                    first.packageName != fresh.packageName || isExcluded(context, fresh.packageName) ||
                    context.getSystemService(KeyguardManager::class.java)?.isDeviceLocked == true)
                    return rejected(context, "页面或授权在捕获期间发生变化，画面已丢弃")
                if (!userRequested && !preferences(context).edit()
                        .putLong(LAST_AUTO, System.currentTimeMillis()).commit())
                    return rejected(context, "无法保存观察间隔，已停止")
                report(context, "已取得一帧截图，识图进行中")
                val description = VisionModelService.describeScreenFrame(context, bitmap).getOrElse {
                    return rejected(context, "识图失败：" + it.message.orEmpty().take(90), "failed")
                }
                if (!onDemandAllowed(context)) return rejected(context, "授权已撤销，丢弃本次识图结果")
                report(context, "识图完成")
                return JSONObject().put("success", true).put("status", "observed")
                    .put("packageName", fresh.packageName)
                    .put("capturedAt", Instant.now().toString())
                    .put("summary", "真实看到的屏幕内容：" + description.take(280))
                    .put("visibleDescription", description.take(1300))
                    .put("verification", "真实截图＋视觉模型描述，不代表持续监看").toString()
            } finally { bitmap.recycle() }
        } finally { busy.set(false) }
    }

    /** A notification the phone owner may tap, never a background Activity hijack. */
    fun summon(context: Context): String {
        if (!summonAllowed(context)) return rejected(context, "未授权邀请返回露露机")
        if (Build.VERSION.SDK_INT >= 33 &&
            ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) !=
            PackageManager.PERMISSION_GRANTED) return rejected(context, "缺少通知权限")
        val launch = context.packageManager.getLaunchIntentForPackage(context.packageName)
            ?: return rejected(context, "未找到露露机启动入口", "failed")
        launch.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP)
        val pending = PendingIntent.getActivity(context, 41031, launch,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        val manager = context.getSystemService(NotificationManager::class.java)
        val channel = "lulu_summon_v1"
        manager.createNotificationChannel(NotificationChannel(channel, "角色邀请返回",
            NotificationManager.IMPORTANCE_HIGH))
        manager.notify(41031, Notification.Builder(context, channel)
            .setSmallIcon(android.R.drawable.ic_dialog_info)
            .setContentTitle("露露机想见你").setContentText("点击返回角色身边")
            .setContentIntent(pending).setAutoCancel(true).build())
        report(context, "角色邀请通知已投递")
        return JSONObject().put("success", true).put("status", "notification_posted")
            .put("summary", "已发送返回邀请；用户是否点击尚未确认").toString()
    }

    /** Local UsageEvents estimate; no database and no screenshot transmission. */
    fun recentAppActivity(context: Context, nowMillis: Long = System.currentTimeMillis()): String {
        if (!appTracking(context)) return "用户已关闭应用使用感知"
        val service = context.getSystemService(UsageStatsManager::class.java)
            ?: return "系统无法读取使用记录"
        val start = nowMillis - 2L * 60L * 60_000L
        val events = service.queryEvents(start, nowMillis)
        val event = UsageEvents.Event()
        val totals = mutableMapOf<String, Long>()
        var active = ""
        var began = start
        fun addUntil(until: Long) {
            if (active.isNotBlank() && !isExcluded(context, active))
                totals[active] = (totals[active] ?: 0L) + (until - began).coerceAtLeast(0L)
        }
        while (events.hasNextEvent()) {
            events.getNextEvent(event)
            val pkg = event.packageName.orEmpty()
            if (event.eventType == UsageEvents.Event.ACTIVITY_RESUMED) {
                addUntil(event.timeStamp)
                active = pkg
                began = event.timeStamp
            } else if (event.eventType == UsageEvents.Event.ACTIVITY_PAUSED && active == pkg) {
                addUntil(event.timeStamp)
                active = ""
            }
        }
        addUntil(nowMillis)
        val top = totals.entries.filter { it.value >= 60_000L }.sortedByDescending { it.value }.take(4)
        if (top.isEmpty()) return "最近两小时没有可统计的应用活动（可能未授权）"
        return top.joinToString("；") { entry ->
            val label = runCatching {
                context.packageManager.getApplicationLabel(
                    context.packageManager.getApplicationInfo(entry.key, 0)).toString()
            }.getOrDefault(entry.key)
            label.take(30) + "约" + entry.value / 60_000L + "分钟"
        } + "（系统事件估算，不等于持续注视）"
    }
}
