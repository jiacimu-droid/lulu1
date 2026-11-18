package com.jiacimu.lulu

import android.Manifest
import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.BackoffPolicy
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import com.jiacimu.lulu.ai.CompanionContextMode
import com.jiacimu.lulu.ai.LuluAiServices
import com.jiacimu.lulu.ai.ModelUsage
import com.jiacimu.lulu.data.MigratedDomainStores
import com.jiacimu.lulu.data.initializeBackgroundRuntime
import java.time.Duration
import java.time.LocalDate
import java.time.Instant
import java.time.ZoneId
import java.time.ZonedDateTime
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import org.json.JSONObject

/** A real full-character model response, not template role speech. */
internal data class LuluWidgetDailyReading(
    val date: String,
    val sign: Int,
    val characterId: String,
    val message: String,
    val wantsNotify: Boolean,
    val chosenSkin: Int,
)

internal object LuluWidgetDailyRole {
    private const val PREF = "lulu_desktop_widget_daily_role_v1"
    private const val WORK = "lulu-widget-daily-character-midnight"
    private const val OLD_WORK = "lulu-widget-daily-character"
    private const val CHANNEL = "lulu-widget-daily"
    private val gate = Mutex()
    private val changes = MutableStateFlow(0L)
    val revision = changes.asStateFlow()

    private fun fingerprint(widget: LuluDesktopWidget, date: LocalDate): String =
        date.toString() + "|" + widget.sign + "|" + widget.characterId

    fun read(context: Context, widget: LuluDesktopWidget, date: LocalDate): LuluWidgetDailyReading? {
        if (widget.kind != LuluWidgetKind.HOROSCOPE ||
            !widget.dailyReadingEnabled || widget.characterId.isBlank()) return null
        val raw = context.getSharedPreferences(PREF, Context.MODE_PRIVATE)
            .getString("reading_" + fingerprint(widget, date), null) ?: return null
        return runCatching {
            val obj = JSONObject(raw)
            if (obj.optString("fingerprint") != fingerprint(widget, date)) return@runCatching null
            val message = obj.optString("message").trim()
            if (message.isBlank()) return@runCatching null
            LuluWidgetDailyReading(date.toString(), widget.sign, widget.characterId,
                message, obj.optBoolean("notify", false), obj.optInt("skin", -1))
        }.getOrNull()
    }

    suspend fun refresh(context: Context, widget: LuluDesktopWidget, date: LocalDate = LocalDate.now(), manual: Boolean = false) =
        gate.withLock {
            if (widget.kind != LuluWidgetKind.HOROSCOPE || !widget.dailyReadingEnabled ||
                widget.characterId.isBlank()) return@withLock
            val app = context.applicationContext
            val current = LuluDesktopWidgetStore.load(app)
                .firstOrNull { it.id == widget.id } ?: return@withLock
            if (current.characterId != widget.characterId || current.sign != widget.sign ||
                !current.dailyReadingEnabled) return@withLock
            read(app, current, date)?.let {
                if (current.allowRoleStyle && it.chosenSkin in 0..3) {
                    LuluDesktopWidgetStore.roleApplySkin(
                        app, current.id, current.characterId, it.chosenSkin)
                }
                deliverIfConsented(app, current, it)
                return@withLock
            }
            val prefs = app.getSharedPreferences(PREF, Context.MODE_PRIVATE)
            val fp = fingerprint(current, date)
            val oldAttempt = prefs.getString("attempt_" + fp, "")
            val oldTime = prefs.getLong("attemptAt_" + fp, 0L)
            if (!manual && oldAttempt == fp && System.currentTimeMillis() - oldTime < 3600_000L) return@withLock
            if (!MigratedDomainStores.characters.settings.value.containsKey(current.characterId))
                return@withLock
            // Durable retry cooldown prevents navigation loops from repeatedly paying for Full inputs.
            if (!prefs.edit().putString("attempt_" + fp, fp)
                    .putLong("attemptAt_" + fp, System.currentTimeMillis()).commit())
                return@withLock
            val (good, caution) = luluDailyFortune(date, current.sign)
            val answer = try {
                LuluAiServices.initialize(app)
                LuluAiServices.gateway.generate(
                    characterId = current.characterId,
                    facts = "【露露机桌面星座娱乐卡，不是主人新的聊天消息】\n" +
                        "今天的本机日期：" + date + "；主人设置的星座：" +
                        luluZodiacNames[current.sign] + "；娱乐性宜：" + good +
                        "；娱乐性忌：" + caution + "。\n" +
                        "允许通知：" + current.dailyNotifyEnabled +
                        "；允许角色装修：" + current.allowRoleStyle +
                        "。候选皮肤0星夜/1植物信纸/2樱粉/3像素夜空/4桌面原皮/5错落信笺/6相册叠片。" +
                        "这并不证明主人已经执行了任何行为。",
                    instruction = """
你是现有的这个数字生命本人；沿用与私聊相同的人设、记忆、经历和关系。
主人把你绑定到每日星座小卡，希望每天看到你对娱乐性今日运势的自然解读。
根据你自己真实的兴趣和对主人的了解，写一小段不超过90字的话，
可以建议做什么和避免什么，但不要编造今天已观察到的行为，
不要断言星座能够预测未来，也不要替所有角色使用一种统一口吻。
是否发一次通知由你自己选；可以不通知，用户开关会额外决定最终权限。
如果用户授权自由装修，你可以选皮肤0至6，或者-1表示保持当前皮肤。
只返回JSON：{"message":"你本人写的短话","notify":false,"skin":-1}。
notify是布尔值，skin只能是-1或0/1/2/3。生成文字不代表通知已送达。
""".trimIndent(),
                    source = "露露机 · 每日星座小组件",
                    capturePrivateMoment = true,
                    title = "角色桌面今日解读",
                    usage = ModelUsage.Chat,
                    contextMode = CompanionContextMode.Full,
                    maxTokens = 240,
                ).getOrNull()
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                null
            } ?: return@withLock
            val parsed = parse(date, current, answer.text) ?: return@withLock
            val latest = LuluDesktopWidgetStore.load(app).firstOrNull { it.id == current.id }
                ?: return@withLock
            if (fingerprint(latest, date) != fp || !latest.dailyReadingEnabled ||
                LocalDate.now() != date) return@withLock
            val stored = prefs.edit().putString("reading_" + fp, JSONObject()
                .put("fingerprint", fp).put("message", parsed.message)
                .put("notify", parsed.wantsNotify).put("skin", parsed.chosenSkin)
                .toString()).commit()
            if (!stored) return@withLock
            changes.value += 1L
            com.jiacimu.lulu.data.CharacterObservedMoment.recordScene(
                current.characterId, answer, "桌面组件", current.id, fp,
                "本机日期 $date，角色看到已绑定的每日星座组件；用户设置的星座为${luluZodiacNames[current.sign]}。",
                parsed.message,
            )
            if (latest.allowRoleStyle && parsed.chosenSkin in 0..3) {
                LuluDesktopWidgetStore.roleApplySkin(
                    app, latest.id, latest.characterId, parsed.chosenSkin)
            }
            deliverIfConsented(app, latest, parsed)
        }

