package com.jiacimu.lulu.health

import android.app.AlarmManager
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Build
import androidx.core.app.NotificationCompat
import com.jiacimu.lulu.MigrationActivity
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import org.json.JSONArray
import org.json.JSONObject
import java.time.Instant
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneId
import java.time.temporal.ChronoUnit
import java.util.UUID
import kotlin.math.roundToInt

internal data class PeriodRecord(
    val id: String,
    val startDate: LocalDate,
    val endDate: LocalDate,
)

internal data class HealthCycleState(
    val records: List<PeriodRecord> = emptyList(),
    val reminderEnabled: Boolean = true,
    val reminderDaysBefore: Int = 3,
)

internal data class PeriodPrediction(
    val startDate: LocalDate,
    val endDate: LocalDate,
    val cycleDays: Int,
    val periodDays: Int,
)

internal object HealthCycleStore {
    private const val PREFS_NAME = "lulu_health_cycle"
    private const val KEY_STATE = "cycle_state_v1"

    private val mutableState = MutableStateFlow(HealthCycleState())
    val state: StateFlow<HealthCycleState> = mutableState.asStateFlow()
    private var context: Context? = null

    @Synchronized
    fun initialize(appContext: Context) {
        if (context != null) return
        context = appContext.applicationContext
        val raw = context!!.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .getString(KEY_STATE, null)
        mutableState.value = decode(raw)
        HealthPeriodReminderScheduler.reschedule(context!!, mutableState.value)
    }

    @Synchronized
    fun savePeriod(first: LocalDate, second: LocalDate) {
        val start = minOf(first, second)
        val end = maxOf(first, second)
        val record = PeriodRecord(UUID.randomUUID().toString(), start, end)
        val records = mutableState.value.records
            .filterNot { it.startDate == start && it.endDate == end }
            .plus(record)
            .sortedByDescending { it.startDate }
            .take(48)
        persist(mutableState.value.copy(records = records))
    }

    @Synchronized
    fun deletePeriod(id: String) {
        persist(mutableState.value.copy(records = mutableState.value.records.filterNot { it.id == id }))
    }

    @Synchronized
    fun setReminderEnabled(enabled: Boolean) {
        persist(mutableState.value.copy(reminderEnabled = enabled))
    }

    @Synchronized
    fun setReminderDaysBefore(days: Int) {
        persist(mutableState.value.copy(reminderDaysBefore = days.coerceIn(0, 14)))
    }

    fun prediction(value: HealthCycleState = mutableState.value): PeriodPrediction? {
        val latest = value.records.maxByOrNull { it.startDate } ?: return null
        val cycleDays = averageCycleDays(value.records)
        val periodDays = averagePeriodDays(value.records)
        val start = latest.startDate.plusDays(cycleDays.toLong())
        return PeriodPrediction(
            startDate = start,
            endDate = start.plusDays((periodDays - 1).toLong()),
            cycleDays = cycleDays,
            periodDays = periodDays,
        )
    }

    private fun averageCycleDays(records: List<PeriodRecord>): Int {
        val starts = records.map { it.startDate }.distinct().sorted()
        val gaps = starts.zipWithNext { first, second ->
            ChronoUnit.DAYS.between(first, second).toInt()
        }.filter { it in 15..60 }
        return if (gaps.isEmpty()) 28 else gaps.average().roundToInt().coerceIn(15, 60)
    }

    private fun averagePeriodDays(records: List<PeriodRecord>): Int {
        val lengths = records.map {
            ChronoUnit.DAYS.between(it.startDate, it.endDate).toInt() + 1
        }.filter { it in 1..15 }
        return if (lengths.isEmpty()) 5 else lengths.average().roundToInt().coerceIn(1, 15)
    }

    private fun persist(next: HealthCycleState) {
        mutableState.value = next
        val appContext = context ?: return
        appContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .edit()
            .putString(KEY_STATE, encode(next).toString())
            .apply()
        HealthPeriodReminderScheduler.reschedule(appContext, next)
    }

