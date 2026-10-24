package com.jiacimu.lulu.system

import android.Manifest
import android.app.KeyguardManager
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.usage.UsageEvents
import android.content.BroadcastReceiver
import androidx.core.app.NotificationManagerCompat
import androidx.work.CoroutineWorker
import androidx.work.Data
import androidx.work.ExistingWorkPolicy
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import com.jiacimu.lulu.data.MigratedDomainStores
import com.jiacimu.lulu.data.SharedExperienceTimeline
import com.jiacimu.lulu.data.EventEvidenceKind
import com.jiacimu.lulu.data.ProactivePerceptionRuntime
import com.jiacimu.lulu.data.initializeBackgroundRuntime
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
    private const val PENDING_CAPTURE = "pending_capture_permission"
    private const val CAPTURE_CHANNEL = "lulu_screen_requests_v1"
    private const val CAPTURE_NOTIFICATION_ID = 41032
    private const val CAPTURE_EXPIRES_MS = 120_000L
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

    private fun notificationsAvailable(context: Context): Boolean {
        if (Build.VERSION.SDK_INT >= 33 &&
            ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) !=
            PackageManager.PERMISSION_GRANTED) return false
        if (!NotificationManagerCompat.from(context).areNotificationsEnabled()) return false
        val manager = context.getSystemService(NotificationManager::class.java)
        manager.createNotificationChannel(NotificationChannel(CAPTURE_CHANNEL,
            "屏幕查看请求", NotificationManager.IMPORTANCE_HIGH))
        return manager.getNotificationChannel(CAPTURE_CHANNEL)?.importance != NotificationManager.IMPORTANCE_NONE
    }

    private fun requestCapture(context: Context, characterId: String, requestId: String): String {
        val canNotify = notificationsAvailable(context)
        if (!canNotify && !LuluScreenRequestPopup.available(context)) return rejected(context,
            "未开启悬浮窗和通知权限，无法让主人看见屏幕查看请求")
        val prefs = preferences(context)
        val existing = runCatching { JSONObject(prefs.getString(PENDING_CAPTURE, "{}")) }.getOrNull()
        val now = System.currentTimeMillis()
        if (existing != null && existing.optString("id").isNotBlank() &&
            now - existing.optLong("at") in 0L..CAPTURE_EXPIRES_MS) {
            return rejected(context, "已有一条屏幕查看请求等待主人选择", "waiting_user")
        }
        val id = requestId.ifBlank { java.util.UUID.randomUUID().toString() }
        val payload = JSONObject().put("id", id).put("characterId", characterId)
            .put("at", now)
        if (!prefs.edit().putString(PENDING_CAPTURE, payload.toString()).commit())
            return rejected(context, "无法保存屏幕查看请求", "failed")
        val roleName = runCatching {
            MigratedDomainStores.characters.get(characterId).displayName
        }.getOrNull()?.takeIf(String::isNotBlank) ?: "角色"
        fun decisionIntent(approved: Boolean): PendingIntent {
            val intent = Intent(context, LuluScreenRequestReceiver::class.java)
                .setAction(if (approved) "lulu.screen.ALLOW" else "lulu.screen.DENY")
                .putExtra("requestId", id).putExtra("characterId", characterId)
            return PendingIntent.getBroadcast(context, id.hashCode() + if (approved) 1 else 2,
                intent, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        }
        if (canNotify) {
        val notice = Notification.Builder(context, CAPTURE_CHANNEL)
            .setSmallIcon(android.R.drawable.ic_menu_view)
            .setContentTitle("$roleName 想看屏幕")
            .setContentText("允许一次 · 拒绝")
            .addAction(Notification.Action.Builder(android.R.drawable.ic_menu_view,
                "允许一次", decisionIntent(true)).build())
            .addAction(Notification.Action.Builder(android.R.drawable.ic_menu_close_clear_cancel,
                "拒绝", decisionIntent(false)).build())
            .setCategory(Notification.CATEGORY_REMINDER)
            .setPriority(Notification.PRIORITY_HIGH)
            .setAutoCancel(false)
            .build()
        context.getSystemService(NotificationManager::class.java)
            .notify(CAPTURE_NOTIFICATION_ID, notice)
        }
        val popup = LuluScreenRequestPopup.showCapture(
            context, roleName, id,
            stillPending = {
                val pending = runCatching {
                    JSONObject(prefs.getString(PENDING_CAPTURE, "{}"))
                }.getOrNull()
                pending?.optString("id") == id && pending?.optString("characterId") == characterId
            },
            onAllow = { handleCaptureResponse(context.applicationContext, id, characterId, true) },
            onDeny = { handleCaptureResponse(context.applicationContext, id, characterId, false) },
            onFailure = {
                report(context, if (canNotify) "系统拒绝悬浮弹窗；已保留通知栏中的查看请求"
                    else "系统拒绝悬浮弹窗；未截图，可到权限与能力页检查悬浮窗权限")
            },
        )
        if (!canNotify && !popup) {
            prefs.edit().remove(PENDING_CAPTURE).commit()
            return rejected(context, "悬浮弹窗无法显示且通知不可用，未截图", "failed")
        }
        report(context, if (popup) "请求查看屏幕 · 已尝试悬浮弹窗，等待主人允许（未截图）"
            else "请求查看屏幕 · 通知后备，等待主人允许（未截图）")
        return JSONObject().put("success", true).put("status", "pending")
            .put("summary", if (popup) "已请求显示「请求查看屏幕」悬浮弹窗，尚未截图；主人可允许一次或拒绝"
                else "悬浮窗未授权，已发送「请求查看屏幕」通知；尚未截图").toString()
    }

    internal fun handleCaptureResponse(context: Context, id: String, characterId: String,
        approved: Boolean) {
        val prefs = preferences(context)
        val saved = runCatching { JSONObject(prefs.getString(PENDING_CAPTURE, "{}")) }.getOrNull()
            ?: return
        if (saved.optString("id") != id || saved.optString("characterId") != characterId) return
        val age = System.currentTimeMillis() - saved.optLong("at")
        if (age !in 0L..CAPTURE_EXPIRES_MS) {
            prefs.edit().remove(PENDING_CAPTURE).commit()
            report(context, "屏幕查看请求已过期，没有截图")
            return
        }
        if (!prefs.edit().remove(PENDING_CAPTURE).commit()) return
        LuluScreenRequestPopup.dismissCapture(id)
        context.getSystemService(NotificationManager::class.java)
            .cancel(CAPTURE_NOTIFICATION_ID)
        val canProceed = approved && autonomousAllowed(context)
        if (!canProceed) report(context, if (approved) "角色屏幕授权已撤销，未截图" else "主人拒绝查看屏幕，未截图")
        else report(context, "主人允许本次截图 · 等待实际截图")
        // Even a refusal is durable feedback to the character, never silent success.
        val work = OneTimeWorkRequestBuilder<LuluApprovedScreenWorker>()
            .setInputData(Data.Builder().putString("characterId", characterId)
                .putString("requestId", id).putBoolean("approved", canProceed).build())
            .setInitialDelay(if (canProceed) 2L else 0L, java.util.concurrent.TimeUnit.SECONDS).build()
        WorkManager.getInstance(context.applicationContext)
            .enqueueUniqueWork("screen-approved-$id", ExistingWorkPolicy.KEEP, work)
    }

    private fun viewingNotice(context: Context, title: String, message: String,
        finished: Boolean = false) {
        if (!notificationsAvailable(context)) return
        context.getSystemService(NotificationManager::class.java)
            .notify(CAPTURE_NOTIFICATION_ID, Notification.Builder(context, CAPTURE_CHANNEL)
                .setSmallIcon(android.R.drawable.ic_menu_view)
                .setContentTitle(title).setContentText(message)
                .setCategory(Notification.CATEGORY_STATUS)
                .setOnlyAlertOnce(true).setAutoCancel(finished)
                .setOngoing(!finished).build())
    }

    /** One frame, and one clearly visible notification, even for explicit chat requests. */
    suspend fun capture(context: Context, userRequested: Boolean,
        characterId: String = "", requestId: String = "", approved: Boolean = false): String {
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
        if (!userRequested && !approved) return requestCapture(context, characterId, requestId)
        if (!notificationsAvailable(context) && !LuluScreenRequestPopup.available(context))
            return rejected(context, "屏幕查看提醒方式都不可用，拒绝无提示截图")
        if (!busy.compareAndSet(false, true)) return rejected(context, "当前已有识图请求", "busy")
        viewingNotice(context, "正在查看屏幕", "识别中")
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
                LuluScreenRequestPopup.showProgress(context)
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
        } finally {
            busy.set(false)
            LuluScreenRequestPopup.dismissProgress()
            viewingNotice(context, "屏幕查看已结束",
                diagnostics(context).substringAfter(" · ").take(100), true)
        }
    }

    /** A notification the phone owner may tap, never a background Activity hijack. */
    fun summon(context: Context, characterId: String = ""): String {
        if (!summonAllowed(context)) return rejected(context, "未授权邀请返回露露机")
        val canNotify = (Build.VERSION.SDK_INT < 33 ||
            ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) ==
            PackageManager.PERMISSION_GRANTED) &&
            NotificationManagerCompat.from(context).areNotificationsEnabled()
        if (!canNotify && !LuluScreenRequestPopup.available(context))
            return rejected(context, "未开启悬浮窗或通知，无法让主人收到返回露露机的请求")
        val launch = context.packageManager.getLaunchIntentForPackage(context.packageName)
            ?: return rejected(context, "未找到露露机启动入口", "failed")
        launch.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP)
        val pending = PendingIntent.getActivity(context, 41031, launch,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        val manager = context.getSystemService(NotificationManager::class.java)
        val channel = "lulu_summon_v1"
        manager.createNotificationChannel(NotificationChannel(channel, "角色邀请返回",
            NotificationManager.IMPORTANCE_HIGH))
        val channelAvailable = canNotify &&
            manager.getNotificationChannel(channel)?.importance != NotificationManager.IMPORTANCE_NONE
        val roleName = runCatching {
            MigratedDomainStores.characters.get(characterId).displayName
        }.getOrNull()?.takeIf(String::isNotBlank) ?: "角色"
        if (channelAvailable) {
            manager.notify(41031, Notification.Builder(context, channel)
                .setSmallIcon(android.R.drawable.ic_dialog_info)
                .setContentTitle("$roleName 邀请你回来").setContentText("返回露露机")
                .setCategory(Notification.CATEGORY_REMINDER).setPriority(Notification.PRIORITY_HIGH)
                .setContentIntent(pending).setAutoCancel(true).build())
        }
        val popup = LuluScreenRequestPopup.showReturn(context, roleName,
            onReturn = {
                manager.cancel(41031)
                report(context, "主人点击了「返回露露机」，正在交由 Android 打开")
                runCatching { pending.send() }.onFailure {
                    report(context, "主人点击返回，但 Android 未成功启动：" +
                        it.message.orEmpty().take(75))
                }
            },
            onLater = {
                manager.cancel(41031)
                report(context, "主人选择了「稍后」，本次不打开露露机")
            },
            onFailure = {
                report(context, if (channelAvailable) "系统拒绝悬浮弹窗；已保留返回通知"
                    else "系统拒绝悬浮弹窗，返回邀请未能显示")
            },
        )
        if (!channelAvailable && !popup)
            return rejected(context, "悬浮窗未显示，且返回通知不可用", "failed")
        report(context, if (popup) "请求返回露露机 · 已尝试悬浮弹窗，等待主人操作"
            else "请求返回露露机 · 通知后备，等待主人点击")
        return JSONObject().put("success", true)
            .put("status", if (popup) "popup_requested" else "notification_posted")
            .put("summary", if (popup) "已尝试弹出「请求返回露露机」，主人可返回或稍后；尚未确认返回"
                else "已发送「请求返回露露机」通知；主人是否点击尚未确认").toString()
    }

    /**
     * A scheduled request to return has an explicit owner-chosen action.
     * The overlay is a first-class UI, the notification only a fallback when
     * Android blocks overlays. Merely showing it is NOT confirmation the
     * owner returned or completed the task.
     */
    fun scheduledReminder(context: Context, characterId: String, title: String,
        requestId: String, conversationId: String = ""): String {
        if (characterId.isBlank() || requestId.isBlank()) return rejected(
            context, "定时提醒缺少角色或事件ID", "failed")
        val app = context.applicationContext
        val canNotify = (Build.VERSION.SDK_INT < 33 ||
            ContextCompat.checkSelfPermission(app, Manifest.permission.POST_NOTIFICATIONS) ==
            PackageManager.PERMISSION_GRANTED) &&
            NotificationManagerCompat.from(app).areNotificationsEnabled()
        if (!canNotify && !LuluScreenRequestPopup.available(app))
            return rejected(app, "定时提醒需要通知或悬浮窗权限；未显示", "failed")
        val launch = Intent(app, com.jiacimu.lulu.MigrationActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
        if (conversationId.isNotBlank()) launch.putExtra("open_conversation_id", conversationId)
        val noticeId = "scheduled:$requestId".hashCode()
        val pending = PendingIntent.getActivity(app, noticeId, launch,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        val roleName = runCatching { MigratedDomainStores.characters.get(characterId).displayName }
            .getOrNull()?.takeIf(String::isNotBlank) ?: "角色"
        val shortTitle = title.trim().take(34).ifBlank { "约好的时间到了" }
        // Legacy versions could schedule the same study wake in both the
        // commitment alarm and the character's personal plan. One real
        // invitation is enough; keep unrelated topics independent.
        val family = when {
            Regex("学习|复习|背书|做题|作业").containsMatchIn(title) -> "study"
            Regex("睡觉|睡前|入睡|哄睡|休息").containsMatchIn(title) -> "rest"
            Regex("起床|叫醒|醒来").containsMatchIn(title) -> "wake"
            else -> shortTitle
        }
        val key = "$characterId:$family"
        val nowMillis = System.currentTimeMillis()
        val prefs = preferences(app)
        val alreadyOffered = prefs.getString("last_schedule_popup_key", "") == key &&
            nowMillis - prefs.getLong("last_schedule_popup_at", 0L) in 0L..90_000L
        if (alreadyOffered) return JSONObject()
            .put("success", true).put("status", "duplicate_suppressed")
            .put("summary", "同一事项的近期弹窗已请求，未重复打扰").toString()
        val manager = app.getSystemService(NotificationManager::class.java)
        val channel = "lulu_scheduled_return_v1"
        manager.createNotificationChannel(NotificationChannel(channel,
            "角色日程邀请", NotificationManager.IMPORTANCE_HIGH))
        val notificationReady = canNotify &&
            manager.getNotificationChannel(channel)?.importance != NotificationManager.IMPORTANCE_NONE
        if (notificationReady) manager.notify(noticeId, Notification.Builder(app, channel)
            .setSmallIcon(com.jiacimu.lulu.R.drawable.lulu_exact_icon)
            .setContentTitle("$roleName 来喊你啦")
            .setContentText(shortTitle)
            .setCategory(Notification.CATEGORY_REMINDER)
            .setPriority(Notification.PRIORITY_HIGH)
            .setContentIntent(pending).setAutoCancel(true).build())
        val popup = LuluScreenRequestPopup.showScheduledReminder(app, roleName, shortTitle,
            requestId,
            onReturn = { manager.cancel(noticeId); runCatching { pending.send() } },
            onLater = { manager.cancel(noticeId) },
            onFailure = { report(app, "系统未能显示定时提醒悬浮窗" +
                if (notificationReady) "；已保留通知" else "；通知不可用") },
        )
        if (!popup && !notificationReady)
            return rejected(app, "悬浮窗被禁止，通知不可用", "failed")
        prefs.edit().putString("last_schedule_popup_key", key)
            .putLong("last_schedule_popup_at", nowMillis).apply()
        report(app, if (popup) "已尝试显示定时提醒悬浮弹窗，等待主人选择"
            else "定时提醒改用通知后备")
        return JSONObject().put("success", true)
            .put("status", if (popup) "popup_requested" else "notification_posted")
            .put("summary", "定时邀请已请求显示；未确认主人已返回或完成事项").toString()
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

/** Notification actions are app-private and checked against one durable, expiring request. */
class LuluScreenRequestReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val action = intent.action
        if (action !in setOf("lulu.screen.ALLOW", "lulu.screen.DENY")) return
        LuluScreenAwareness.handleCaptureResponse(context,
            intent.getStringExtra("requestId").orEmpty(),
            intent.getStringExtra("characterId").orEmpty(),
            action == "lulu.screen.ALLOW")
    }
}

/** Screenshot and model interpretation may take longer than a broadcast receiver lifetime. */
class LuluApprovedScreenWorker(context: Context, params: WorkerParameters) :
    CoroutineWorker(context, params) {
    override suspend fun doWork(): Result {
        val role = inputData.getString("characterId").orEmpty()
        val requestId = inputData.getString("requestId").orEmpty()
        if (role.isBlank() || requestId.isBlank()) return Result.failure()
        initializeBackgroundRuntime(applicationContext)
        val permitted = inputData.getBoolean("approved", false)
        val response = if (!permitted) {
            JSONObject().put("success", false).put("status", "denied")
                .put("summary", "主人没有同意本次屏幕查看，没有截图")
        } else {
            val raw = LuluScreenAwareness.capture(applicationContext,
                userRequested = false, characterId = role, requestId = requestId, approved = true)
            runCatching { JSONObject(raw) }.getOrNull()
                ?: JSONObject().put("success", false).put("error", "未返回有效截图结果")
        }
        val success = response.optBoolean("success")
        // This independent evidence is created AFTER the model actually sees the image.
        // The earlier permission request's pending receipt never claims observation.
        SharedExperienceTimeline.record("screen-observation-$role-$requestId",
            role, if (success) "屏幕观察" else "屏幕观察失败", "手机",
            response.toString(), source = "screen-consent",
            evidenceKind = if (success) EventEvidenceKind.Observation
                else EventEvidenceKind.ToolResult)
        if (success) {
            // A planned screen request was only 'pending' when initially posted.
            // Fulfil that plan only after an approved screenshot was really understood.
            val original = runCatching {
                JSONObject(applicationContext.getSharedPreferences("lulu_tool_executions",
                    Context.MODE_PRIVATE).getString("$role:$requestId", "{}"))
            }.getOrNull()
            val planId = original?.optString("planId").orEmpty()
            if (planId.isNotBlank()) runCatching {
                com.jiacimu.lulu.data.RoleDayScheduleStore.completeFromReceipt(
                    applicationContext, role, planId, "capture_screen",
                    "screen-observation-$role-$requestId",
                    response.optString("summary"), Instant.now())
            }.onFailure { android.util.Log.w("LuluScreen", "Scheduled screen receipt not settled", it) }
            runCatching {
                ProactivePerceptionRuntime.runDueCycle(applicationContext,
                    trigger = "主人同意的屏幕识图已取得真实观察", targetCharacterId = role, force = true)
            }
        }
        return Result.success()
    }
}
