package com.jiacimu.lulu.data

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.Data
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import com.jiacimu.lulu.LuluRepositories
import com.jiacimu.lulu.ai.LuluAiServices
import java.util.concurrent.TimeUnit

/**
 * Keeps perception alive after the activity is backgrounded or the process is killed.
 *
 * WorkManager owns the long model request, so it is not constrained by BroadcastReceiver's short
 * execution window. Periodic work survives process death and reboot; real screen/notification
 * changes enqueue a debounced one-time run.
 */
object ProactivePerceptionScheduler {
    private const val PERIODIC_WORK = "lulu-periodic-perception"
    private const val SIGNAL_WORK = "lulu-signal-perception"
    private const val DEFAULT_DELAY_MINUTES = 30L

    fun schedule(context: Context, delayMinutes: Long = DEFAULT_DELAY_MINUTES, trigger: String = "后台定时") {
        val appContext = context.applicationContext
        val constraints = Constraints.Builder()
            .setRequiredNetworkType(NetworkType.CONNECTED)
            .build()
        val periodic = PeriodicWorkRequestBuilder<ProactivePerceptionWorker>(30, TimeUnit.MINUTES)
            .setConstraints(constraints)
            .setInputData(Data.Builder().putString("trigger", "后台定时").build())
            .build()
        WorkManager.getInstance(appContext).enqueueUniquePeriodicWork(
            PERIODIC_WORK,
            ExistingPeriodicWorkPolicy.UPDATE,
            periodic,
        )
        if (delayMinutes != DEFAULT_DELAY_MINUTES || trigger != "后台定时") {
            enqueueOneTime(appContext, delayMinutes.coerceAtLeast(0L), trigger)
        }
    }

    fun scheduleSoon(context: Context, trigger: String) {
        enqueueOneTime(context.applicationContext, 1L, trigger)
    }

    private fun enqueueOneTime(context: Context, delayMinutes: Long, trigger: String) {
        val request = OneTimeWorkRequestBuilder<ProactivePerceptionWorker>()
            .setInitialDelay(delayMinutes, TimeUnit.MINUTES)
            .setConstraints(
                Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build(),
            )
            .setInputData(Data.Builder().putString("trigger", trigger.take(80)).build())
            .build()
        WorkManager.getInstance(context).enqueueUniqueWork(
            SIGNAL_WORK,
            ExistingWorkPolicy.REPLACE,
            request,
        )
    }
}

class ProactivePerceptionWorker(
    appContext: Context,
    workerParameters: WorkerParameters,
) : CoroutineWorker(appContext, workerParameters) {
    override suspend fun doWork(): Result = runCatching {
        initializeBackgroundRuntime(applicationContext)
        ProactiveMessageAutomation.initialize(applicationContext)
        val trigger = inputData.getString("trigger").orEmpty().ifBlank { "后台定时" }
        ProactiveMessageAutomation.runBackgroundCycle(trigger = trigger)
        Result.success()
    }.getOrElse {
        if (runAttemptCount < 2) Result.retry() else Result.failure()
    }
}

/** Compatibility receiver: any old pending alarm is converted into durable WorkManager work. */
class ProactivePerceptionReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val trigger = intent.getStringExtra("trigger").orEmpty().ifBlank { "后台唤醒" }
        ProactivePerceptionScheduler.scheduleSoon(context.applicationContext, trigger)
    }
}

/** Restores and immediately verifies the perception heartbeat after reboot or cover-install. */
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