    internal fun parse(date: LocalDate, widget: LuluDesktopWidget, raw: String): LuluWidgetDailyReading? =
        runCatching {
            val start = raw.indexOf('{')
            val end = raw.lastIndexOf('}')
            if (start < 0 || end <= start) return@runCatching null
            val obj = JSONObject(raw.substring(start, end + 1))
            val message = obj.optString("message").trim().take(90)
            if (message.isBlank() || obj.opt("notify") !is Boolean ||
                obj.opt("skin") !is Number) return@runCatching null
            val skin = obj.getInt("skin")
            if (skin != -1 && !isLuluWidgetSkin(skin)) return@runCatching null
            LuluWidgetDailyReading(date.toString(), widget.sign, widget.characterId,
                message, obj.getBoolean("notify"), skin)
        }.getOrNull()

    private fun deliverIfConsented(
        context: Context, widget: LuluDesktopWidget, reading: LuluWidgetDailyReading,
    ) {
        if (!widget.dailyNotifyEnabled || !reading.wantsNotify ||
            reading.message.isBlank() || reading.date != LocalDate.now().toString()) return
        if (Build.VERSION.SDK_INT >= 33 &&
            ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) !=
                PackageManager.PERMISSION_GRANTED) return
        if (!NotificationManagerCompat.from(context).areNotificationsEnabled()) return
        val current = LuluDesktopWidgetStore.load(context)
            .firstOrNull { it.id == widget.id } ?: return
        if (current.characterId != reading.characterId || current.sign != reading.sign ||
            !current.dailyNotifyEnabled || !current.dailyReadingEnabled) return
        val prefs = context.getSharedPreferences(PREF, Context.MODE_PRIVATE)
        val notificationKey = reading.date + "|" + reading.characterId + "|" + reading.sign
        if (prefs.getString("notified_" + notificationKey, "") == "sent") return
        val name = MigratedDomainStores.characters.settings.value[reading.characterId]
            ?.displayName ?: return
        val manager = context.getSystemService(NotificationManager::class.java) ?: return
        if (Build.VERSION.SDK_INT >= 26) {
            manager.createNotificationChannel(NotificationChannel(
                CHANNEL, "露露机 · 角色每日运势", NotificationManager.IMPORTANCE_DEFAULT))
        }
        val intent = Intent(context, MigrationActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP
        }
        val pending = android.app.PendingIntent.getActivity(
            context, widget.id.hashCode(), intent,
            android.app.PendingIntent.FLAG_UPDATE_CURRENT or android.app.PendingIntent.FLAG_IMMUTABLE,
        )
        val notice = NotificationCompat.Builder(context, CHANNEL)
            .setSmallIcon(android.R.drawable.ic_menu_today)
            .setContentTitle(name + " · 今天的星座小纸条")
            .setContentText(reading.message)
            .setStyle(NotificationCompat.BigTextStyle().bigText(reading.message))
            .setAutoCancel(true).setContentIntent(pending).build()
        try {
            NotificationManagerCompat.from(context).notify(widget.id.hashCode(), notice)
            prefs.edit().putString("notified_" + notificationKey, "sent").commit()
        } catch (_: SecurityException) {
            // A denied notification is not a successful delivery.
        }
    }

    // Each local calendar day begins at midnight. WorkManager is best-effort:
    // device sleep, Doze and network restrictions may delay actual execution.
    internal fun plannedDate(now: ZonedDateTime, needsReadingToday: Boolean): LocalDate =
        if (needsReadingToday) now.toLocalDate() else now.toLocalDate().plusDays(1)

    internal fun scheduledAt(date: LocalDate, zone: ZoneId): Instant =
        date.atStartOfDay(zone).toInstant()

    private fun enqueueMidnight(context: Context, date: LocalDate) {
        val app = context.applicationContext
        val dueAt = scheduledAt(date, ZoneId.systemDefault())
        val delayMillis = Duration.between(java.time.Instant.now(), dueAt).toMillis()
            .coerceAtLeast(0L)
        val request = OneTimeWorkRequestBuilder<LuluWidgetDailyWorker>()
            .addTag(WORK)
            .setInitialDelay(delayMillis, TimeUnit.MILLISECONDS)
            .setBackoffCriteria(BackoffPolicy.LINEAR, 65, TimeUnit.MINUTES)
            .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build())
            .build()
        WorkManager.getInstance(app).enqueueUniqueWork(
            "$WORK-$date", ExistingWorkPolicy.KEEP, request,
        )
    }

    /** Saving/enabling a card or reopening the desktop is a schedule check,
     * not another direct AI call. Late app starts catch up on today's missing cards. */
    fun schedule(context: Context, widgets: List<LuluDesktopWidget>) {
        val active = widgets.filter { it.kind == LuluWidgetKind.HOROSCOPE &&
            it.dailyReadingEnabled && it.characterId.isNotBlank() }
        val manager = WorkManager.getInstance(context.applicationContext)
        // Retire the previous 08:00 work when upgrading. Its already-enqueued
        // task would otherwise keep waking up at the old time.
        manager.cancelAllWorkByTag(OLD_WORK)
        manager.cancelUniqueWork(OLD_WORK) // Pre-morning 24-hour periodic worker
        if (active.isEmpty()) {
            manager.cancelAllWorkByTag(WORK)
            return
        }
        val now = ZonedDateTime.now(ZoneId.systemDefault())
        val needsReadingToday = active.any { read(context, it, now.toLocalDate()) == null }
        enqueueMidnight(context, plannedDate(now, needsReadingToday))
    }

    /** Each successful or exhausted daily run schedules the next calendar midnight. */
    internal fun scheduleNextMidnight(context: Context, today: LocalDate) {
        enqueueMidnight(context, today.plusDays(1))
    }
}

