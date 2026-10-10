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
import androidx.work.WorkInfo
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import com.jiacimu.lulu.LuluRepositories
import com.jiacimu.lulu.ai.LuluAiServices
import com.jiacimu.lulu.health.GadgetbridgeHealthStore
import com.jiacimu.lulu.study.PostgraduateExamStores
import com.jiacimu.lulu.study.StarWishStores
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.time.Duration
import java.time.Instant
import java.util.concurrent.TimeUnit

/**
 * Durable scheduler for proactive perception.
 *
 * Long-lived background work follows each role's interval. Short online sessions enqueue an
 * independent per-role perception when a relevant unread chat event arrives. Device signals remain
 * context only and never wake a role by themselves.
 */
object ProactivePerceptionScheduler {
    private const val WATCHDOG_WORK = "lulu-perception-watchdog-v2"
    private const val NEXT_DUE_WORK = "lulu-perception-next-due-v2"
    private const val ONLINE_WORK = "lulu-perception-online-v1"
    private const val DUE_TAG = "lulu-perception-due-v3"
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val scheduleMutex = Mutex()
    private var foregroundChecks: Job? = null

    @Synchronized fun startForegroundChecks(context: Context) {
        if (foregroundChecks?.isActive == true) return
        val app = context.applicationContext
        foregroundChecks = scope.launch {
            while (true) {
                scheduleNextDue(app)
                delay(30_000L)
            }
        }
    }

