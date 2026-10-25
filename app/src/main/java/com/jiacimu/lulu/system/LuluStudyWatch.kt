package com.jiacimu.lulu.system

import android.Manifest
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.KeyguardManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.content.ContextCompat
import androidx.core.app.NotificationManagerCompat
import androidx.work.CoroutineWorker
import androidx.work.Data
import androidx.work.ExistingWorkPolicy
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import com.jiacimu.lulu.data.CompanionPresenceStore
import com.jiacimu.lulu.data.EventEvidenceKind
import com.jiacimu.lulu.data.MigratedDomainStores
import com.jiacimu.lulu.data.ProactivePerceptionPolicyStore
import com.jiacimu.lulu.data.ProactivePerceptionRuntime
import com.jiacimu.lulu.data.SharedExperienceTimeline
import com.jiacimu.lulu.data.initializeBackgroundRuntime
import org.json.JSONObject
import java.time.Instant
import java.util.UUID
import java.util.concurrent.TimeUnit

/**
 * Opt-in, bounded screen supervision. Operational consent/timing only:
 * motives and relationship decisions remain in the character's usual stores.
 *
 * A role may ASK to supervise; only the human's explicit session approval
 * enables repeated frames. A persistent notification exposes STOP. No frames
 * are stored, and every permitted screenshot goes through LuluScreenAwareness.
 */
internal object LuluStudyWatch {
    private const val PREFS = "lulu_screen_awareness_v1"
    private const val PENDING = "study_watch_pending"
    private const val ACTIVE = "study_watch_active"
    private const val LATEST = "study_watch_latest"
    private const val CHANNEL = "lulu_study_watch"
    private const val REQUEST_NOTICE = 42061
    private const val ACTIVE_NOTICE = 42062
    private const val CONSENT_LIFETIME_MS = 120_000L
    private const val MAX_AGE_MS = 180L * 60_000L
    private const val MIN_AGE_MS = 15L * 60_000L

    private fun prefs(context: Context) =
        context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    private fun parse(raw: String?): JSONObject =
        runCatching { JSONObject(raw ?: "{}") }.getOrDefault(JSONObject())

    private fun pending(context: Context) = parse(prefs(context).getString(PENDING, "{}"))
    private fun rawActive(context: Context) = parse(prefs(context).getString(ACTIVE, "{}"))

