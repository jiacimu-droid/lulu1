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
import androidx.work.OutOfQuotaPolicy
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
    // A queued WorkManager job is not proof that a user bubble was perceived.
    // Keep a short-lived foreground reader per batch revision, even when a
    // durable job was already marked enqueued in the preferences.
    private val liveChatJobs = mutableMapOf<Pair<String, Long>, Job>()

    @Synchronized fun startForegroundChecks(context: Context) {
        if (foregroundChecks?.isActive == true) return
        val app = context.applicationContext
        // Force-stop and some OEM task cleaners remove alarms without removing our preferences.
        // A real user reopen must rebuild the registration, even if its saved due time is unchanged.
        PerceptionNativeWake.invalidate(app)
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

    private fun goalWorkName(characterId: String) = "lulu-goal-review-$characterId"

    /**
     * One timed, evidence-gated reappraisal of an existing character desire.
     * Unique REPLACE work prevents duplicate paid calls for the same goal.
     */
    fun scheduleGoalReview(context: Context, characterId: String, intentionId: String,
        at: Instant) {
        if (characterId.isBlank() || intentionId.isBlank() ||
            !ProactivePerceptionPolicyStore.get(characterId).enabled) return
        val app = context.applicationContext
        val delayMs = Duration.between(Instant.now(), at).toMillis().coerceAtLeast(0L)
        val request = OneTimeWorkRequestBuilder<ProactivePerceptionWorker>()
            .setInitialDelay(delayMs, TimeUnit.MILLISECONDS)
            .setConstraints(Constraints.Builder()
                .setRequiredNetworkType(NetworkType.CONNECTED).build())
            .setInputData(Data.Builder()
                .putString("trigger", "持续目标定时复盘：只检查已保存的目标、真正行为回执和新证据；决定改进、联系、等待或休眠。")
                .putString("characterId", characterId)
                .putString("goalReviewId", intentionId)
                .putLong("goalReviewDueMillis", at.toEpochMilli())
                .putBoolean("force", true)
                .build())
            .build()
        WorkManager.getInstance(app).enqueueUniqueWork(
            goalWorkName(characterId), ExistingWorkPolicy.REPLACE, request)
    }

    fun cancelGoalReview(context: Context, characterId: String) {
        if (characterId.isBlank()) return
        WorkManager.getInstance(context.applicationContext)
            .cancelUniqueWork(goalWorkName(characterId))
    }

    /** Restore still-valid intentions after process death or an APK upgrade. */
    private fun restoreGoalReviews(context: Context) {
        CharacterMotiveReviewScheduler.restore(context)
        CharacterLifeStore.states.value.keys.forEach { characterId ->
            val goal = CharacterLifeStore.state(characterId).optJSONObject("intention")
                ?: return@forEach
            val at = runCatching {
                Instant.parse(goal.optString("nextReviewAt"))
            }.getOrNull() ?: return@forEach
            if (goal.optString("stage") !in setOf("paused", "released", "completed")) {
                scheduleGoalReview(context, characterId, goal.optString("createdAt"), at)
            }
        }
    }

    fun schedule(context: Context) {
        val appContext = context.applicationContext
        val constraints = Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build()
        // Cheap local due check, not a fifteen-minute paid model cadence.
        val watchdog = PeriodicWorkRequestBuilder<ProactivePerceptionWorker>(15, TimeUnit.MINUTES)
            .setConstraints(constraints)
            .setInputData(Data.Builder().putString("trigger", "后台调度守护").build())
            .build()
        WorkManager.getInstance(appContext).enqueueUniquePeriodicWork(
            WATCHDOG_WORK,
            ExistingPeriodicWorkPolicy.UPDATE,
            watchdog,
        )
        scheduleNextDue(appContext)
        restoreGoalReviews(appContext)
    }

    /** Receivers hand off durable work before returning; no cold Activity/store assumptions. */
    internal fun enqueueWake(context: Context, restoreSchedule: Boolean = false) {
        val request = OneTimeWorkRequestBuilder<ProactivePerceptionWorker>()
            .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build())
            .setExpedited(OutOfQuotaPolicy.RUN_AS_NON_EXPEDITED_WORK_REQUEST)
            .setInputData(Data.Builder().putString("trigger", "角色时间间隔")
                .putBoolean("restoreSchedule", restoreSchedule).build()).build()
        WorkManager.getInstance(context.applicationContext).enqueueUniqueWork(
            if (restoreSchedule) "lulu-perception-restore" else "lulu-perception-native-wake",
            ExistingWorkPolicy.KEEP, request).result.get(8, TimeUnit.SECONDS)
    }

    fun scheduleNextDue(context: Context) {
        val appContext = context.applicationContext
        scope.launch {
            try { scheduleMutex.withLock {
                // WorkManager queries run off the UI thread. Never replace a running perception.
                val manager = WorkManager.getInstance(appContext)
                val due = ProactivePerceptionRuntime.nextDueAt(appContext)
                val nativeError = runCatching { PerceptionNativeWake.schedule(appContext, due) }
                    .exceptionOrNull()?.let { "系统唤醒登记失败，保留任务队列：${it.message.orEmpty().take(80)}" }.orEmpty()
                if (due != null && UserInteractionPresenceStore.state.value.appVisible == true) {
                    BackgroundCompanionControl.refresh(appContext)
                }
                val jobs = manager.getWorkInfosByTag(DUE_TAG).get(10, TimeUnit.SECONDS).filterNot { it.state.isFinished }
                val legacy = manager.getWorkInfosForUniqueWork(NEXT_DUE_WORK).get(10, TimeUnit.SECONDS).filterNot { it.state.isFinished }
                val dueTag = due?.let { "wake-at-${it.toEpochMilli()}" }
                (jobs + legacy).filter { it.state != WorkInfo.State.RUNNING &&
                    (dueTag == null || dueTag !in it.tags) }.forEach { manager.cancelWorkById(it.id) }
                if (due == null || jobs.any { dueTag != null && dueTag in it.tags }) {
                    PerceptionWakePlanStore.recordSchedulingError(nativeError)
                    return@withLock
                }
                val delayMillis = Duration.between(Instant.now(), due).toMillis().coerceAtLeast(0L)
                val request = OneTimeWorkRequestBuilder<ProactivePerceptionWorker>()
                    .addTag(DUE_TAG).addTag(checkNotNull(dueTag))
                    .setInitialDelay(delayMillis, TimeUnit.MILLISECONDS)
                    .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build())
                    .setInputData(Data.Builder().putString("trigger", "角色时间间隔").build())
                    .build()
                manager.enqueueUniqueWork("$NEXT_DUE_WORK-${due.toEpochMilli()}", ExistingWorkPolicy.KEEP, request).result.get(10, TimeUnit.SECONDS)
                PerceptionWakePlanStore.recordSchedulingError(nativeError)
            } } catch (error: Exception) {
                if (error is kotlinx.coroutines.CancellationException) throw error
                PerceptionWakePlanStore.recordSchedulingError("自动唤醒排程未完成：${error.message.orEmpty().take(100)}")
            }
        }
    }

    /**
     * Recover an autonomous *failed model/parse call*, not a legitimate silent
     * decision or a failed external action. The runtime owns the persistent
     * cooldown and will not re-enqueue this recovery if it also fails.
     */
    fun scheduleBackgroundFailureRecovery(context: Context, characterId: String, attemptAtMillis: Long) {
        if (characterId.isBlank() || attemptAtMillis <= 0L) return
        val request = OneTimeWorkRequestBuilder<ProactivePerceptionWorker>()
            .setInitialDelay(ProactivePerceptionRecoveryPolicy.RETRY_DELAY_MILLIS, TimeUnit.MILLISECONDS)
            .setConstraints(Constraints.Builder()
                .setRequiredNetworkType(NetworkType.CONNECTED).build())
            .setInputData(Data.Builder()
                .putString("trigger", ProactivePerceptionRecoveryPolicy.RETRY_TRIGGER)
                .putString("characterId", characterId)
                .putBoolean("force", true)
                .build())
            .build()
        WorkManager.getInstance(context.applicationContext).enqueueUniqueWork(
            "lulu-autonomy-failed-recovery-$characterId-$attemptAtMillis",
            ExistingWorkPolicy.KEEP, request,
        ).result.get(10, TimeUnit.SECONDS)
    }

    /**
     * Read the actual OS work queue (not just our in-memory desired plan).
     * This is a read-only diagnostic for "the character did nothing all afternoon".
     * It never starts a new model call or changes the role's chosen cadence.
     */
    suspend fun backgroundWakeStatus(context: Context): String =
        kotlinx.coroutines.withContext(Dispatchers.IO) {
            runCatching {
                val manager = WorkManager.getInstance(context.applicationContext)
                val dueWork = manager.getWorkInfosByTag(DUE_TAG).get(10, TimeUnit.SECONDS)
                    .filter { !it.state.isFinished }
                val watchdog = manager.getWorkInfosForUniqueWork(WATCHDOG_WORK).get(10, TimeUnit.SECONDS)
                    .filter { !it.state.isFinished }
                val next = ProactivePerceptionRuntime.nextDueAt(context.applicationContext)
                val nextText = next?.atZone(java.time.ZoneId.systemDefault())
                    ?.format(java.time.format.DateTimeFormatter.ofPattern("MM-dd HH:mm"))
                    ?: "当前没有符合条件的普通感知计划"
                val dueStatus = when {
                    dueWork.any { it.state == WorkInfo.State.RUNNING } -> "任务正在执行"
                    dueWork.any { it.state == WorkInfo.State.ENQUEUED } -> "已进入 Android 等待队列"
                    dueWork.any { it.state == WorkInfo.State.BLOCKED } -> "任务被依赖阻塞"
                    next == null -> "无需普通定时任务"
                    else -> "未发现已排队的定时任务（可能正在重排，建议刷新）"
                }
                val watchdogStatus = if (watchdog.isNotEmpty()) "守护任务已登记"
                    else "未发现后台守护任务"
                val resident = BackgroundCompanionControl.status.value.note
                "后台陪伴：$resident；下次预定：$nextText；系统排程：$dueStatus；$watchdogStatus。"
            }.getOrElse { "系统排程读取失败：${it.message.orEmpty().take(100)}" }
        }

    /** One location arrival -> one targeted assessment, without requiring prior online status. */
    fun scheduleWorldArrival(context: Context, characterId: String, evidenceId: String) {
        if (characterId.isBlank() || evidenceId.isBlank()) return
        val request = OneTimeWorkRequestBuilder<ProactivePerceptionWorker>()
            .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build())
            .setInitialDelay(3_000L, TimeUnit.MILLISECONDS)
            .setInputData(Data.Builder()
                .putString("trigger", "数字世界真实地图到访通知：可自主选择回应、前往、继续活动或安静")
                .putString("characterId", characterId)
                .putBoolean("force", true)
                .putString("interactionEvidenceId", evidenceId)
                .build())
            .build()
        WorkManager.getInstance(context.applicationContext).enqueueUniqueWork(
            "lulu-arrival-$characterId", ExistingWorkPolicy.APPEND_OR_REPLACE, request)
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

    /** A character's own appointment is NOT a manual wake initiated by the user. */
    fun scheduleSelfPlanned(context: Context, characterId: String, title: String) {
        CompanionOnlineStore.wakeCharacter(
            characterId = characterId,
            reason = CompanionOnlineReason.BackgroundPerception,
            trigger = "本人日程到点：${title.take(80)}；请自行决定真实执行、改期或放弃",
        )
    }

    fun scheduleOnline(context: Context, characterId: String, trigger: String,
        collectMessages: Boolean = false, requiresUnread: Boolean = false,
        delayMillis: Long = 0L) {
        val batch = OnlineChatBatchStore.next(context, characterId, collectMessages)
        // This flag is only a scheduling hint, never an acknowledgement of
        // perception. A previously enqueued worker may have been stopped by
        // Android, or may have exited before a later bubble extended this turn.
        OnlineChatBatchStore.markEnqueued(context, characterId, batch.revision)
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
            .addTag("$ONLINE_WORK-chat-$characterId")
            .build()
        try {
            // A newer revision does NOT wait for an old generation or a
            // five-minute life/expiry job to drain a WorkManager chain.
            WorkManager.getInstance(context.applicationContext)
                .enqueueUniqueWork(
                    "$ONLINE_WORK-chat-$characterId-${batch.revision}",
                    ExistingWorkPolicy.KEEP, request,
                )
        } catch (error: Exception) {
            OnlineChatBatchStore.unmarkEnqueued(context, characterId, batch.revision)
            throw error
        }
        // While the app is actually foregrounded, avoid the OS WorkManager
        // dispatch latency. The durable worker is still the fallback after
        // process death; both contenders must claim the SAME batch revision,
        // so only one model perception can consume the user's messages.
        if (requiresUnread && foregroundChecks?.isActive == true)
            ensureForegroundChatReader(context.applicationContext, characterId, batch.revision, trigger)
    }

    @Synchronized private fun ensureForegroundChatReader(
        app: Context, characterId: String, revision: Long, trigger: String,
    ) {
        val key = characterId to revision
        if (liveChatJobs[key]?.isActive == true) return
        liveChatJobs[key] = scope.launch {
            var perceptionFailed = false
            try {
                while (CompanionOnlineStore.isOnline(characterId) &&
                    OnlineChatBatchStore.isCurrent(app, characterId, revision)) {
                    val due = OnlineChatBatchStore.dueAt(app, characterId, revision)
                        ?: return@launch
                    val remaining = due - System.currentTimeMillis()
                    if (remaining <= 0L) break
                    delay(remaining)
                }
                if (CompanionOnlineStore.isOnline(characterId) &&
                    OnlineChatBatchStore.isCurrent(app, characterId, revision)) {
                    // The durable and foreground workers share the same
                    // atomic batch claim; only one is allowed to read.
                    runCatching {
                        ProactivePerceptionRuntime.runDueCycle(
                            context = app,
                            trigger = trigger,
                            targetCharacterId = characterId,
                            force = true,
                            onlineRevision = revision,
                            requiresUnread = true,
                        )
                    }.onFailure { error ->
                        if (error is kotlinx.coroutines.CancellationException) throw error
                        perceptionFailed = true
                        if (OnlineChatBatchStore.isCurrent(app, characterId, revision)) {
                            // Scheduling failure is visible, but must not crash
                            // the UI just because a background retry was denied.
                            runCatching { scheduleFailedMessageRetry(app, characterId, revision) }
                                .onFailure { retryError ->
                                    CompanionPresenceStore.recordPerceptionAttempt(
                                        characterId,
                                        "新消息重试排程失败 · ${retryError.message.orEmpty().take(90)}",
                                        Instant.now(),
                                    )
                                }
                        }
                    }
                }
            } finally {
                synchronized(this@ProactivePerceptionScheduler) {
                    liveChatJobs.remove(key)
                    // A fresh user bubble can extend the deadline AFTER this
                    // worker passed its wait but BEFORE it claimed perception.
                    // Re-arm that same revision rather than leaving it waiting
                    // for an unrelated five-minute wake.
                    if (!perceptionFailed && foregroundChecks?.isActive == true &&
                        CompanionOnlineStore.isOnline(characterId) &&
                        OnlineChatBatchStore.dueAt(app, characterId, revision) != null) {
                        ensureForegroundChatReader(app, characterId, revision, trigger)
                    }
                }
            }
        }
    }

    private fun scheduleFailedMessageRetry(
        app: Context, characterId: String, revision: Long,
    ) {
        val request = OneTimeWorkRequestBuilder<ProactivePerceptionWorker>()
            .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build())
            .setInitialDelay(2_000L, TimeUnit.MILLISECONDS)
            .setInputData(Data.Builder()
                .putString("trigger", "在线收到新消息，恢复失败的感知")
                .putString("characterId", characterId)
                .putBoolean("force", true)
                .putBoolean("requireOnline", true)
                .putLong("onlineRevision", revision)
                .putBoolean("requiresUnread", true)
                .build())
            .addTag("$ONLINE_WORK-chat-$characterId")
            .build()
        WorkManager.getInstance(app).enqueueUniqueWork(
            "$ONLINE_WORK-recovery-$characterId-$revision",
            ExistingWorkPolicy.KEEP, request,
        )
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
     * Low-urgency life/idea follow-through shares one budget window per role.
     * A real user message, verified new arrival or call event never comes here.
     * Identity includes the next eligible timestamp so a worker currently executing
     * cannot swallow a later deferred follow-through.
     */
    fun scheduleCoalescedLifeReview(
        context: Context, characterId: String, delayMillis: Long, onlineOnly: Boolean,
        eligibleAtMillis: Long,
    ) {
        if (characterId.isBlank() || eligibleAtMillis <= 0L) return
        val app = context.applicationContext
        val request = OneTimeWorkRequestBuilder<ProactivePerceptionWorker>()
            .setInitialDelay(delayMillis.coerceAtLeast(100L), TimeUnit.MILLISECONDS)
            .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build())
            .setInputData(Data.Builder()
                .putString("trigger", if (onlineOnly) "在线持续感知·合并回顾" else "合并后的自主生活回顾")
                .putString("characterId", characterId)
                .putBoolean("force", true)
                .putBoolean("requireOnline", onlineOnly)
                .putBoolean("respectMessageBuffer", true)
                .build())
            .build()
        WorkManager.getInstance(app).enqueueUniqueWork(
            "lulu-coalesced-life-$characterId-$eligibleAtMillis", ExistingWorkPolicy.KEEP, request,
        )
    }

    /**
     * Durable fallback for an exact five-minute expiry boundary. The foreground
     * expiry job uses the same decision path; CompanionOnlineStore claims the
     * boundary once so these two paths never cause two paid calls.
     */
    fun scheduleOnlineExpiryDecision(context: Context, characterId: String, untilMillis: Long) {
        if (characterId.isBlank() || untilMillis <= System.currentTimeMillis()) return
        val request = OneTimeWorkRequestBuilder<ProactivePerceptionWorker>()
            .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build())
            .setInitialDelay((untilMillis - System.currentTimeMillis() + 100L).coerceAtLeast(0L), TimeUnit.MILLISECONDS)
            .setInputData(Data.Builder()
                .putString("trigger", "五分钟在线到期，自主决定继续醒着或休眠")
                .putString("characterId", characterId)
                .putBoolean("force", true)
                .putBoolean("preserveOffline", true)
                .putLong("onlineExpiryAtMillis", untilMillis)
                .build())
            .build()
        WorkManager.getInstance(context.applicationContext).enqueueUniqueWork(
            "lulu-online-expiry-v1-$characterId-$untilMillis", ExistingWorkPolicy.KEEP, request,
        )
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
     * One deferred opportunity for spontaneous reaction to an actual lived
     * event. A queue can collect multiple events without creating a model call
     * per ambient sound, and the user is never auto-contacted by this scheduler.
     */
    fun scheduleResidentEpisodeReview(context: Context, characterId: String, episodeId: String, readyAt: Instant) {
        if (!ProactivePerceptionPolicyStore.get(characterId).enabled) return
        val work = OneTimeWorkRequestBuilder<ProactivePerceptionWorker>()
            .setInitialDelay((readyAt.toEpochMilli() - System.currentTimeMillis()).coerceAtLeast(0), TimeUnit.MILLISECONDS)
            .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build())
            .setInputData(Data.Builder().putString("characterId", characterId)
                .putString("residentEpisodeId", episodeId)
                .putString("trigger", "居民小事到了可以再谈的时候，读取仍存在的真实进度，自主决定继续、暂缓或做别的；没有自动生成对方回复，也不强制联系用户。")
                .putBoolean("force", true).putBoolean("preserveOffline", true).build()).build()
        WorkManager.getInstance(context.applicationContext).enqueueUniqueWork(
            "lulu-resident-episode-$characterId-$episodeId", ExistingWorkPolicy.REPLACE, work)
    }

    fun scheduleLifeFollowThrough(context: Context, characterId: String) {
        if (characterId.isBlank() ||
            !ProactivePerceptionPolicyStore.get(characterId).enabled) return
        val app = context.applicationContext
        val prefs = app.getSharedPreferences("lulu_life_review_schedule_v1", Context.MODE_PRIVATE)
        val now = System.currentTimeMillis()
        if (now - prefs.getLong("last:$characterId", 0L) in 0L until (30L * 60_000L))
            return
        val work = OneTimeWorkRequestBuilder<ProactivePerceptionWorker>()
            .setInitialDelay(75_000L, TimeUnit.MILLISECONDS)
            .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build())
            .setInputData(Data.Builder()
                .putString("trigger", "真实生活后续回想：角色近期亲历事件，自己判断是否观察、阅读、私聊分享、发朋友圈或保持安静；不强制发消息。")
                .putString("characterId", characterId)
                .putBoolean("force", true).build())
            .build()
        WorkManager.getInstance(app).enqueueUniqueWork(
            "lulu-verified-life-review-$characterId", ExistingWorkPolicy.KEEP, work)
        prefs.edit().putLong("last:$characterId", now).apply()
    }

    /**
     * A genuine undecided personal idea gets one later autonomous reconsideration,
     * not a forced action. All resulting choices still go through the ordinary
     * ProactivePerceptionRuntime decision and ToolRouter executor.
     */
    fun scheduleOwnIdeaFollowThrough(context: Context, characterId: String) {
        if (characterId.isBlank() ||
            !ProactivePerceptionPolicyStore.get(characterId).enabled) return
        val app = context.applicationContext
        val prefs = app.getSharedPreferences("lulu_own_idea_review_v1", Context.MODE_PRIVATE)
        val now = System.currentTimeMillis()
        if (now - prefs.getLong("scheduled:$characterId", 0L) in 0L until (60L * 60_000L)) return
        val request = OneTimeWorkRequestBuilder<ProactivePerceptionWorker>()
            .setInitialDelay(90_000L, TimeUnit.MILLISECONDS)
            .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build())
            .setInputData(Data.Builder()
                .putString("trigger", "角色自己先前有过一种尚未决定的生活念头，重新看看此刻还想不想做；可以执行真实动作、换想法或放下，不要机械照旧做。")
                .putString("characterId", characterId).putBoolean("force", true).build())
            .build()
        prefs.edit().putLong("scheduled:$characterId", now).apply()
        WorkManager.getInstance(app).enqueueUniqueWork(
            "lulu-own-idea-review-$characterId", ExistingWorkPolicy.APPEND_OR_REPLACE, request,
        )
    }

    /**
     * One bounded follow-through after a VERIFIED voluntary activity. Uses the
     * very same perception/decision pipeline as chat/world/background life,
     * never a second ad-hoc "cute thought generator". If automatic perception
     * is disabled, no extra AI call is made. 30-minute throttle avoids loops.
     */
    fun scheduleActionFollowThrough(context: Context, characterId: String, activityId: String) {
        if (characterId.isBlank() || activityId.isBlank() ||
            !ProactivePerceptionPolicyStore.get(characterId).enabled) return
        val app = context.applicationContext
        val prefs = app.getSharedPreferences("lulu_self_action_reflection_v1", Context.MODE_PRIVATE)
        val now = System.currentTimeMillis()
        if (now - prefs.getLong("scheduled:$characterId", 0L) in 0L until (30L * 60_000L)) return
        val request = OneTimeWorkRequestBuilder<ProactivePerceptionWorker>()
            .setInitialDelay(40_000L, TimeUnit.MILLISECONDS)
            .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build())
            .setInputData(Data.Builder()
                .putString("trigger", "角色自己刚完成了一次真实自主生活动作，查看已存执行回执和感受；可以继续、改变想法、分享或安静，不得编造新动作。")
                .putString("characterId", characterId)
                .putBoolean("force", true).build())
            .build()
        prefs.edit().putLong("scheduled:$characterId", now).apply()
        WorkManager.getInstance(app).enqueueUniqueWork(
            "lulu-self-action-reflection-$characterId", ExistingWorkPolicy.APPEND_OR_REPLACE, request,
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
        if (characterId.isBlank() || evidenceId.isBlank()) return
        val app = context.applicationContext
        val request = OneTimeWorkRequestBuilder<ProactivePerceptionWorker>()
            .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build())
            .setInitialDelay(delayMillis.coerceAtLeast(0L), TimeUnit.MILLISECONDS)
            .setInputData(Data.Builder()
                .putString("trigger", "新发生的互动体验：电话已结束。请根据已记录的事实与个人经历自行形成感受，并决定是否沟通、继续生活或安静等待；不预设用户动机。证据ID=$evidenceId")
                .putString("characterId", characterId)
                .putString("interactionEvidenceId", evidenceId)
                .putBoolean("force", true)
                .build())
            .build()
        // Coalesce repeated calls per role, keeping the most recent cue.
        WorkManager.getInstance(app).enqueueUniqueWork(
            "lulu-interaction-reflection-$characterId", ExistingWorkPolicy.REPLACE, request,
        )
    }

    /**
     * One bounded recovery opportunity if the first appraisal model returned
     * invalid data. Separate work identity prevents self-cancel of the
     * currently running worker; its evidence is checked again at execution.
     */
    fun scheduleInteractionRetry(context: Context, characterId: String, evidenceId: String) {
        if (characterId.isBlank() || evidenceId.isBlank()) return
        val request = OneTimeWorkRequestBuilder<ProactivePerceptionWorker>()
            .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build())
            .setInitialDelay(90_000L, TimeUnit.MILLISECONDS)
            .setInputData(Data.Builder()
                .putString("trigger", "互动事件二次重试：只处理未完成的真实感知，证据ID=$evidenceId")
                .putString("characterId", characterId)
                .putString("interactionEvidenceId", evidenceId)
                .putBoolean("force", true)
                .build())
            .build()
        WorkManager.getInstance(context.applicationContext).enqueueUniqueWork(
            "lulu-interaction-retry-$characterId-${evidenceId.hashCode()}",
            ExistingWorkPolicy.KEEP, request,
        )
    }

    fun cancelOnline(context: Context, characterId: String) {
        OnlineChatBatchStore.cancel(context, characterId)
        // Include per-revision chat jobs as well as the legacy per-role chain.
        WorkManager.getInstance(context.applicationContext).cancelAllWorkByTag("$ONLINE_WORK-chat-$characterId")
        WorkManager.getInstance(context.applicationContext).cancelUniqueWork("$ONLINE_WORK-$characterId")
        WorkManager.getInstance(context.applicationContext).cancelUniqueWork("$ONLINE_WORK-reflection-$characterId")
        synchronized(this) {
            liveChatJobs.filterKeys { it.first == characterId }.values.forEach { it.cancel() }
            liveChatJobs.keys.removeAll { it.first == characterId }
        }
        ChatGenerationActivity.clearCharacter(characterId)
    }
}

