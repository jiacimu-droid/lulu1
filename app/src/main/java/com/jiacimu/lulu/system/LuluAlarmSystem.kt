package com.jiacimu.lulu.system

import android.app.AlarmManager
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.provider.AlarmClock
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.media.AudioAttributes
import android.media.RingtoneManager
import android.os.Build
import androidx.core.app.NotificationCompat
import com.jiacimu.lulu.MigrationActivity
import org.json.JSONArray
import org.json.JSONObject
import java.time.Instant
import java.time.temporal.ChronoUnit
import java.util.UUID

data class LuluAlarm(
    val id: String = UUID.randomUUID().toString(),
    val characterId: String,
    val characterName: String,
    val triggerAt: Instant,
    val label: String,
    val createdAt: Instant = Instant.now(),
)

object LuluAlarmSystem {
    private const val PREFS_NAME = "lulu_alarm_store"
    private const val KEY_ALARMS = "alarms_v1"
    private const val CHANNEL_ID = "lulu_alarm"
    private var context: Context? = null

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

    fun create(
        characterId: String,
        characterName: String,
        triggerAt: Instant,
        label: String,
    ): Result<LuluAlarm> = runCatching {
        val appContext = context ?: error("闹钟系统尚未初始化")
        require(triggerAt.isAfter(Instant.now().plusSeconds(5))) { "闹钟时间必须晚于当前时间" }
        val alarm = LuluAlarm(
            characterId = characterId.ifBlank { "lulu" },
            characterName = characterName.ifBlank { "露露" },
            triggerAt = triggerAt,
            label = label.trim().ifBlank { "该起床啦" },
        )
        setSystemClockAlarm(appContext, alarm)
        save(appContext, list(appContext).filterNot { it.id == alarm.id } + alarm)
        alarm
    }

    fun list(): List<LuluAlarm> {
        val appContext = context ?: return emptyList()
        val future = list(appContext).filter { it.triggerAt.isAfter(Instant.now()) }.sortedBy { it.triggerAt }
        save(appContext, future)
        return future
    }

    fun cancel(id: String): Boolean {
        val appContext = context ?: return false
        val alarms = list(appContext)
        val target = alarms.firstOrNull { it.id == id } ?: return false
        dismissSystemClockAlarm(appContext, target)
        save(appContext, alarms.filterNot { it.id == id })
        return true
    }

    internal fun markTriggered(context: Context, id: String) {
        save(context, list(context).filterNot { it.id == id })
    }

    internal fun restoreFutureAlarms(context: Context) {
        val now = Instant.now()
        val alarms = list(context)
        // 系统时钟应用会自行持久化闹钟；这里只清理露露保存的过期索引，避免重复创建。
        save(context, alarms.filter { it.triggerAt.isAfter(now) })
    }

    private fun setSystemClockAlarm(context: Context, alarm: LuluAlarm) {
        val localTime = alarm.triggerAt.atZone(java.time.ZoneId.systemDefault())
        val now = java.time.ZonedDateTime.now()
        var nextAtSameTime = now.withHour(localTime.hour).withMinute(localTime.minute).withSecond(0).withNano(0)
        if (!nextAtSameTime.isAfter(now)) nextAtSameTime = nextAtSameTime.plusDays(1)
        val requested = localTime.withSecond(0).withNano(0)
        require(kotlin.math.abs(ChronoUnit.MINUTES.between(nextAtSameTime, requested)) <= 1) {
            "手机系统时钟只能直接创建未来 24 小时内下一次出现的时刻"
        }
        val intent = Intent(AlarmClock.ACTION_SET_ALARM).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK
            putExtra(AlarmClock.EXTRA_HOUR, localTime.hour)
            putExtra(AlarmClock.EXTRA_MINUTES, localTime.minute)
            putExtra(AlarmClock.EXTRA_MESSAGE, systemLabel(alarm))
            putExtra(AlarmClock.EXTRA_VIBRATE, true)
            putExtra(AlarmClock.EXTRA_SKIP_UI, true)
        }
        try {
            context.startActivity(intent)
        } catch (_: android.content.ActivityNotFoundException) {
            error("手机里没有支持创建系统闹钟的时钟应用")
        }
    }

    private fun dismissSystemClockAlarm(context: Context, alarm: LuluAlarm) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.M) return
        val intent = Intent(AlarmClock.ACTION_DISMISS_ALARM).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK
            putExtra(AlarmClock.EXTRA_ALARM_SEARCH_MODE, AlarmClock.ALARM_SEARCH_MODE_LABEL)
            putExtra(AlarmClock.EXTRA_MESSAGE, systemLabel(alarm))
        }
        runCatching { context.startActivity(intent) }
    }

    private fun systemLabel(alarm: LuluAlarm): String = "${alarm.characterName} · ${alarm.label}"

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
                        .put("createdAt", alarm.createdAt.toString()),
                )
            }
        }
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .edit().putString(KEY_ALARMS, array.toString()).apply()
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
        val characterId = intent.getStringExtra("character_id").orEmpty().ifBlank { "lulu" }
        val characterName = intent.getStringExtra("character_name").orEmpty().ifBlank { "露露" }
        val label = intent.getStringExtra("label").orEmpty().ifBlank { "该起床啦" }
        LuluAlarmSystem.markTriggered(context, id)
        LuluAlarmSystem.showAlarmNotification(context, id, characterId, characterName, label)
    }
}

class LuluBootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action == Intent.ACTION_BOOT_COMPLETED || intent.action == Intent.ACTION_MY_PACKAGE_REPLACED) {
            LuluAlarmSystem.initialize(context.applicationContext)
        }
    }
}
