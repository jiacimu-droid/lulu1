package com.jiacimu.lulu.data

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import java.time.Instant

/** Inexact idle-capable wake supplements JobScheduler; it is not an alarm-clock exemption. */
internal object PerceptionNativeWake {
    private const val PREFS = "lulu_perception_native_wake_v1"

    @Synchronized fun schedule(context: Context, due: Instant?) {
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val alarm = context.getSystemService(AlarmManager::class.java)
        val pending = pendingIntent(context)
        if (due == null) {
            alarm.cancel(pending)
            prefs.edit().remove("due").commit()
            return
        }
        // Rechecking a past-due plan must not turn into an immediate idle-alarm loop.
        // The foreground service handles an overdue connected plan immediately.
        val desired = due.toEpochMilli()
        if (prefs.getLong("due", 0L) == desired) return
        val at = maxOf(desired, System.currentTimeMillis() + 60_000L)
        alarm.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, at, pending)
        prefs.edit().putLong("due", desired).commit()
    }

    @Synchronized fun invalidate(context: Context) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().remove("due").commit()
    }

    private fun pendingIntent(context: Context) = PendingIntent.getBroadcast(context, 8423,
        Intent(context, ProactivePerceptionReceiver::class.java)
            .setAction("com.jiacimu.lulu.PERCEPTION_DUE"),
        PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
}
