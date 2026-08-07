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
import com.jiacimu.lulu.health.GadgetbridgeHealthStore
import com.jiacimu.lulu.study.PostgraduateExamStores
import com.jiacimu.lulu.study.StarWishStores
import java.time.Duration
import java.time.Instant
import java.util.concurrent.TimeUnit

/**
 * Durable scheduler for proactive perception.
 *
 * There are only two automatic sources:
 * 1) each character's configured fixed interval (with a 2-hour WorkManager watchdog), and
 * 2) pending concern/promise state, which can cancel adaptive stretching but never beats the
 *    user's base interval.
 *
 * Screen changes and notifications are read as context only and never schedule work.
 */
object ProactivePerceptionScheduler {
    private const val WATCHDOG_WORK = "lulu-perception-watchdog-v2"
    private const val NEXT_DUE_WORK = "lulu-perception-next-due-v2"
    private const val MANUAL_WORK = "lulu-perception-manual-v2"

    fun schedule(context: Context) {
        val appContext = context.applicationContext
        val constraints = Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build()
        val watchdog = PeriodicWorkRequestBuilder<ProactivePerceptionWorker>(2, TimeUnit.HOURS)
            .setConstraints(constraints)
            .setInputData(Data.Builder().putString("trigger", "后台两小时守护").build())
            .build()
        WorkManager.getInstance(appContext).enqueueUniquePeriodicWork(
            WATCHDOG_WORK,
            ExistingPeriodicWorkPolicy.UPDATE,
            watchdog,
        )
        scheduleNextDue(appContext)
    }

    fun scheduleNextDue(context: Context) {
        val appContext = context.applicationContext
        val manager = WorkManager.getInstance(appContext)
        val due = runCatching { ProactivePerceptionRuntime.nextDueAt(appContext) }.getOrNull()
        if (due == null) {
            manager.cancelUniqueWork(NEXT_DUE_WORK)
            return
        }
        val delayMillis = Duration.between(Instant.now(), due).toMillis().coerceAtLeast(0L)
        val request = OneTimeWorkRequestBuilder<ProactivePerceptionWorker>()
            .setInitialDelay(delayMillis, TimeUnit.MILLISECONDS)
            .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build())
            .setInputData(Data.Builder().putString("trigger", "角色时间间隔").build())
            .build()
        manager.enqueueUniqueWork(NEXT_DUE_WORK, ExistingWorkPolicy.REPLACE, request)
    }

    fun scheduleConcernPromise(context: Context, characterId: String) {
        ProactivePerceptionRuntime.markConcernPromisePending(context.applicationContext, characterId)
    }

    fun scheduleManual(context: Context, characterId: String) {
        val request = OneTimeWorkRequestBuilder<ProactivePerceptionWorker>()
            .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build())
            .setInputData(
                Data.Builder()
                    .putString("trigger", "用户手动检查")
                    .putString("characterId", characterId)
                    .putBoolean("force", true)
                    .build(),
            )
            .build()
        WorkManager.getInstance(context.applicationContext)
            .enqueueUniqueWork("$MANUAL_WORK-$characterId", ExistingWorkPolicy.REPLACE, request)
    }
}

class ProactivePerceptionWorker(
    appContext: Context,
    workerParameters: WorkerParameters,
) : CoroutineWorker(appContext, workerParameters) {
    override suspend fun doWork(): Result = runCatching {
        initializeBackgroundRuntime(applicationContext)
        ProactivePerceptionRuntime.initialize(applicationContext)
        val trigger = inputData.getString("trigger").orEmpty().ifBlank { "角色时间间隔" }
        val characterId = inputData.getString("characterId")?.takeIf(String::isNotBlank)
        val force = inputData.getBoolean("force", false)
        ProactivePerceptionRuntime.runDueCycle(
            context = applicationContext,
            trigger = trigger,
            targetCharacterId = characterId,
            force = force,
        )
        ProactivePerceptionScheduler.scheduleNextDue(applicationContext)
        Result.success()
    }.getOrElse {
        if (runAttemptCount < 2) Result.retry() else Result.failure()
    }
}

/** Old pending alarms no longer trigger a model call; they only rebuild the next due schedule. */
class ProactivePerceptionReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        ProactivePerceptionScheduler.scheduleNextDue(context.applicationContext)
    }
}

/** Rebuild the durable watchdog and per-character timer after reboot or cover-install. */
class ProactivePerceptionBootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action == Intent.ACTION_BOOT_COMPLETED || intent.action == Intent.ACTION_MY_PACKAGE_REPLACED) {
            initializeBackgroundRuntime(context.applicationContext)
            ProactivePerceptionRuntime.initialize(context.applicationContext)
            ProactivePerceptionScheduler.schedule(context.applicationContext)
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
    ProactivePerceptionPolicyStore.initialize(context)
    PostgraduateExamStores.initialize(context)
    StarWishStores.initialize(context)
    GadgetbridgeHealthStore.initialize(context)
}
