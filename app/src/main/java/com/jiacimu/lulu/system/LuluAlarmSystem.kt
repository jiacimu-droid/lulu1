package com.jiacimu.lulu.system

import android.app.AlarmManager
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.media.AudioAttributes
import android.media.RingtoneManager
import android.os.Build
import androidx.core.app.NotificationCompat
import com.jiacimu.lulu.MigrationActivity
import com.jiacimu.lulu.data.CommitmentAlarmTiming
import com.jiacimu.lulu.data.CommitmentTaskStore
import androidx.core.content.ContextCompat
import org.json.JSONArray
import org.json.JSONObject
import java.time.Instant
import java.util.UUID

data class LuluAlarm(
    val id: String = UUID.randomUUID().toString(),
    val characterId: String,
    val characterName: String,
    val triggerAt: Instant,
    val label: String,
    val createdAt: Instant = Instant.now(),
    val silentCallback: Boolean = false,
    val commitmentTaskId: String? = null,
)

object LuluAlarmSystem {
    private const val PREFS_NAME = "lulu_alarm_store"
    private const val KEY_ALARMS = "alarms_v1"
    private const val CHANNEL_ID = "lulu_alarm"
    private var context: Context? = null

    @Synchronized
    fun initialize(appContext: Context) {
        context = appContext.applicationContext
        createChannel(appContext)
        restoreFutureAlarms(appContext)
    }

    fun canScheduleExact(): Boolean {
        val appContext = context ?: return false
        val manager = appContext.getSystemService(AlarmManager::class.java)
        return Build.VERSION.SDK_INT < Build.VERSION_CODES.S || manager.canScheduleExactAlarms()
    }

    @Synchronized
    fun create(
        characterId: String,
        characterName: String,
        triggerAt: Instant,
        label: String,
        id: String = UUID.randomUUID().toString(),
        silentCallback: Boolean = false,
        commitmentTaskId: String? = null,
    ): Result<LuluAlarm> = runCatching {
        val appContext = context ?: error("闹钟系统尚未初始化")
        list(appContext).firstOrNull { it.id == id }?.let { existing ->
            require(existing.characterId == characterId && existing.triggerAt == triggerAt && existing.label == label.trim()) { "闹钟执行ID冲突" }
            return@runCatching existing
        }
        require(triggerAt.isAfter(Instant.now().plusSeconds(5))) { "闹钟时间必须晚于当前时间" }
        val alarm = LuluAlarm(
            id = id,
            characterId = characterId.ifBlank { "lulu" },
            characterName = characterName.ifBlank { "露露" },
            triggerAt = triggerAt,
            label = label.trim().ifBlank { "该起床啦" },
            silentCallback = silentCallback,
            commitmentTaskId = commitmentTaskId,
        )
        register(appContext, alarm)
        save(appContext, list(appContext).filterNot { it.id == alarm.id } + alarm)
        CommitmentAlarmDelivery.scheduleBackup(appContext, alarm)
        alarm
    }

    fun list(): List<LuluAlarm> {
        val appContext = context ?: return emptyList()
        // A read must not erase the one-shot token while its OS callback is being delivered.
        return list(appContext).filter { it.triggerAt.isAfter(Instant.now()) }.sortedBy { it.triggerAt }
    }

    @Synchronized
    fun cancel(id: String): Boolean {
        val appContext = context ?: return false
        val alarms = list(appContext)
        val target = alarms.firstOrNull { it.id == id } ?: return false
        appContext.getSystemService(AlarmManager::class.java).cancel(pendingIntent(appContext, target))
        save(appContext, alarms.filterNot { it.id == id })
        CommitmentAlarmDelivery.cancelBackup(appContext, id)
        return true
    }

    internal fun find(context: Context, id: String): LuluAlarm? = list(context).firstOrNull { it.id == id }

