package com.jiacimu.lulu.data

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.SystemClock
import com.jiacimu.lulu.LuluRepositories
import com.jiacimu.lulu.ai.LuluAiServices
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

/**
 * Keeps perception alive after the activity process is backgrounded or killed.
 *
 * AlarmManager is used instead of an Activity-owned timer. Every delivery re-schedules the next
 * heartbeat, while accessibility/notification signals can replace it with a sooner one.
 */
object ProactivePerceptionScheduler {
    private const val ACTION = "app.lulu.PROACTIVE_PERCEPTION"
    private const val REQUEST_CODE = 0x1A11
    private const val DEFAULT_DELAY_MINUTES = 20L

    fun schedule(context: Context, delayMinutes: Long = DEFAULT_DELAY_MINUTES, trigger: String = "后台定时") {
        scheduleMillis(context, delayMinutes.coerceAtLeast(1L) * 60_000L, trigger)
    }

    fun scheduleSoon(context: Context, trigger: String) {
        scheduleMillis(context, 45_000L, trigger)
    }

    private fun scheduleMillis(context: Context, delayMillis: Long, trigger: String) {
        val appContext = context.applicationContext
        val manager = appContext.getSystemService(AlarmManager::class.java)
        val operation = PendingIntent.getBroadcast(
            appContext,
            REQUEST_CODE,
            Intent(appContext, ProactivePerceptionReceiver::class.java).apply {
                action = ACTION
                putExtra("trigger", trigger.take(80))
            },
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        val at = SystemClock.elapsedRealtime() + delayMillis
        runCatching {
            manager.setExactAndAllowWhileIdle(AlarmManager.ELAPSED_REALTIME_WAKEUP, at, operation)
        }.recoverCatching {
            manager.setAndAllowWhileIdle(AlarmManager.ELAPSED_REALTIME_WAKEUP, at, operation)
        }
    }
}

class ProactivePerceptionReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val pending = goAsync()
        val trigger = intent.getStringExtra("trigger").orEmpty().ifBlank { "后台定时" }
        CoroutineScope(SupervisorJob() + Dispatchers.IO).launch {
            try {
                initializeBackgroundRuntime(context.applicationContext)
                ProactiveMessageAutomation.initialize(context.applicationContext)
                ProactiveMessageAutomation.runBackgroundCycle(trigger = trigger)
            } finally {
                ProactivePerceptionScheduler.schedule(context.applicationContext)
                pending.finish()
            }
        }
    }
}

/** Restores the perception heartbeat after reboot and after an APK cover-install. */
class ProactivePerceptionBootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action == Intent.ACTION_BOOT_COMPLETED || intent.action == Intent.ACTION_MY_PACKAGE_REPLACED) {
            ProactivePerceptionScheduler.schedule(context.applicationContext, delayMinutes = 2L, trigger = "开机恢复")
        }
    }
}

@Synchronized
private fun initializeBackgroundRuntime(context: Context) {
    UserDataUpgradeGuard.protectBeforeStoresInitialize(context)
    LuluAppPreferencesStore.initialize(context)
    UserProfileContext.initialize(context)
    LuluRepositories.initialize(context)
    LuluRepositories.lexicon.initialize(context)
    LuluRepositories.worldBook.initialize(context)
    SharedExperienceTimeline.initialize(context)
    MigratedDomainStores.initialize(context)
    MomentsStore.initialize(context)
    CompanionPresenceStore.initialize(context)
    LuluAiServices.initialize(context)
    MemoryModelRuntime.initialize(context)
}