    private fun encode(value: HealthCycleState): JSONObject = JSONObject().apply {
        put("reminderEnabled", value.reminderEnabled)
        put("reminderDaysBefore", value.reminderDaysBefore)
        put("records", JSONArray().apply {
            value.records.forEach { record ->
                put(
                    JSONObject()
                        .put("id", record.id)
                        .put("start", record.startDate.toString())
                        .put("end", record.endDate.toString()),
                )
            }
        })
    }

    private fun decode(raw: String?): HealthCycleState = runCatching {
        val root = JSONObject(raw ?: "{}")
        val array = root.optJSONArray("records") ?: JSONArray()
        val records = buildList {
            for (index in 0 until array.length()) {
                val item = array.optJSONObject(index) ?: continue
                val start = runCatching { LocalDate.parse(item.optString("start")) }.getOrNull() ?: continue
                val end = runCatching { LocalDate.parse(item.optString("end")) }.getOrNull() ?: start
                add(
                    PeriodRecord(
                        id = item.optString("id").ifBlank { UUID.randomUUID().toString() },
                        startDate = minOf(start, end),
                        endDate = maxOf(start, end),
                    ),
                )
            }
        }.sortedByDescending { it.startDate }
        HealthCycleState(
            records = records,
            reminderEnabled = root.optBoolean("reminderEnabled", true),
            reminderDaysBefore = root.optInt("reminderDaysBefore", 3).coerceIn(0, 14),
        )
    }.getOrDefault(HealthCycleState())
}

internal object HealthPeriodReminderScheduler {
    private const val REQUEST_CODE = 0x4845

    fun reschedule(context: Context, state: HealthCycleState) {
        val appContext = context.applicationContext
        val manager = appContext.getSystemService(AlarmManager::class.java)
        val operation = PendingIntent.getBroadcast(
            appContext,
            REQUEST_CODE,
            Intent(appContext, HealthPeriodReminderReceiver::class.java),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        manager.cancel(operation)
        if (!state.reminderEnabled) return
        val prediction = HealthCycleStore.prediction(state) ?: return
        val reminderDate = prediction.startDate.minusDays(state.reminderDaysBefore.toLong())
        val trigger = reminderDate.atTime(LocalTime.of(9, 0)).atZone(ZoneId.systemDefault()).toInstant()
        if (!trigger.isAfter(Instant.now())) return
        runCatching {
            manager.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, trigger.toEpochMilli(), operation)
        }.recoverCatching {
            manager.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, trigger.toEpochMilli(), operation)
        }
    }
}

class HealthPeriodReminderReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        HealthCycleStore.initialize(context.applicationContext)
        val prediction = HealthCycleStore.prediction() ?: return
        val manager = context.getSystemService(NotificationManager::class.java)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            manager.createNotificationChannel(
                NotificationChannel(CHANNEL_ID, "经期提醒", NotificationManager.IMPORTANCE_DEFAULT),
            )
        }
        val openApp = PendingIntent.getActivity(
            context,
            0x4846,
            Intent(context, MigrationActivity::class.java).apply {
                flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
            },
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        val notification = NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.ic_dialog_info)
            .setContentTitle("经期可能快到了")
            .setContentText("预计从 ${formatHealthDate(prediction.startDate)} 开始")
            .setContentIntent(openApp)
            .setAutoCancel(true)
            .build()
        runCatching { manager.notify(NOTIFICATION_ID, notification) }
    }

    private companion object {
        const val CHANNEL_ID = "lulu_health_period"
        const val NOTIFICATION_ID = 0x4847
    }
}

class HealthReminderBootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action == Intent.ACTION_BOOT_COMPLETED || intent.action == Intent.ACTION_MY_PACKAGE_REPLACED) {
            HealthCycleStore.initialize(context.applicationContext)
            HealthPeriodReminderScheduler.reschedule(context.applicationContext, HealthCycleStore.state.value)
        }
    }
}

internal fun formatHealthDate(date: LocalDate): String = "${date.monthValue}月${date.dayOfMonth}日"