    private fun notificationsAllowed(context: Context): Boolean {
        if (Build.VERSION.SDK_INT >= 33 &&
            ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) !=
            PackageManager.PERMISSION_GRANTED) return false
        if (!NotificationManagerCompat.from(context).areNotificationsEnabled()) return false
        val manager = context.getSystemService(NotificationManager::class.java)
        manager.createNotificationChannel(NotificationChannel(
            CHANNEL, "学习监督进行中", NotificationManager.IMPORTANCE_LOW))
        return manager.getNotificationChannel(CHANNEL)?.importance != NotificationManager.IMPORTANCE_NONE
    }

    private fun notificationAction(context: Context, action: String, id: String): PendingIntent =
        PendingIntent.getBroadcast(context, (action + id).hashCode(),
            Intent(context, LuluStudyWatchReceiver::class.java)
                .setAction(action).putExtra("sessionId", id),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)

    private fun label(role: String): String = runCatching {
        MigratedDomainStores.characters.get(role).displayName
    }.getOrDefault("角色").ifBlank { "角色" }

    @Synchronized
    fun active(context: Context, role: String, now: Long = System.currentTimeMillis()): JSONObject? {
        val entry = rawActive(context)
        if (entry.optString("characterId") != role || entry.optString("id").isBlank()) return null
        val end = entry.optLong("endsAt")
        return entry.takeIf { now >= entry.optLong("startedAt") && now < end }
    }

    @Synchronized
    fun request(context: Context, role: String, requestedMinutes: Int): String {
        if (role.isBlank() || role.startsWith("resident:"))
            return response(false, "学习监督仅供本人真实聊天角色使用")
        if (!LuluScreenAwareness.autonomousAllowed(context))
            return response(false, "请先到权限与能力中同意角色自主屏幕视觉")
        if (!notificationsAllowed(context))
            return response(false, "学习监督须开启通知，确保随时可见和停止")
        if (!LuluAccessibilityService.observe().connected)
            return response(false, "先开启无障碍服务，角色才能真实查看屏幕")
        val now = System.currentTimeMillis()
        val old = rawActive(context)
        if (old.optString("id").isNotBlank() && old.optLong("endsAt") > now)
            return response(false, "已经有一段学习监督在进行，先结束当前时段")
        val outstanding = pending(context)
        if (outstanding.optString("id").isNotBlank() &&
            now - outstanding.optLong("at") in 0..CONSENT_LIFETIME_MS)
            return response(false, "已有学习监督授权请求等待主人回应")
        val minutes = requestedMinutes.coerceIn(
            (MIN_AGE_MS / 60_000L).toInt(), (MAX_AGE_MS / 60_000L).toInt())
        val id = UUID.randomUUID().toString()
        val payload = JSONObject().put("id", id).put("characterId", role)
            .put("minutes", minutes).put("at", now)
        if (!prefs(context).edit().putString(PENDING, payload.toString()).commit())
            return response(false, "学习监督请求无法保存")
        val manager = context.getSystemService(NotificationManager::class.java)
        val title = "${label(role)}想监督你学习"
        manager.notify(REQUEST_NOTICE, Notification.Builder(context, CHANNEL)
            .setSmallIcon(android.R.drawable.ic_menu_view)
            .setContentTitle(title)
            .setContentText("允许查看屏幕 ${minutes} 分钟 · 随时可停止")
            .setCategory(Notification.CATEGORY_REMINDER)
            .addAction(Notification.Action.Builder(android.R.drawable.ic_menu_view,
                "允许本时段", notificationAction(context, "lulu.study_watch.ALLOW", id)).build())
            .addAction(Notification.Action.Builder(android.R.drawable.ic_menu_close_clear_cancel,
                "拒绝", notificationAction(context, "lulu.study_watch.DENY", id)).build())
            .build())
        LuluScreenRequestPopup.showStudyWatch(context, label(role), minutes, id,
            stillPending = { pending(context).optString("id") == id },
            onAllow = { resolve(context.applicationContext, id, true) },
            onDeny = { resolve(context.applicationContext, id, false) })
        return JSONObject().put("success", true).put("status", "pending")
            .put("summary", "已请求主人允许${minutes}分钟学习监督；还未开始，也没有读取屏幕")
            .toString()
    }

    @Synchronized
    fun resolve(context: Context, id: String, granted: Boolean) {
        val entry = pending(context)
        if (id.isBlank() || entry.optString("id") != id) return
        if (!prefs(context).edit().remove(PENDING).commit()) return
        context.getSystemService(NotificationManager::class.java).cancel(REQUEST_NOTICE)
        LuluScreenRequestPopup.dismiss("study-watch:$id")
        val now = System.currentTimeMillis()
        val accepted = granted &&
            now - entry.optLong("at") in 0..CONSENT_LIFETIME_MS &&
            LuluScreenAwareness.autonomousAllowed(context) &&
            notificationsAllowed(context) && LuluAccessibilityService.observe().connected
        val role = entry.optString("characterId")
        if (!accepted) {
            SharedExperienceTimeline.record("study-watch-denied-$id", role, "屏幕监督授权", "手机",
                "主人未同意这段屏幕监督，未观察画面", source = "study-watch-consent",
                evidenceKind = EventEvidenceKind.ToolResult)
            return
        }
        val session = JSONObject(entry.toString()).put("startedAt", now)
            .put("endsAt", now + entry.optInt("minutes") * 60_000L)
            .put("ordinal", 0)
        if (!prefs(context).edit().putString(ACTIVE, session.toString()).remove(LATEST).commit()) return
        showActiveNotification(context, session)
        SharedExperienceTimeline.record("study-watch-approved-$id", role, "屏幕监督授权", "手机",
            "主人同意${entry.optInt("minutes")}分钟限时监督；有持续通知与停止按钮。截图尚未发生",
            source = "study-watch-consent", evidenceKind = EventEvidenceKind.UserStatement)
        scheduleNext(context, session)
    }

    private fun showActiveNotification(context: Context, session: JSONObject) {
        if (!notificationsAllowed(context)) return
        context.getSystemService(NotificationManager::class.java).notify(
            ACTIVE_NOTICE, Notification.Builder(context, CHANNEL)
                .setSmallIcon(android.R.drawable.ic_menu_view)
                .setContentTitle("${label(session.optString("characterId"))}正在陪你学习")
                .setContentText("期间会不定时查看屏幕 · 随时点「停止」")
                .setCategory(Notification.CATEGORY_SERVICE)
                .setOngoing(true).setOnlyAlertOnce(true)
                .addAction(Notification.Action.Builder(android.R.drawable.ic_menu_close_clear_cancel,
                    "停止监督", notificationAction(context, "lulu.study_watch.STOP",
                        session.optString("id"))).build())
                .build())
    }

    /** Deterministic nonuniform 6–12 minute checks; OS may defer background work. */
    internal fun nextGapMillis(id: String, ordinal: Int): Long {
        val mix = (id + ":" + ordinal).hashCode().toLong() and 0x7fffffffL
        return (6L + mix % 7L) * 60_000L
    }

    @Synchronized
    fun scheduleNext(context: Context, session: JSONObject) {
        val id = session.optString("id")
        if (id.isBlank() || rawActive(context).optString("id") != id) return
        val now = System.currentTimeMillis()
        val remain = session.optLong("endsAt") - now
        if (remain <= 0L) { stop(context, id); return }
        val delay = minOf(nextGapMillis(id, session.optInt("ordinal")), remain)
        val request = OneTimeWorkRequestBuilder<LuluStudyWatchWorker>()
            .setInitialDelay(delay, TimeUnit.MILLISECONDS)
            .setInputData(Data.Builder().putString("sessionId", id).build())
            .build()
        WorkManager.getInstance(context.applicationContext)
            .enqueueUniqueWork("lulu-study-watch-$id", ExistingWorkPolicy.REPLACE, request)
    }

    /** Claim exactly one check, even if WorkManager redelivers an older worker. */
    @Synchronized
    fun claim(context: Context, id: String, now: Long = System.currentTimeMillis()): JSONObject? {
        val session = rawActive(context)
        if (id.isBlank() || session.optString("id") != id ||
            now >= session.optLong("endsAt")) {
            if (session.optString("id") == id) stop(context, id)
            return null
        }
        val next = JSONObject(session.toString()).put("ordinal", session.optInt("ordinal") + 1)
        return if (prefs(context).edit().putString(ACTIVE, next.toString()).commit()) next else null
    }

    @Synchronized
    fun stop(context: Context, id: String = "", reason: String = "监督已结束"): Boolean {
        val session = rawActive(context)
        if (session.optString("id").isBlank() ||
            (id.isNotBlank() && session.optString("id") != id)) return false
        if (!prefs(context).edit().remove(ACTIVE).remove(LATEST).commit()) return false
        WorkManager.getInstance(context.applicationContext)
            .cancelUniqueWork("lulu-study-watch-${session.optString("id")}")
        context.getSystemService(NotificationManager::class.java).cancel(ACTIVE_NOTICE)
        SharedExperienceTimeline.record("study-watch-ended-${session.optString("id")}",
            session.optString("characterId"), "屏幕监督结束", "手机",
            reason, source = "study-watch-consent", evidenceKind = EventEvidenceKind.Observation)
        return true
    }

    /** Latest actual result is a fact supplied to the same original role decision. */
    fun context(context: Context, role: String, now: Instant = Instant.now()): String {
        val session = active(context, role, now.toEpochMilli()) ?: return ""
        val result = parse(prefs(context).getString(LATEST, "{}"))
        return buildString {
            appendLine("【主人亲自授权的限时学习监督】")
            appendLine("学习监督已同意；到期=${Instant.ofEpochMilli(session.optLong("endsAt"))}。这是用户主动开启的学习陪伴，不是永久监控，不要把一个游戏画面就当成逃避学习的定论。")
            if (result.optString("sessionId") == session.optString("id") &&
                now.toEpochMilli() - result.optLong("at") in 0..300_000L) {
                appendLine("本次真实屏幕观察结果：${result.optString("summary").take(1250)}")
                appendLine("观察成功=${result.optBoolean("success")}。失败、锁屏、隐私页面不代表用户没学习，不要编造看到的东西。")
            }
            appendLine("你可以根据真实结果自主选择温柔提醒、打趣、鼓励、保持安静或停止监督。与用户说话仍走原私聊/电话动作，不直接把程序报告当台词；重复同一句催促没有必要。")
        }
    }

    @Synchronized
    fun recordCheck(context: Context, id: String, result: JSONObject, now: Instant = Instant.now()) {
        if (rawActive(context).optString("id") != id) return
        prefs(context).edit().putString(LATEST, JSONObject()
            .put("sessionId", id).put("at", now.toEpochMilli())
            .put("success", result.optBoolean("success"))
            .put("summary", if (result.optBoolean("success"))
                result.optString("visibleDescription") else result.optString("error"))
            .toString()).commit()
    }

    private fun response(success: Boolean, message: String): String =
        JSONObject().put("success", success).put("status", if (success) "succeeded" else "failed")
            .put(if (success) "summary" else "error", message).toString()
}

class LuluStudyWatchReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val id = intent.getStringExtra("sessionId").orEmpty()
        when (intent.action) {
            "lulu.study_watch.ALLOW" -> LuluStudyWatch.resolve(context, id, true)
            "lulu.study_watch.DENY" -> LuluStudyWatch.resolve(context, id, false)
            "lulu.study_watch.STOP" -> LuluStudyWatch.stop(context, id, "主人主动停止监督")
        }
    }
}

class LuluStudyWatchWorker(context: Context, params: WorkerParameters) :
    CoroutineWorker(context, params) {
    override suspend fun doWork(): Result {
        val id = inputData.getString("sessionId").orEmpty()
        if (id.isBlank()) return Result.success()
        initializeBackgroundRuntime(applicationContext)
        val session = LuluStudyWatch.claim(applicationContext, id) ?: return Result.success()
        val role = session.optString("characterId")
        try {
            if (ProactivePerceptionPolicyStore.get(role).enabled &&
                LuluScreenAwareness.autonomousAllowed(applicationContext) &&
                LuluStudyWatch.active(applicationContext, role) != null) {
                val raw = LuluScreenAwareness.capture(applicationContext, userRequested = false,
                    characterId = role, requestId = "study-watch-$id-${session.optInt("ordinal")}",
                    approved = true, sessionId = id)
                if (LuluStudyWatch.active(applicationContext, role)?.optString("id") != id)
                    return Result.success()
                val observed = runCatching { JSONObject(raw) }.getOrDefault(
                    JSONObject().put("success", false).put("error", "截屏结果无法解析"))
                LuluStudyWatch.recordCheck(applicationContext, id, observed)
                val evidenceId = "study-watch-observation-$id-${session.optInt("ordinal")}"
                SharedExperienceTimeline.record(evidenceId, role,
                    if (observed.optBoolean("success")) "屏幕观察" else "屏幕观察失败",
                    "手机", observed.toString(), source = "study-watch",
                    evidenceKind = if (observed.optBoolean("success"))
                        EventEvidenceKind.Observation else EventEvidenceKind.ToolResult)
                // No vision result => no invented judgment and no extra paid role call.
                if (observed.optBoolean("success") && LuluStudyWatch.active(applicationContext, role) != null) {
                    ProactivePerceptionRuntime.runDueCycle(applicationContext,
                        trigger = "已授权的学习监督实际看到一帧屏幕，请根据证据自己决定是否需要交流",
                        targetCharacterId = role, force = true)
                }
            } else if (!LuluScreenAwareness.autonomousAllowed(applicationContext)) {
                LuluStudyWatch.stop(applicationContext, id, "屏幕视觉授权已关闭，自动结束监督")
            }
        } catch (cancel: kotlinx.coroutines.CancellationException) {
            throw cancel
        } catch (failure: Exception) {
            android.util.Log.w("LuluStudyWatch", "Supervision check failed; preserve existing session", failure)
        } finally {
            // A check never extends its owner's original session end.
            LuluStudyWatch.active(applicationContext, role)?.let {
                LuluStudyWatch.scheduleNext(applicationContext, it)
            } ?: LuluStudyWatch.stop(applicationContext, id, "学习监督时段已结束")
        }
        return Result.success()
    }
}
