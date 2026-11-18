package com.jiacimu.lulu.system

import android.Manifest
import android.app.KeyguardManager
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.PowerManager
import android.view.Display
import android.view.accessibility.AccessibilityEvent
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import androidx.work.CoroutineWorker
import androidx.work.Data
import androidx.work.ExistingWorkPolicy
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import com.jiacimu.lulu.data.EventEvidenceKind
import com.jiacimu.lulu.data.ProactivePerceptionRuntime
import com.jiacimu.lulu.data.SharedExperienceTimeline
import com.jiacimu.lulu.data.initializeBackgroundRuntime
import org.json.JSONArray
import org.json.JSONObject
import java.time.Instant
import java.util.UUID
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

/** Role-selected, temporary app supervision. The owner can revoke it at any time. */
internal object LuluStrictMode {
    const val GRANT = "strict_mode_owner_grant"
    private const val PREFS = "lulu_strict_mode_v1"
    private const val ACTIVE = "active"
    private const val CHANNEL = "lulu_strict_mode"
    private const val NOTICE_ID = 42081
    internal const val REVIEW_GAP = 20_000L
    internal const val LOCAL_SAMPLE_GAP = 10_000L
    private const val MAX_LEASE_MINUTES = 120
    private val writer = Executors.newSingleThreadExecutor { task ->
        Thread(task, "lulu-strict-receipts").apply { isDaemon = true }
    }
    private var lastPackage = ""
    private var lastObservationAt = 0L
    private var lastReturnAttemptAt = 0L

    private fun prefs(context: Context) =
        context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
    private fun stored(context: Context): JSONObject =
        runCatching { JSONObject(prefs(context).getString(ACTIVE, "{}")) }.getOrDefault(JSONObject())

    fun ownerGranted(context: Context): Boolean =
        LuluScreenAwareness.consented(context) &&
            LuluScreenAwareness.preferences(context).getBoolean(GRANT, false)

    @Synchronized
    fun active(context: Context, now: Long = System.currentTimeMillis()): JSONObject? {
        val value = stored(context)
        return value.takeIf { it.optString("id").isNotBlank() &&
            it.optLong("startedAt") <= now && it.optLong("endsAt") > now &&
            ownerGranted(context) && LuluScreenAwareness.appTracking(context) }
    }

    internal fun shouldReturn(
        mode: String, foreground: String, lulu: String,
        exceptions: Set<String>, isProtected: Boolean,
        blockedPackages: Set<String> = emptySet(),
    ): Boolean = mode in setOf("return", "home") && foreground.isNotBlank() &&
        foreground != lulu && foreground !in exceptions && !isProtected &&
        (blockedPackages.isEmpty() || foreground in blockedPackages)