    /** Receiver and durable fallback race for the same persisted token, never two calls. */
    @Synchronized
    internal fun takeForDelivery(context: Context, id: String, now: Instant): LuluAlarm? {
        val alarms = list(context)
        val alarm = alarms.firstOrNull { it.id == id } ?: return null
        if (alarm.triggerAt.isAfter(now)) return null
        save(context, alarms.filterNot { it.id == id })
        return alarm
    }

    @Synchronized
    internal fun restoreFutureAlarms(context: Context) {
        val now = Instant.now()
        // Briefly due alarms may be the reason this process was started. Re-registering their
        // original timestamp asks Android to dispatch now; no Activity must be required first.
        list(context).filterNot { CommitmentAlarmTiming.expired(it.triggerAt, now) }.forEach { alarm ->
            runCatching { register(context, alarm) }
                .onFailure { android.util.Log.w("LuluAlarm", "Alarm restore failed: ${alarm.id}", it) }
            CommitmentAlarmDelivery.scheduleBackup(context, alarm)
        }
    }

    private fun register(context: Context, alarm: LuluAlarm) {
        if (!canScheduleExact()) scheduleBestEffortCallback(context, alarm)
        // These are user-facing, accepted wake/reminder commitments. "Silent" only suppresses
        // the duplicate generic notification, not precise wake delivery. Allow-while-idle alarms
        // can be throttled beyond the promised five-minute wake retry while the phone dozes.
        else schedule(context, alarm)
    }

    private fun scheduleBestEffortCallback(context: Context, alarm: LuluAlarm) {
        val manager = context.getSystemService(AlarmManager::class.java)
        manager.setAndAllowWhileIdle(
            AlarmManager.RTC_WAKEUP,
            alarm.triggerAt.toEpochMilli(),
            pendingIntent(context, alarm),
        )
    }

    private fun schedule(context: Context, alarm: LuluAlarm) {
        val manager = context.getSystemService(AlarmManager::class.java)
        val operation = pendingIntent(context, alarm)
        val showIntent = PendingIntent.getActivity(
            context,
            alarm.id.hashCode(),
            Intent(context, MigrationActivity::class.java).apply {
                flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
                putExtra("open_conversation_id", alarm.characterId)
                putExtra("alarm_id", alarm.id)
            },
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        val info = AlarmManager.AlarmClockInfo(alarm.triggerAt.toEpochMilli(), showIntent)
        manager.setAlarmClock(info, operation)
    }

    private fun pendingIntent(context: Context, alarm: LuluAlarm): PendingIntent = PendingIntent.getBroadcast(
        context,
        alarm.id.hashCode(),
        Intent(context, LuluAlarmReceiver::class.java).apply {
            action = "app.lulu.ALARM_TRIGGER"
            putExtra("alarm_id", alarm.id)
            putExtra("character_id", alarm.characterId)
            putExtra("character_name", alarm.characterName)
            putExtra("label", alarm.label)
            putExtra("silent_callback", alarm.silentCallback)
        },
        PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
    )

    private fun list(context: Context): List<LuluAlarm> {
        val raw = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE).getString(KEY_ALARMS, null)
            ?: return emptyList()
        return runCatching {
            val array = JSONArray(raw)
            buildList {
                for (index in 0 until array.length()) {
                    val item = array.optJSONObject(index) ?: continue
                    val trigger = runCatching { Instant.parse(item.optString("triggerAt")) }.getOrNull() ?: continue
                    add(
                        LuluAlarm(
                            id = item.optString("id").ifBlank { UUID.randomUUID().toString() },
                            characterId = item.optString("characterId").ifBlank { "lulu" },
                            characterName = item.optString("characterName").ifBlank { "露露" },
                            triggerAt = trigger,
                            label = item.optString("label").ifBlank { "该起床啦" },
                            createdAt = runCatching { Instant.parse(item.optString("createdAt")) }.getOrDefault(Instant.now()),
                            silentCallback = item.optBoolean("silentCallback", false),
                            commitmentTaskId = item.optString("commitmentTaskId").takeIf { it.isNotBlank() && it != "null" },
                        ),
                    )
                }
            }
        }.getOrDefault(emptyList())
    }

