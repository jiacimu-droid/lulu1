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
        check(canScheduleExact()) { "尚未获得精确闹钟权限" }
        val alarm = LuluAlarm(
            characterId = characterId.ifBlank { "lulu" },
            characterName = characterName.ifBlank { "露露" },
            triggerAt = triggerAt,
            label = label.trim().ifBlank { "该起床啦" },
        )
        val alarms = list(appContext).filterNot { it.id == alarm.id } + alarm
        save(appContext, alarms)
        schedule(appContext, alarm)
        alarm
    }

    fun list(): List<LuluAlarm> = context?.let(::list).orEmpty().sortedBy { it.triggerAt }

    fun cancel(id: String): Boolean {
        val appContext = context ?: return false
        val alarms = list(appContext)
        val target = alarms.firstOrNull { it.id == id } ?: return false
        val manager = appContext.getSystemService(AlarmManager::class.java)
        manager.cancel(pendingIntent(appContext, target))
        save(appContext, alarms.filterNot { it.id == id })
        return true
    }

    internal fun markTriggered(context: Context, id: String) {
        save(context, list(context).filterNot { it.id == id })
    }

    internal fun restoreFutureAlarms(context: Context) {
        val now = Instant.now()
        val alarms = list(context)
        alarms.filter { it.triggerAt.isAfter(now) }.forEach { alarm ->
            if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S || context.getSystemService(AlarmManager::class.java).canScheduleExactAlarms()) {
                schedule(context, alarm)
            }
        }
        save(context, alarms.filter { it.triggerAt.isAfter(now) })
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