    private fun notificationsReady(context: Context): Boolean {
        if (Build.VERSION.SDK_INT >= 33 &&
            ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) !=
            PackageManager.PERMISSION_GRANTED) return false
        if (!NotificationManagerCompat.from(context).areNotificationsEnabled()) return false
        val manager = context.getSystemService(NotificationManager::class.java)
        manager.createNotificationChannel(NotificationChannel(CHANNEL,
            "角色临时强制监督", NotificationManager.IMPORTANCE_LOW))
        return manager.getNotificationChannel(CHANNEL)?.importance != NotificationManager.IMPORTANCE_NONE
    }

    private fun showNotice(context: Context, value: JSONObject) {
        val id = value.optString("id")
        val stop = PendingIntent.getBroadcast(context, id.hashCode(),
            Intent(context, LuluStrictModeReceiver::class.java)
                .setAction("lulu.strict.STOP").putExtra("sessionId", id),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        val label = when (value.optString("mode")) {
            "return" -> "即时切回露露机"
            "home" -> "即时返回桌面"
            else -> "高频观察应用"
        }
        context.getSystemService(NotificationManager::class.java).notify(
            NOTICE_ID, Notification.Builder(context, CHANNEL)
                .setSmallIcon(android.R.drawable.ic_lock_idle_alarm)
                .setContentTitle("露露机 · 临时强制监督")
                .setContentText(label + " · " + value.optString("goal").take(30) + " · 可随时结束")
                .setOngoing(true).setOnlyAlertOnce(true)
                .setCategory(Notification.CATEGORY_SERVICE)
                .addAction(Notification.Action.Builder(
                    android.R.drawable.ic_menu_close_clear_cancel, "立即结束", stop).build())
                .build())
    }

    private fun response(success: Boolean, status: String, message: String): String =
        JSONObject().put("success", success).put("status", status)
            .put(if (success) "summary" else "error", message).toString()

    /** start/adjust/stop/status is a genuine role tool, not an automatic bedtime rule. */
    @Synchronized
    fun command(context: Context, role: String, args: JSONObject): String {
        if (role.isBlank() || role.startsWith("resident:"))
            return response(false, "denied", "居民不能控制主人的手机")
        val action = args.optString("action", "status").lowercase()
        if (action == "status") return status(context, role)
        val previous = active(context)
        if (action == "stop") {
            if (previous?.optString("characterId") != role)
                return response(false, "inactive", "本角色没有正在监督的会话")
            val stopped = stop(context, previous.optString("id"), "角色决定结束监督")
            return response(stopped, if (stopped) "stopped" else "failed",
                if (stopped) "限时监督已经结束" else "结束监督时保存失败")
        }
        if (action !in setOf("start", "adjust"))
            return response(false, "invalid", "action 必须是 start/adjust/stop/status")
        if (!ownerGranted(context) || !LuluScreenAwareness.appTracking(context))
            return response(false, "denied", "主人未单独授权强制模式或关闭了应用使用感知")
        if (!LuluAccessibilityService.state.value.connected)
            return response(false, "unavailable", "无障碍服务未连接")
        if (!notificationsReady(context))
            return response(false, "unavailable", "必须开启带立即结束按钮的持续通知")
        if (previous != null && previous.optString("characterId") != role)
            return response(false, "busy", "另一角色正在负责本段监督")
        if (action == "adjust" && previous == null)
            return response(false, "inactive", "没有可以调整的监督会话")
        val mode = args.optString("mode", previous?.optString("mode") ?: "observe")
        if (mode !in setOf("observe", "return", "home"))
            return response(false, "invalid", "mode 仅支持 observe/return/home")
        if (mode != "observe" && !LuluScreenAwareness.directReturnAllowed(context))
            return response(false, "denied", "主人未开启直接切回授权")
        val goal = args.optString("goal", previous?.optString("goal").orEmpty()).trim()
        if (goal.isBlank() || goal.length > 160)
            return response(false, "invalid", "请由角色提供本次具体监督目标（1至160字）")
        // The lease is a safety backstop, never evidence of goal completion.
        val duration = args.optInt("minutes", MAX_LEASE_MINUTES)
        if (duration !in 5..MAX_LEASE_MINUTES)
            return response(false, "invalid", "单次安全租约为5至120分钟，角色可再评估后重新开启")
        val exceptions = mutableSetOf<String>()
        val chosen: JSONArray = if (args.has("exceptPackages")) {
            args.optJSONArray("exceptPackages")
                ?: return response(false, "invalid", "exceptPackages 需要应用包名数组")
        } else previous?.optJSONArray("exceptPackages") ?: JSONArray()
        if (chosen.length() > 32) return response(false, "invalid", "临时例外应用不能超过32个")
        for (index in 0 until chosen.length()) {
            val pkg = chosen.optString(index)
            if (!pkg.matches(Regex("[a-zA-Z0-9_]+(\\.[a-zA-Z0-9_]+)+")))
                return response(false, "invalid", "例外应用必须是真实包名")
            exceptions.add(pkg)
        }
        val blocked = mutableSetOf<String>()
        val blockedArray = if (args.has("blockedPackages")) {
            args.optJSONArray("blockedPackages")
                ?: return response(false, "invalid", "blockedPackages 需要应用包名数组")
        } else previous?.optJSONArray("blockedPackages") ?: JSONArray()
        if (blockedArray.length() > 32) return response(false, "invalid", "禁止应用不能超过32个")
        for (index in 0 until blockedArray.length()) {
            val pkg = blockedArray.optString(index)
            if (!pkg.matches(Regex("[a-zA-Z0-9_]+(\\.[a-zA-Z0-9_]+)+")))
                return response(false, "invalid", "禁止应用必须是真实包名")
            if (LuluScreenAwareness.isExcluded(context, pkg) || pkg == context.packageName)
                return response(false, "denied", "系统、隐私或露露机自身不能加入禁止应用")
            blocked.add(pkg)
        }
        val now = System.currentTimeMillis()
        val session = JSONObject()
            .put("id", previous?.optString("id") ?: UUID.randomUUID().toString())
            .put("characterId", role).put("mode", mode).put("goal", goal)
            .put("startedAt", previous?.optLong("startedAt") ?: now)
            .put("endsAt", minOf(now + duration * 60_000L,
                (previous?.optLong("startedAt") ?: now) + 120L * 60_000L))
            .put("exceptPackages", JSONArray(exceptions.toList()))
            .put("blockedPackages", JSONArray(blocked.toList()))
        if (!prefs(context).edit().putString(ACTIVE, session.toString()).commit())
            return response(false, "failed", "无法持久化强制监督会话")
        lastPackage = ""
        lastObservationAt = 0L
        lastReturnAttemptAt = 0L
        if (previous == null) prefs(context).edit().putLong("lastReview", 0L).apply()
        showNotice(context, session)
        // Expiry is a local time limit, not a character decision to continue watching.
        val expiry = OneTimeWorkRequestBuilder<LuluStrictExpiryWorker>()
            .setInitialDelay(session.optLong("endsAt") - now + 2_000L, TimeUnit.MILLISECONDS)
            .setInputData(Data.Builder().putString("sessionId", session.optString("id")).build())
            .build()
        WorkManager.getInstance(context.applicationContext).enqueueUniqueWork(
            "lulu-strict-expiry-" + session.optString("id"), ExistingWorkPolicy.REPLACE, expiry)
        writer.execute {
            record(role, "strict-mode-" + session.optString("id") + "-" + now,
                "角色目标=" + goal + "；监督方式=" + mode + "；安全租约到期=" +
                    Instant.ofEpochMilli(session.optLong("endsAt")) +
                    "；禁止应用=" + blocked.joinToString() + "；例外应用=" + exceptions.joinToString() +
                    "。主人可随时退出", EventEvidenceKind.ToolResult)
        }
        return JSONObject().put("success", true).put("status", "active")
            .put("sessionId", session.optString("id"))
            .put("mode", mode).put("goal", goal)
            .put("endsAt", Instant.ofEpochMilli(session.optLong("endsAt")).toString())
            .put("summary", "目标监督已启动或调整；安全租约到期不代表目标已完成").toString()
    }

    fun status(context: Context, role: String): String {
        val state = active(context)
        if (state?.optString("characterId") != role)
            return response(true, "inactive", "本角色当前没有有效强制模式")
        return JSONObject().put("success", true).put("status", "active")
            .put("mode", state.optString("mode"))
            .put("goal", state.optString("goal"))
            .put("blockedPackages", state.optJSONArray("blockedPackages") ?: JSONArray())
            .put("endsAt", Instant.ofEpochMilli(state.optLong("endsAt")).toString())
            .put("recent", prefs(context).getString("recent_" + state.optString("id"), "[]"))
            .toString()
    }

    /** Window events and ten-second local pulse use the same owner-screen policy.
     * The model is NOT called for each observation or accessibility event. */
    @Synchronized
    fun onWindowChanged(context: Context, event: AccessibilityEvent) {
        if (event.eventType != AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED &&
            event.eventType != AccessibilityEvent.TYPE_WINDOWS_CHANGED) return
        if (Build.VERSION.SDK_INT >= 30 && event.displayId > Display.DEFAULT_DISPLAY) return
        observeForeground(context, event.packageName?.toString())
    }

    @Synchronized
    fun onPulse(context: Context) {
        observeForeground(context, null)
    }

    private fun observeForeground(context: Context, expectedPackage: String?) {
        val session = active(context) ?: return
        if (session.optString("mode") != "observe" &&
            !LuluScreenAwareness.directReturnAllowed(context)) {
            stop(context, session.optString("id"), "切回授权已撤销")
            return
        }
        if (!NotificationManagerCompat.from(context).areNotificationsEnabled()) {
            stop(context, session.optString("id"), "监督通知已关闭")
            return
        }
        val keyguard = context.getSystemService(KeyguardManager::class.java)
        val power = context.getSystemService(PowerManager::class.java)
        if (keyguard?.isKeyguardLocked != false || power?.isInteractive != true) return
        val foreground = LuluAccessibilityService.ownerForegroundPackage().orEmpty()
        if (foreground.isBlank() ||
            (expectedPackage != null && expectedPackage != foreground) ||
            LuluScreenAwareness.isExcluded(context, foreground)) return
        val now = System.currentTimeMillis()
        val changed = lastPackage != foreground
        if (!changed && now - lastObservationAt < LOCAL_SAMPLE_GAP) return
        lastPackage = foreground
        lastObservationAt = now
        val except = (session.optJSONArray("exceptPackages") ?: JSONArray()).let { array ->
            (0 until array.length()).map { array.optString(it) }.toSet()
        }
        val blocked = (session.optJSONArray("blockedPackages") ?: JSONArray()).let { array ->
            (0 until array.length()).map { array.optString(it) }.toSet()
        }
        val homePackage = LuluAccessibilityService.ownerHomePackage(context)
        // A launcher is a safe escape surface, never something to intercept.
        val interrupt = foreground != homePackage &&
            shouldReturn(session.optString("mode"), foreground,
                context.packageName, except, false, blocked)
        val attempt = interrupt && (changed || now - lastReturnAttemptAt >= LOCAL_SAMPLE_GAP)
        if (attempt) lastReturnAttemptAt = now
        val accepted = when {
            !attempt -> false
            session.optString("mode") == "home" ->
                LuluAccessibilityService.requestOwnerHome()
            else -> LuluAccessibilityService.requestLuluForeground(context)
        }
        val id = session.optString("id")
        android.os.Handler(android.os.Looper.getMainLooper()).postDelayed({
            if (active(context)?.optString("id") != id) return@postDelayed
            val after = LuluAccessibilityService.ownerForegroundPackage().orEmpty()
            val confirmed = attempt && accepted && after.isNotBlank() &&
                when (session.optString("mode")) {
                    "home" -> homePackage != null && after == homePackage
                    "return" -> after == context.packageName
                    else -> false
                }
            val detail = "前台应用=" + foreground + "；目标=" + session.optString("goal") +
                "；监督=" + session.optString("mode") + "；尝试切回=" + accepted +
                "；切回后核实=" + after + "；成功=" + confirmed
            writer.execute { storeObservation(context, id, session.optString("characterId"), detail) }
        }, if (attempt) 650L else 100L)
    }

    private fun storeObservation(context: Context, id: String, role: String, detail: String) {
        if (active(context)?.optString("id") != id) return
        val now = System.currentTimeMillis()
        val p = prefs(context)
        val key = "recent_" + id
        val old = runCatching { JSONArray(p.getString(key, "[]")) }.getOrDefault(JSONArray())
        val next = JSONArray()
        for (index in (old.length() - 9).coerceAtLeast(0) until old.length()) next.put(old.opt(index))
        next.put(JSONObject().put("at", now).put("detail", detail))
        p.edit().putString(key, next.toString()).apply()
        // Per-switch evidence stays in the bounded session log. Only a single
        // batch enters the raw character timeline when a paid review is due.
        val lastReview = p.getLong("lastReview", 0L)
        if (now - lastReview >= REVIEW_GAP &&
            p.edit().putLong("lastReview", now).commit()) {
            val request = OneTimeWorkRequestBuilder<LuluStrictReviewWorker>()
                .setInitialDelay(8, TimeUnit.SECONDS)
                .setInputData(Data.Builder().putString("sessionId", id)
                    .putString("characterId", role).build()).build()
            WorkManager.getInstance(context.applicationContext).enqueueUniqueWork(
                "lulu-strict-review-" + id, ExistingWorkPolicy.KEEP, request)
        }
    }

    private fun record(role: String, id: String, detail: String, kind: EventEvidenceKind) {
        runCatching {
            SharedExperienceTimeline.record(id, role, "临时强制监督", "手机", detail,
                source = "strict-mode", evidenceKind = kind, triggerExtraction = false)
        }
    }

    @Synchronized
    fun stop(context: Context, id: String = "", reason: String = "强制模式结束"): Boolean {
        val current = stored(context)
        val sessionId = current.optString("id")
        if (sessionId.isBlank() || (id.isNotBlank() && id != sessionId)) return false
        if (!prefs(context).edit().remove(ACTIVE)
                .remove("recent_" + sessionId).commit()) return false
        lastPackage = ""
        lastObservationAt = 0L
        lastReturnAttemptAt = 0L
        context.getSystemService(NotificationManager::class.java).cancel(NOTICE_ID)
        WorkManager.getInstance(context.applicationContext)
            .cancelUniqueWork("lulu-strict-review-" + sessionId)
        WorkManager.getInstance(context.applicationContext)
            .cancelUniqueWork("lulu-strict-expiry-" + sessionId)
        writer.execute {
            record(current.optString("characterId"), "strict-ended-" + sessionId,
                reason, EventEvidenceKind.Observation)
        }
        return true
    }

    /** Explicit owner stop revokes future automatic role restarts. */
    fun ownerStop(context: Context, reason: String = "主人结束强制监督") {
        LuluScreenAwareness.preferences(context).edit().putBoolean(GRANT, false).commit()
        stop(context, reason = reason)
    }

    fun restore(context: Context) {
        val value = stored(context)
        if (value.optString("id").isBlank()) return
        if (active(context) == null || !notificationsReady(context) ||
            (value.optString("mode") != "observe" &&
                !LuluScreenAwareness.directReturnAllowed(context)))
            stop(context, value.optString("id"), "授权、通知或时限失效")
        else showNotice(context, value)
    }

    fun modelContext(context: Context, role: String): String {
        if (role.startsWith("resident:")) return ""
        return "【角色自主目标监督】主人授权=" + ownerGranted(context) +
            "；直接切回授权=" + LuluScreenAwareness.directReturnAllowed(context) +
            "；本角色状态=" + status(context, role) + "。" +
            "strict_supervision 支持 start/adjust/stop/status；start 必须提供真实具体 goal，" +
            "mode=observe 只观察；mode=return 切回露露机；mode=home 返回手机桌面。" +
            "可选 blockedPackages 为需限制的普通 App 包名数组，留空时所有非例外普通 App 都会切回；" +
            "exceptPackages 为不拦截名单。由你依实际目标选择，不预设用户任何应用违规。" +
            "你可决定何时停止或重新启动；安全租约默认最多120分钟，并不代表任务完成。" +
            "目标确证达成后主动 stop，未证实可继续或安排稍后复查。" +
            "已获得授权时本机约10秒检查前台应用、约20秒交同一角色模型复议（系统后台可能延迟），" +
            "不是每10秒截图或付费；锁屏、无回复并不能证明睡着。" +
            "这里只能切换前台，不能真正强停或系统级锁死第三方 App；" +
            "系统/支付/通话/隐私应用受保护，主人可从设置或通知随时关闭并撤销未来重启权。" +
            "操作成功需新证据验证，旧回执不能充当现在的状态。"
    }
}

class LuluStrictModeReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action == "lulu.strict.STOP") {
            val id = intent.getStringExtra("sessionId").orEmpty()
            if (id.isNotBlank() && LuluStrictMode.active(context)?.optString("id") == id)
                LuluStrictMode.ownerStop(context, "主人从通知栏主动停止")
        }
    }
}

class LuluStrictReviewWorker(context: Context, params: WorkerParameters) :
    CoroutineWorker(context, params) {
    override suspend fun doWork(): Result {
        val role = inputData.getString("characterId").orEmpty()
        val id = inputData.getString("sessionId").orEmpty()
        val current = LuluStrictMode.active(applicationContext)
        if (id.isBlank() || current?.optString("id") != id ||
            current?.optString("characterId") != role) return Result.success()
        return runCatching {
            initializeBackgroundRuntime(applicationContext)
            val fresh = LuluStrictMode.status(applicationContext, role)
            val snapshot = JSONObject(fresh).optString("recent").take(4500)
            if (LuluStrictMode.active(applicationContext)?.optString("id") != id)
                return@runCatching Result.success()
            SharedExperienceTimeline.record(
                "strict-review-" + id + "-" + System.currentTimeMillis(),
                role, "临时强制监督", "手机",
                "本段监督最近的真实应用变化与切回核验：" + snapshot,
                source = "strict-mode", evidenceKind = EventEvidenceKind.Observation,
                triggerExtraction = false)
            ProactivePerceptionRuntime.runDueCycle(applicationContext,
                "目标监督本次真实应用证据不是入睡或专注成果证明。请本人决定是否达成、继续、改变力度、结束或安排稍后复查，不要仅凭无回复结案",
                targetCharacterId = role, force = true)
            Result.success()
        }.getOrElse { Result.retry() }
    }
}

/** Backup expiry even when there are no later app-switch accessibility events. */
class LuluStrictExpiryWorker(context: Context, params: WorkerParameters) :
    CoroutineWorker(context, params) {
    override suspend fun doWork(): Result {
        val id = inputData.getString("sessionId").orEmpty()
        val session = LuluStrictMode.active(applicationContext)
        if (id.isNotBlank() && session?.optString("id") == id) {
            // An adjusted session will have replaced this worker with its new deadline.
            return Result.success()
        }
        if (id.isNotBlank()) LuluStrictMode.stop(applicationContext, id, "限时监督到期")
        return Result.success()
    }
}