class ProactivePerceptionWorker(
    appContext: Context,
    workerParameters: WorkerParameters,
) : CoroutineWorker(appContext, workerParameters) {
    override suspend fun getForegroundInfo() = BackgroundCompanionService.workerForegroundInfo(applicationContext)

    override suspend fun doWork(): Result = runCatching {
        initializeBackgroundRuntime(applicationContext)
        ProactivePerceptionRuntime.initialize(applicationContext)
        AutonomousPerceptionAttempts.recoverInterrupted(applicationContext)
        if (inputData.getBoolean("restoreSchedule", false)) {
            ProactivePerceptionScheduler.schedule(applicationContext)
            return@runCatching Result.success()
        }
        val trigger = inputData.getString("trigger").orEmpty().ifBlank { "角色时间间隔" }
        val characterId = inputData.getString("characterId")?.takeIf(String::isNotBlank)
        val force = inputData.getBoolean("force", false)
        val requireOnline = inputData.getBoolean("requireOnline", false)
        val preserveOffline = inputData.getBoolean("preserveOffline", false)
        val onlineExpiryAtMillis = inputData.getLong("onlineExpiryAtMillis", 0L)
        val goalReviewId = inputData.getString("goalReviewId").orEmpty()
        val residentEpisodeId = inputData.getString("residentEpisodeId").orEmpty()
        if (residentEpisodeId.isNotBlank() && (characterId == null ||
                !DigitalWorldResidentEpisodeStore.isReady(characterId, residentEpisodeId, Instant.now()))) {
            return@runCatching Result.success()
        }
        if (goalReviewId.isNotBlank()) {
            if (characterId == null ||
                CompanionPresenceStore.isInCall(characterId) ||
                CompanionOnlineStore.unreadChatSnapshot(characterId).text.isNotBlank() ||
                !CharacterLifeStore.claimGoalReview(
                    characterId, goalReviewId, inputData.getLong("goalReviewDueMillis", 0L))) {
                return@runCatching Result.success()
            }
            // At the same five-minute boundary, the existing autonomous
            // online-expiry appraisal already reads this persisted intention.
            // Consume only the duplicate goal check, not another paid call.
            val expiry = CompanionOnlineStore.states.value[characterId]?.onlineUntil
            val dueAt = Instant.ofEpochMilli(inputData.getLong("goalReviewDueMillis", 0L))
            if (GoalFollowUpPolicy.isCoveredByOnlineExpiry(dueAt, expiry, Instant.now())) {
                return@runCatching Result.success()
            }
        }
        val retryEvidenceId = inputData.getString("retryEvidenceId").orEmpty()
        if (characterId != null && retryEvidenceId.isNotBlank() &&
            PerceptionStimulusLedger.hasSeen(applicationContext, characterId, retryEvidenceId)) {
            return@runCatching Result.success()
        }
        if (preserveOffline && onlineExpiryAtMillis == 0L &&
            (characterId == null || CompanionOnlineStore.isOnline(characterId) ||
                (residentEpisodeId.isBlank() && !CharacterInnerLifeStore.needsPostOnlineReflection(characterId)) ||
                !ProactivePerceptionPolicyStore.get(characterId).enabled)) {
            return@runCatching Result.success()
        }
        if (requireOnline && (characterId == null || !CompanionOnlineStore.isOnline(characterId))) {
            ProactivePerceptionScheduler.scheduleNextDue(applicationContext)
            return@runCatching Result.success()
        }
        // A queued call-end/world-interaction reflection is background work,
        // never a substitute for answering a fresh message. Drop this wake
        // when the user has spoken since it was queued; the original timeline
        // event remains available to future low-priority perception.
        val interactionId = inputData.getString("interactionEvidenceId").orEmpty()
        if (characterId != null && interactionId.isNotBlank() &&
            CompanionOnlineStore.unreadChatSnapshot(characterId).text.isNotBlank()) {
            // Chat is the primary owner of live user messages, but the
            // call-end event must not disappear merely because chat is busy.
            // Retry once using the same evidence ID; the causal receipt makes
            // it idempotent if ordinary chat has already perceived the event.
            if (!CharacterCausalAppraisalStage.hasCompletedDecision(characterId, interactionId)) {
                ProactivePerceptionScheduler.scheduleInteractionRetry(
                    applicationContext, characterId, interactionId,
                )
            }
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
            // Only the primary chat-batch worker may consume unread messages.
            // A free-running reflection worker must never trigger another
            // 30K-token recall for the same still-unanswered user message.
            if (CompanionOnlineStore.unreadChatSnapshot(characterId).text.isNotBlank()) {
                return@runCatching Result.success()
            }
            while (true) {
                if (!CompanionOnlineStore.isOnline(characterId)) return@runCatching Result.success()
                val pending = OnlineChatBatchStore.pendingDueAt(applicationContext, characterId)
                val busy = MigratedDomainStores.chat.conversations.value.any {
                    it.characterId == characterId && ChatGenerationActivity.isRunning(it.id)
                } || CompanionPresenceStore.isInCall(characterId)
                if (CompanionOnlineStore.unreadChatSnapshot(characterId).text.isNotBlank()) {
                    return@runCatching Result.success()
                }
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
            requiredInteractionEvidenceId = inputData.getString("interactionEvidenceId"),
            onlineExpiryAtMillis = onlineExpiryAtMillis,
        )
        ProactivePerceptionScheduler.scheduleNextDue(applicationContext)
        Result.success()
    }.getOrElse {
        if (it is kotlinx.coroutines.CancellationException) throw it
        if (runAttemptCount < 2) Result.retry() else Result.failure()
    }.also {
        // Even a transient transport error must leave the next native/WorkManager wake intact.
        ProactivePerceptionScheduler.scheduleNextDue(applicationContext)
    }
}

/** The OS can enter here with no Activity and no initialized in-memory stores. */
class ProactivePerceptionReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        PerceptionNativeWake.invalidate(context.applicationContext)
        handOffPerceptionWake(context, goAsync(), restoreSchedule = false)
    }
}