    private fun save(context: Context, alarms: List<LuluAlarm>) {
        val array = JSONArray().apply {
            alarms.forEach { alarm ->
                put(
                    JSONObject()
                        .put("id", alarm.id)
                        .put("characterId", alarm.characterId)
                        .put("characterName", alarm.characterName)
                        .put("triggerAt", alarm.triggerAt.toString())
                        .put("label", alarm.label)
                        .put("createdAt", alarm.createdAt.toString())
                        .put("silentCallback", alarm.silentCallback)
                        .put("commitmentTaskId", alarm.commitmentTaskId ?: JSONObject.NULL),
                )
            }
        }
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .edit().putString(KEY_ALARMS, array.toString()).commit().also { check(it) { "闹钟记录未能保存" } }
    }

    private fun createChannel(context: Context) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val sound = RingtoneManager.getDefaultUri(RingtoneManager.TYPE_ALARM)
        val attributes = AudioAttributes.Builder()
            .setUsage(AudioAttributes.USAGE_ALARM)
            .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
            .build()
        val channel = NotificationChannel(CHANNEL_ID, "露露闹钟", NotificationManager.IMPORTANCE_HIGH).apply {
            description = "角色设置的叫醒、提醒与监督闹钟"
            enableVibration(true)
            setSound(sound, attributes)
            lockscreenVisibility = android.app.Notification.VISIBILITY_PUBLIC
        }
        context.getSystemService(NotificationManager::class.java).createNotificationChannel(channel)
    }

    internal fun showAlarmNotification(context: Context, alarmId: String, characterId: String, characterName: String, label: String) {
        createChannel(context)
        val openIntent = PendingIntent.getActivity(
            context,
            alarmId.hashCode(),
            Intent(context, MigrationActivity::class.java).apply {
                flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
                putExtra("open_conversation_id", characterId)
                putExtra("alarm_id", alarmId)
            },
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        val notification = NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.ic_lock_idle_alarm)
            .setContentTitle("$characterName 在叫你")
            .setContentText(label)
            .setStyle(NotificationCompat.BigTextStyle().bigText(label))
            .setContentIntent(openIntent)
            .setFullScreenIntent(openIntent, true)
            .setCategory(NotificationCompat.CATEGORY_ALARM)
            .setPriority(NotificationCompat.PRIORITY_MAX)
            .setAutoCancel(true)
            .setOngoing(false)
            .setVibrate(longArrayOf(0, 500, 250, 500, 250, 800))
            .setSound(RingtoneManager.getDefaultUri(RingtoneManager.TYPE_ALARM))
            .build()
        context.getSystemService(NotificationManager::class.java).notify(alarmId.hashCode(), notification)
    }
}

class LuluAlarmReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val id = intent.getStringExtra("alarm_id").orEmpty()
        if (id.isBlank() || LuluAlarmSystem.find(context, id) == null) return
        // Exact alarms may start this short foreground service from a cold/background process.
        // It has no microphone dependency; the microphone service starts only after answering.
        val started = runCatching {
            ContextCompat.startForegroundService(context,
                Intent(context, CommitmentAlarmService::class.java).putExtra("alarm_id", id))
        }.isSuccess
        if (!started) {
            // Inexact alarms may not have foreground-start privileges. A persistent expedited
            // worker is the fallback, rather than a coroutine that outlives BroadcastReceiver.
            CommitmentAlarmDelivery.enqueueNow(context, id)
        }
    }
}

class LuluBootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action in setOf(Intent.ACTION_BOOT_COMPLETED, Intent.ACTION_MY_PACKAGE_REPLACED,
                AlarmManager.ACTION_SCHEDULE_EXACT_ALARM_PERMISSION_STATE_CHANGED,
                Intent.ACTION_TIME_CHANGED, Intent.ACTION_TIMEZONE_CHANGED)) {
            LuluAlarmSystem.initialize(context.applicationContext)
            CommitmentTaskStore.initialize(context.applicationContext)
            CommitmentTaskStore.reconcileAfterRestart()
        }
    }
}