    @Synchronized fun stopForegroundChecks() {
        foregroundChecks?.cancel()
        foregroundChecks = null
    }

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
        scope.launch {
            try { scheduleMutex.withLock {
                // WorkManager queries run off the UI thread. Never replace a running perception.
                val manager = WorkManager.getInstance(appContext)
                val due = ProactivePerceptionRuntime.nextDueAt(appContext)
                val jobs = manager.getWorkInfosByTag(DUE_TAG).get().filterNot { it.state.isFinished }
                val legacy = manager.getWorkInfosForUniqueWork(NEXT_DUE_WORK).get().filterNot { it.state.isFinished }
                val dueTag = due?.let { "wake-at-${it.toEpochMilli()}" }
                (jobs + legacy).filter { it.state != WorkInfo.State.RUNNING &&
                    (dueTag == null || dueTag !in it.tags) }.forEach { manager.cancelWorkById(it.id) }
                if (due == null || jobs.any { dueTag != null && dueTag in it.tags }) {
                    PerceptionWakePlanStore.recordSchedulingError("")
                    return@withLock
                }
                val delayMillis = Duration.between(Instant.now(), due).toMillis().coerceAtLeast(0L)
                val request = OneTimeWorkRequestBuilder<ProactivePerceptionWorker>()
                    .addTag(DUE_TAG).addTag(checkNotNull(dueTag))
                    .setInitialDelay(delayMillis, TimeUnit.MILLISECONDS)
                    .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build())
                    .setInputData(Data.Builder().putString("trigger", "角色时间间隔").build())
                    .build()
                manager.enqueueUniqueWork("$NEXT_DUE_WORK-${due.toEpochMilli()}", ExistingWorkPolicy.KEEP, request).result.get()
                PerceptionWakePlanStore.recordSchedulingError("")
            } } catch (error: Exception) {
                if (error is kotlinx.coroutines.CancellationException) throw error
                PerceptionWakePlanStore.recordSchedulingError("自动唤醒排程未完成：${error.message.orEmpty().take(100)}")
            }
        }
    }

    /** Legacy API kept only so old code can compile; it never creates an event-triggered model run. */
    fun scheduleSoon(context: Context, trigger: String) {
        scheduleNextDue(context.applicationContext)
    }

    fun scheduleConcernPromise(context: Context, characterId: String) {
        ProactivePerceptionRuntime.markConcernPromisePending(context.applicationContext, characterId)
    }

    fun scheduleManual(context: Context, characterId: String) {
        CompanionOnlineStore.wakeCharacter(
            characterId = characterId,
            reason = CompanionOnlineReason.BackgroundPerception,
            trigger = "用户手动唤醒",
        )
    }

    fun scheduleOnline(context: Context, characterId: String, trigger: String,
        collectMessages: Boolean = false, requiresUnread: Boolean = false,
        delayMillis: Long = 0L) {
        val batch = OnlineChatBatchStore.next(context, characterId, collectMessages)
        val request = OneTimeWorkRequestBuilder<ProactivePerceptionWorker>()
            .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build())
            .setInitialDelay(delayMillis.coerceAtLeast(0L), TimeUnit.MILLISECONDS)
            .setInputData(
                Data.Builder()
                    .putString("trigger", trigger)
                    .putString("characterId", characterId)
                    .putBoolean("force", true)
                    .putBoolean("requireOnline", true)
                    .putLong("onlineRevision", batch.revision)
                    .putBoolean("requiresUnread", requiresUnread)
                    .build(),
            )
            .build()
        WorkManager.getInstance(context.applicationContext)
            .enqueueUniqueWork("$ONLINE_WORK-$characterId", ExistingWorkPolicy.APPEND_OR_REPLACE, request)
    }

    /**
     * A separate, evidence-triggered reflection opportunity. It cannot be swallowed
     * by the ordinary online batch revision being claimed by another worker.
     */
    fun scheduleOnlineReflection(
        context: Context, characterId: String, trigger: String, delayMillis: Long = 45_000L,
    ) {
        if (characterId.isBlank()) return
        val request = OneTimeWorkRequestBuilder<ProactivePerceptionWorker>()
            .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build())
            .setInitialDelay(delayMillis.coerceAtLeast(0L), TimeUnit.MILLISECONDS)
            .setInputData(Data.Builder()
                .putString("trigger", trigger)
                .putString("characterId", characterId)
                .putBoolean("force", true)
                .putBoolean("requireOnline", true)
                .putBoolean("respectMessageBuffer", true)
                .build())
            .build()
        WorkManager.getInstance(context.applicationContext)
            .enqueueUniqueWork("$ONLINE_WORK-reflection-$characterId", ExistingWorkPolicy.KEEP, request)
    }

    /**
     * Five minutes means leaving live chat, not forgetting a strong emotion.
     * Run at most one low-priority follow-up per 90 minutes; do not reopen the
     * online window or dictate which specific action a character should choose.
     */
    fun scheduleEmotionalAftercare(context: Context, characterId: String, delayMillis: Long = 90_000L) {
        if (characterId.isBlank() || !ProactivePerceptionPolicyStore.get(characterId).enabled) return
        if (!CharacterInnerLifeStore.needsPostOnlineReflection(characterId)) return
        val app = context.applicationContext
        val prefs = app.getSharedPreferences("lulu_post_online_aftercare_v1", Context.MODE_PRIVATE)
        val now = System.currentTimeMillis()
        if (now - prefs.getLong("scheduled:$characterId", 0L) < 90 * 60_000L) return
        val request = OneTimeWorkRequestBuilder<ProactivePerceptionWorker>()
            .setInitialDelay(delayMillis.coerceAtLeast(0L), TimeUnit.MILLISECONDS)
            .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build())
            .setInputData(Data.Builder()
                .putString("trigger", "强烈情绪的后续整理：从真实经历与未完成心愿里，自主决定反思、沟通、实际行动或安静消化；不要机械写日记或发朋友圈")
                .putString("characterId", characterId)
                .putBoolean("force", true)
                .putBoolean("preserveOffline", true)
                .build())
            .build()
        prefs.edit().putLong("scheduled:$characterId", now).apply()
        WorkManager.getInstance(app).enqueueUniqueWork(
            "lulu-aftercare-$characterId", ExistingWorkPolicy.KEEP, request,
        )
    }

    /**
     * A one-off chance to interpret a recently finished call. It is not an
     * automatic "why did you hang up?" message: the autonomy model may feel
     * something privately, choose another activity, contact the user or stay
     * silent. This must run *after* the call releases ownership of perception.
     */
    fun scheduleInteractionReflection(
        context: Context, characterId: String, evidenceId: String,
        delayMillis: Long = 20_000L,
    ) {
        if (characterId.isBlank() || evidenceId.isBlank() ||
            !ProactivePerceptionPolicyStore.get(characterId).enabled) return
        val app = context.applicationContext
        val request = OneTimeWorkRequestBuilder<ProactivePerceptionWorker>()
            .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build())
            .setInitialDelay(delayMillis.coerceAtLeast(0L), TimeUnit.MILLISECONDS)
            .setInputData(Data.Builder()
                .putString("trigger", "新发生的互动体验：电话已结束。请根据已记录的事实与个人经历自行形成感受，并决定是否沟通、继续生活或安静等待；不预设用户动机。证据ID=$evidenceId")
                .putString("characterId", characterId)
                .putBoolean("force", true)
                .build())
            .build()
        // Coalesce repeated calls per role, keeping the most recent cue.
        WorkManager.getInstance(app).enqueueUniqueWork(
            "lulu-interaction-reflection-$characterId", ExistingWorkPolicy.REPLACE, request,
        )
    }

    fun cancelOnline(context: Context, characterId: String) {
        OnlineChatBatchStore.cancel(context, characterId)
        WorkManager.getInstance(context.applicationContext).cancelUniqueWork("$ONLINE_WORK-$characterId")
        WorkManager.getInstance(context.applicationContext).cancelUniqueWork("$ONLINE_WORK-reflection-$characterId")
        ChatGenerationActivity.clearCharacter(characterId)
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
        val requireOnline = inputData.getBoolean("requireOnline", false)
        val preserveOffline = inputData.getBoolean("preserveOffline", false)
        if (preserveOffline && (characterId == null || CompanionOnlineStore.isOnline(characterId) ||
            !CharacterInnerLifeStore.needsPostOnlineReflection(characterId) ||
            !ProactivePerceptionPolicyStore.get(characterId).enabled)) {
            return@runCatching Result.success()
        }
        if (requireOnline && (characterId == null || !CompanionOnlineStore.isOnline(characterId))) {
            ProactivePerceptionScheduler.scheduleNextDue(applicationContext)
            return@runCatching Result.success()
        }
        val onlineRevision = inputData.getLong("onlineRevision", -1L).takeIf { requireOnline && it >= 0L }
        if (requireOnline && characterId != null && onlineRevision != null &&
            !OnlineChatBatchStore.isCurrent(applicationContext, characterId, onlineRevision)) {
            return@runCatching Result.success()
        }
        // Follow the latest deadline, not the first bubble's deadline. The
        // same pending revision may be extended while this worker is asleep.
        // A new bubble must get a full three seconds before perception starts.
        if (requireOnline && characterId != null && onlineRevision != null) {
            while (true) {
                if (!CompanionOnlineStore.isOnline(characterId) ||
                    !OnlineChatBatchStore.isCurrent(applicationContext, characterId, onlineRevision ?: 0L)) {
                    return@runCatching Result.success()
                }
                val due = OnlineChatBatchStore.dueAt(applicationContext, characterId, onlineRevision ?: 0L)
                    ?: return@runCatching Result.success()
                val remaining = due - System.currentTimeMillis()
                if (remaining <= 0L) break
                kotlinx.coroutines.delay(remaining)
            }
        }
        // A separate reflection should not race with the current chat batch:
        // wait until the primary user-message perception has finished, without
        // claiming or consuming that batch itself.
        if (requireOnline && characterId != null &&
            inputData.getBoolean("respectMessageBuffer", false)) {
            while (true) {
                if (!CompanionOnlineStore.isOnline(characterId)) return@runCatching Result.success()
                val pending = OnlineChatBatchStore.pendingDueAt(applicationContext, characterId)
                val busy = MigratedDomainStores.chat.conversations.value.any {
                    it.characterId == characterId && ChatGenerationActivity.isRunning(it.id)
                } || CompanionPresenceStore.isInCall(characterId)
                if (pending == null && !busy) break
                kotlinx.coroutines.delay(1_000L)
            }
        }
        ProactivePerceptionRuntime.runDueCycle(
            context = applicationContext,
            trigger = trigger,
            targetCharacterId = characterId,
            force = force,
            onlineRevision = onlineRevision,
            requiresUnread = inputData.getBoolean("requiresUnread", false),
            preserveOffline = preserveOffline,
        )
        ProactivePerceptionScheduler.scheduleNextDue(applicationContext)
        Result.success()
    }.getOrElse {
        if (it is kotlinx.coroutines.CancellationException) throw it
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
internal fun initializeBackgroundRuntime(context: Context) {
    UserDataUpgradeGuard.protectBeforeStoresInitialize(context)
    LuluAppPreferencesStore.initialize(context)
    UserProfileContext.initialize(context)
    UserInteractionPresenceStore.initialize(context)
    LuluRepositories.initialize(context)
    LuluRepositories.lexicon.initialize(context)
    LuluRepositories.worldBook.initialize(context)
    DigitalLifeProfileStore.initialize(context)
    SharedExperienceTimeline.initialize(context)
    MigratedDomainStores.initialize(context)
    CharacterIdentityStore.initialize(context)
    CharacterInnerLifeStore.initialize(context)
    PerceptionStimulusLedger.initialize(context)
    DigitalWorldStore.initialize(context)
    MomentsStore.initialize(context)
    CompanionPresenceStore.initialize(context)
    CharacterDevelopmentStore.initialize(context)
        com.jiacimu.lulu.data.CharacterLifeStore.initialize(context)
    CharacterDevelopmentRuntime.initialize(context)
    CloudTaskBridge.initialize(context)
    com.jiacimu.lulu.system.LuluDeviceToolBridge.initialize(context)
    CompanionOnlineStore.initialize(context)
    LuluAiServices.initialize(context)
    MemoryModelRuntime.initialize(context)
    ProactivePerceptionPolicyStore.initialize(context)
    PostgraduateExamStores.initialize(context)
    StarWishStores.initialize(context)
    GadgetbridgeHealthStore.initialize(context)
}