/** Rebuild the durable watchdog and per-character timer after reboot or cover-install. */
class ProactivePerceptionBootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action in setOf(Intent.ACTION_BOOT_COMPLETED, Intent.ACTION_MY_PACKAGE_REPLACED,
                Intent.ACTION_TIME_CHANGED, Intent.ACTION_TIMEZONE_CHANGED)) {
            PerceptionNativeWake.invalidate(context.applicationContext)
            if (intent.action == Intent.ACTION_BOOT_COMPLETED || intent.action == Intent.ACTION_MY_PACKAGE_REPLACED) {
                BackgroundCompanionControl.refresh(context.applicationContext)
            }
            handOffPerceptionWake(context, goAsync(), restoreSchedule = true)
        }
    }
}

private fun handOffPerceptionWake(context: Context, pending: BroadcastReceiver.PendingResult?, restoreSchedule: Boolean) {
    val app = context.applicationContext
    CoroutineScope(SupervisorJob() + Dispatchers.IO).launch {
        try { ProactivePerceptionScheduler.enqueueWake(app, restoreSchedule) }
        catch (error: Exception) { android.util.Log.w("LuluPerception", "Background wake handoff failed", error) }
        finally { pending?.finish() }
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
    // Install the real sticker shelf at app start rather than first model
    // prompt; the background bundled-image import then has time to finish.
    com.jiacimu.lulu.StickerLibraryStore.initialize(context)
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
    LuluExternalMcp.scheduleHealth(context)
}