class LuluWidgetDailyWorker(context: Context, params: WorkerParameters) :
    CoroutineWorker(context, params) {
    override suspend fun doWork(): Result = withContext(Dispatchers.IO) {
        try {
            initializeBackgroundRuntime(applicationContext)
            val today = LocalDate.now()
            val cards = LuluDesktopWidgetStore.load(applicationContext)
                .filter { it.kind == LuluWidgetKind.HOROSCOPE &&
                    it.dailyReadingEnabled && it.characterId.isNotBlank() }
                .distinctBy { it.characterId to it.sign }
            cards.forEach { LuluWidgetDailyRole.refresh(applicationContext, it, today) }
            val incomplete = cards.any {
                LuluWidgetDailyRole.read(applicationContext, it, today) == null
            }
            // Failed/invalid model output is never treated as a real reading.
            // Two bounded retries, one hour apart, rather than paid polling.
            if (incomplete && runAttemptCount < 2) {
                Result.retry()
            } else {
                if (cards.isNotEmpty())
                    LuluWidgetDailyRole.scheduleNextMidnight(applicationContext, today)
                Result.success()
            }
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            if (runAttemptCount < 2) Result.retry() else {
                // A process/network fault today should not permanently stop tomorrow's card.
                if (LuluDesktopWidgetStore.load(applicationContext).any {
                        it.kind == LuluWidgetKind.HOROSCOPE &&
                            it.dailyReadingEnabled && it.characterId.isNotBlank()
                    })
                    LuluWidgetDailyRole.scheduleNextMidnight(applicationContext, LocalDate.now())
                Result.success()
            }
        }
    }
}
