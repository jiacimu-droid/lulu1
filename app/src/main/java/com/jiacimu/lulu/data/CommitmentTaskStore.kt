package com.jiacimu.lulu.data

import android.content.Context
import com.jiacimu.lulu.system.LuluAlarmSystem
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.time.Instant

object CommitmentTaskStore {
    private const val PREFS_NAME = "lulu_commitment_tasks"
    private const val KEY_TASKS = "tasks_v1"

    private var prefs: android.content.SharedPreferences? = null
    private var appContext: Context? = null
    private val mutableTasks = MutableStateFlow<List<CommitmentTask>>(emptyList())
    private val inFlight = mutableMapOf<String, String>()
    val tasks: StateFlow<List<CommitmentTask>> = mutableTasks.asStateFlow()

    @Synchronized
    fun initialize(context: Context) {
        if (prefs != null) return
        appContext = context.applicationContext
        prefs = context.applicationContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        mutableTasks.value = decodeCommitmentTasks(prefs?.getString(KEY_TASKS, null))
    }

    fun snapshot(characterId: String? = null): List<CommitmentTask> = mutableTasks.value
        .filter { characterId.isNullOrBlank() || it.characterId == characterId }
        .sortedWith(compareBy<CommitmentTask> { it.dueAt ?: Instant.MAX }.thenByDescending { it.updatedAt })

    fun active(characterId: String): List<CommitmentTask> = snapshot(characterId).filter { it.status.isActive() }

    @Synchronized
    fun save(task: CommitmentTask): CommitmentTask {
        val current = mutableTasks.value.firstOrNull { it.id == task.id }
        val normalized = task.copy(
            revision = current?.revision ?: task.revision.coerceAtLeast(1L),
            createdAt = current?.createdAt ?: task.createdAt,
            updatedAt = Instant.now(),
        )
        persist(listOf(normalized) + mutableTasks.value.filterNot { it.id == normalized.id })
        return normalized
    }

    @Synchronized
    fun update(id: String, transform: (CommitmentTask) -> CommitmentTask): CommitmentTask? {
        val current = mutableTasks.value.firstOrNull { it.id == id } ?: return null
        val proposed = transform(current)
        val cancelled = proposed.status == CommitmentTaskStatus.Cancelled
        val next = proposed.copy(
            id = current.id,
            characterId = current.characterId,
            createdAt = current.createdAt,
            revision = current.revision + 1L,
            updatedAt = Instant.now(),
            dailyNextAlarmId = if (cancelled) null else proposed.dailyNextAlarmId,
            dailyNextDueAt = if (cancelled) null else proposed.dailyNextDueAt,
            dailyAttempts = if (cancelled) proposed.dailyAttempts.map {
                it.copy(reviewAlarmId = null)
            } else proposed.dailyAttempts,
        )
        persist(listOf(next) + mutableTasks.value.filterNot { it.id == id })
        if (current.dailyNextAlarmId != next.dailyNextAlarmId &&
            current.dailyNextAlarmId != next.linkedAlarmId)
            current.dailyNextAlarmId?.let(LuluAlarmSystem::cancel)
        val retainedReviews = next.dailyAttempts.mapNotNull { it.reviewAlarmId }.toSet()
        current.dailyAttempts.mapNotNull { it.reviewAlarmId }
            .filterNot(retainedReviews::contains).forEach(LuluAlarmSystem::cancel)
        // Every completion/cancellation path (tool verification, model turns,
        // wake confirmation and UI) passes through this single durable store.
        // The old pending child wakeups cannot outlive their parent contract.
        if (cancelled && current.status != CommitmentTaskStatus.Cancelled) {
            // Both the UI and the role's structured cancel flow use update().
            // Stop child plans for either route without deleting real receipts.
            revokePendingLinkedPlans(id, "原约定已取消，关联未来行动一并停止")
        } else if (current.invalidatesUndatedFollowUps(next)) {
            revokePendingLinkedPlans(id,
                "原约定已改变或不再需要后续执行；保留已经发生的结果")
        }
        return next
    }

    /**
     * App/boot recovery only. Do not call this from the alarm receiver itself: a receiver may be the
     * process entry point exactly when a due alarm fires, and must get a chance to claim that token.
     *
     * Opening the Activity is not proof of process death. Keep live executions and briefly due
     * tokens intact, including when a full-screen call notification starts that Activity.
     * An abandoned claimed step is blocked rather than blindly dialing twice.
     */
    @Synchronized
    fun reconcileAfterRestart(now: Instant = Instant.now()) {
        var changed = false
        // These are real missed deliveries, not inferred from goal wording.
        // Preserve their original due times while the daily task advances.
        val missedByRole = linkedMapOf<String, Instant>()
        val next = mutableTasks.value.map { task ->
            when {
                // Old releases could schedule a new call for an instruction to
                // END the currently connected call. Revoke that stale alarm on
                // upgrade without deleting the original conversation evidence.
                task.status == CommitmentTaskStatus.Scheduled &&
                    task.isMisroutedLiveHangup() -> {
                    task.linkedAlarmId?.let(LuluAlarmSystem::cancel)
                    changed = true
                    task.copy(
                        status = CommitmentTaskStatus.Blocked,
                        nextCheckAt = null,
                        linkedAlarmId = null,
                        revision = task.revision + 1L,
                        updatedAt = now,
                        lastActionResult = "旧版误将当前电话的挂断登记为新来电；已取消错误闹钟，请在通话中重新安排",
                    )
                }
                task.status == CommitmentTaskStatus.Running &&
                    (task.executionToken == null || inFlight[task.id] != task.executionToken) -> {
                    changed = true
                    val pending = ProactiveIncomingCallStore.pending.value
                        ?.takeIf { it.commitmentTaskId == task.id && it.active(now) }
                    task.copy(
                        status = if (pending != null) CommitmentTaskStatus.WaitingForFeedback else CommitmentTaskStatus.Blocked,
                        nextCheckAt = null,
                        linkedAlarmId = null,
                        revision = task.revision + 1L,
                        updatedAt = now,
                        lastActionResult = if (pending != null) "已恢复此前实际发出的来电，等待接听；没有重新拨号"
                            else "应用上次在任务动作已领取后中断；无法确认附加消息/来电是否完成，为避免重复执行已停止自动重放，等待用户反馈",
                    )
                }
                task.status == CommitmentTaskStatus.Scheduled &&
                    task.dueAt?.let { CommitmentAlarmTiming.expired(it, now) } == true -> {
                    task.dueAt?.let { missedByRole.putIfAbsent(task.characterId, it) }
                    task.linkedAlarmId?.let(LuluAlarmSystem::cancel)
                    changed = true
                    task.copy(
                        status = CommitmentTaskStatus.Expired,
                        nextCheckAt = null,
                        linkedAlarmId = null,
                        revision = task.revision + 1L,
                        updatedAt = now,
                        lastActionResult = "恢复时已超出定时执行窗口，未收到本次到点执行回执；未补拨，也未记作履约",
                    )
                }
                task.status == CommitmentTaskStatus.WaitingForFeedback &&
                    task.nextCheckAt != null &&
                    CommitmentAlarmTiming.expired(task.nextCheckAt, now) &&
                    task.linkedAlarmId != null -> {
                    task.linkedAlarmId.let(LuluAlarmSystem::cancel)
                    changed = true
                    task.copy(
                        // Missing a passive review must not expire the underlying
                        // care promise. No late extra call is silently placed.
                        status = if (task.followUpKind == "verification")
                            CommitmentTaskStatus.WaitingForFeedback else CommitmentTaskStatus.Expired,
                        nextCheckAt = null,
                        linkedAlarmId = null,
                        followUpKind = "",
                        revision = task.revision + 1L,
                        updatedAt = now,
                        lastActionResult = "应用恢复时发现复查或有限重试时点已经错过；目标结果仍未确认，不擅自补拨；等待角色重新安排",
                    )
                }
                else -> task
            }
        }
        if (changed) persist(next)
        // One-time migration of unequivocal duplicate daily self-plan/call tasks.
        // No chat events or independent commitments are deleted.
        appContext?.let {
            RoleDayScheduleStore.mergeMatchingDailyCallPlans(it)
            restoreMissedDailyCalls(it, now)
            mutableTasks.value.filter { task ->
                task.repeat == "daily" && task.dueAt != null &&
                    task.status in setOf(CommitmentTaskStatus.WaitingForFeedback,
                        CommitmentTaskStatus.Blocked, CommitmentTaskStatus.Completed,
                        CommitmentTaskStatus.Expired) &&
                    (task.dailyNextAlarmId == null ||
                        task.dailyNextDueAt?.let { due -> CommitmentAlarmTiming.expired(due, now) } == true ||
                        task.dailyNextAlarmId?.let { id -> LuluAlarmSystem.list().none { it.id == id } } == true ||
                        task.status == CommitmentTaskStatus.Completed)
            }.forEach { task -> ensureNextDailyOccurrence(it, task.id, now) }
            // A delivered nightly reminder with unknown outcome cannot be
            // silently treated as the next day's already-covered obligation.
            // Wake the SAME role for a bounded recovery choice, not an
            // automatic new message, call, or invented sleep confirmation.
            mutableTasks.value.filter { task ->
                task.needsUnverifiedDailyRecovery(now)
            }.map(CommitmentTask::characterId).distinct().forEach { role ->
                ProactivePerceptionScheduler.scheduleUnfinishedSleepHandoff(it, role)
            }
            missedByRole.forEach { (characterId, missedAt) ->
                runCatching {
                    ProactivePerceptionScheduler.scheduleMissedCommitmentReview(
                        it, characterId, missedAt)
                }.onFailure { error ->
                    android.util.Log.w("LuluCommitment",
                        "Missed commitment decision handoff could not be queued for $characterId", error)
                }
            }
        }
    }

    /**
     * Never replay an alarm missed while the app was stopped. The recurrence
     * itself survives restart: move directly to the next future local slot.
     * An in-flight call is left intact, even when the Activity reopens.
     */
    @Synchronized
    private fun restoreMissedDailyCalls(context: Context, now: Instant) {
        val overdue = mutableTasks.value.filter { task ->
            task.repeat == "daily" && task.dueAt != null &&
                // A pending effect-verification has not finished this night.
                // Only a completed/expired initial attempt may advance the
                // daily clock; otherwise the original responsibility vanishes.
                task.status in setOf(CommitmentTaskStatus.Completed, CommitmentTaskStatus.Expired) &&
                !task.dueAt.isAfter(now.minusSeconds(120)) &&
                (task.executionToken == null || inFlight[task.id] != task.executionToken) &&
                task.dailyNextAlarmId == null
        }
        overdue.forEach { task ->
            val zone = task.timezone?.let { runCatching { java.time.ZoneId.of(it) }.getOrNull() }
                ?: java.time.ZoneId.systemDefault()
            // Roll from the daily anchor, not from a one-off override,
            // so a single postponed day does not permanently move the task.
            val next = nextDailyCommitmentDue(task.dailyBaseDueAt ?: task.dueAt!!, now, zone)
            task.linkedAlarmId?.let(LuluAlarmSystem::cancel)
            val character = MigratedDomainStores.characters.get(task.characterId)
            if (task.deliveryAction == "start_call" &&
                !character.contactPolicy.proactiveCallsEnabled) {
                update(task.id) { old -> old.copy(status = CommitmentTaskStatus.Blocked,
                    linkedAlarmId = null, nextCheckAt = null, executionToken = null,
                    lastActionResult = "每日来电权限关闭；没有登记新的电话") }
                return@forEach
            }
            LuluAlarmSystem.create(task.characterId, character.displayName, next,
                task.goal, commitmentTaskId = task.id).onSuccess { alarm ->
                update(task.id) { old -> old.copy(status = CommitmentTaskStatus.Scheduled,
                    dueAt = next, nextCheckAt = next, linkedAlarmId = alarm.id,
                    dailyAttempts = old.dailyAttempts + DailyCommitmentAttempt(
                        old.dueAt!!, old.status.name, old.readableLastResult(),
                        old.attemptCount),
                    executionToken = null, attemptCount = 0,
                    wakeAttemptHistory = emptyList(),
                    lastActionResult = "已恢复下一次每日安排；上一轮时间=${task.dueAt?.atZone(zone)}；" +
                        "上一轮状态=${task.status}；上一轮真实回执=${task.lastActionResult.take(160)}；" +
                        "本次没有补拨旧电话，也不能据此认定主人已经睡着") }
            }.onFailure { error ->
                update(task.id) { old -> old.copy(status = CommitmentTaskStatus.Blocked,
                    linkedAlarmId = null, nextCheckAt = null, executionToken = null,
                    lastActionResult = "每日安排恢复失败：${error.message.orEmpty().take(120)}") }
            }
        }
    }

    /**
     * Atomically claims exactly one alarm-backed step. linkedAlarmId is a one-shot execution token:
     * the first receiver switches the task to Running and clears it; duplicate broadcasts therefore
     * cannot execute the same task revision again.
     */
    @Synchronized
    fun claimAlarmExecution(
        alarmId: String,
        scheduledAt: Instant? = null,
        receivedAt: Instant = Instant.now(),
        deliverySource: String = "系统闹钟",
    ): CommitmentTask? {
        if (alarmId.isBlank()) return null
        val current = mutableTasks.value.firstOrNull {
            (it.linkedAlarmId == alarmId && it.status.isActive()) ||
                (it.repeat == "daily" && it.dailyNextAlarmId == alarmId &&
                    it.status != CommitmentTaskStatus.Cancelled)
        } ?: return null
        val isNewDaily = current.dailyNextAlarmId == alarmId
        // The alarm receiver can launch the process before Activity recovery.
        // Never let a legacy misrouted alarm trigger an unwanted new call.
        if (current.isMisroutedLiveHangup()) {
            current.linkedAlarmId?.let(LuluAlarmSystem::cancel)
            update(current.id) { task -> task.copy(
                status = CommitmentTaskStatus.Blocked, linkedAlarmId = null, nextCheckAt = null,
                lastActionResult = "旧版错误定时挂断已阻止，不能使用来电闹钟挂断当前电话",
            ) }
            return null
        }
        val deadline = scheduledAt ?: if (isNewDaily) current.dailyNextDueAt
            else current.nextCheckAt ?: current.dueAt
        if (deadline != null && !CommitmentAlarmTiming.due(deadline, receivedAt)) {
            if (CommitmentAlarmTiming.expired(deadline, receivedAt)) {
                if (isNewDaily) {
                    update(current.id) { old -> old.copy(
                        dailyNextDueAt = null, dailyNextAlarmId = null,
                        dailyAttempts = old.dailyAttempts + DailyCommitmentAttempt(
                            deadline, "Expired", "下一次每日闹钟逾期，未补做或假称完成", 0),
                        lastActionResult = old.lastActionResult +
                            "；${deadline} 的每日执行因系统回调过期而错过",
                    ) }
                    appContext?.let { ensureNextDailyOccurrence(it, current.id, receivedAt) }
                } else update(current.id) { old -> old.copy(
                    status = if (old.followUpKind == "verification")
                        CommitmentTaskStatus.WaitingForFeedback else CommitmentTaskStatus.Expired,
                    linkedAlarmId = null, nextCheckAt = null, followUpKind = "",
                    lastAlarmScheduledAt = deadline, lastAlarmReceivedAt = receivedAt,
                    lastAlarmDeliverySource = deliverySource,
                    lastActionResult = "系统回调迟于约定超过两分钟；未补拨，目标完成情况仍未知，需另行安排",
                ) }
            }
            return null
        }
        // Tomorrow is a distinct occurrence, even when yesterday's evidence
        // remains unknown or its explicitly selected review is still pending.
        val keepReview = isNewDaily && current.followUpKind == "verification" &&
            current.linkedAlarmId != null && current.nextCheckAt?.isAfter(receivedAt) == true
        val past = if (isNewDaily && current.dueAt != null)
            DailyCommitmentAttempt(current.dueAt, current.status.name,
                current.readableLastResult(), current.attemptCount,
                if (keepReview) current.linkedAlarmId else null,
                if (keepReview) current.nextCheckAt else null)
            else null
        if (isNewDaily && !keepReview) current.linkedAlarmId?.let(LuluAlarmSystem::cancel)
        val claimed = current.copy(
            status = CommitmentTaskStatus.Running,
            dueAt = if (isNewDaily) deadline else current.dueAt,
            dailyNextDueAt = if (isNewDaily) null else current.dailyNextDueAt,
            dailyNextAlarmId = if (isNewDaily) null else current.dailyNextAlarmId,
            dailyAttempts = if (past == null) current.dailyAttempts else current.dailyAttempts + past,
            linkedAlarmId = null,
            nextCheckAt = if (isNewDaily) null else current.nextCheckAt,
            followUpKind = if (isNewDaily) "" else current.followUpKind,
            attemptCount = if (isNewDaily) 0 else current.attemptCount,
            wakeAttemptHistory = if (isNewDaily) emptyList() else current.wakeAttemptHistory,
            completionEvidenceIds = if (isNewDaily) emptyList() else current.completionEvidenceIds,
            lastVerificationReason = if (isNewDaily) "" else current.lastVerificationReason,
            executionToken = alarmId,
            revision = current.revision + 1L,
            updatedAt = receivedAt,
            lastAlarmScheduledAt = deadline,
            lastAlarmReceivedAt = receivedAt,
            lastAlarmDeliverySource = deliverySource,
            lastActionResult = "到期步骤已领取，正在执行",
        )
        inFlight[current.id] = alarmId
        try {
            persist(listOf(claimed) + mutableTasks.value.filterNot { it.id == current.id })
        } catch (error: Exception) {
            inFlight.remove(current.id)
            throw error
        }
        return claimed
    }

    /** A late verification callback cannot consume another day's action token. */
    @Synchronized
    internal fun claimPastDailyReview(alarmId: String, scheduledAt: Instant,
        receivedAt: Instant): Pair<CommitmentTask, DailyCommitmentAttempt>? {
        val task = mutableTasks.value.firstOrNull { old ->
            old.status != CommitmentTaskStatus.Cancelled &&
                old.dailyAttempts.any { it.reviewAlarmId == alarmId }
        } ?: return null
        val past = task.dailyAttempts.firstOrNull { it.reviewAlarmId == alarmId } ?: return null
        update(task.id) { current -> current.copy(dailyAttempts = current.dailyAttempts.map { old ->
            if (old.reviewAlarmId == alarmId) old.copy(reviewAlarmId = null,
                reviewReceivedAt = receivedAt) else old
        }) }
        return if (past.reviewAt == scheduledAt &&
            CommitmentAlarmTiming.due(scheduledAt, receivedAt)) task to past else null
    }

    @Synchronized
    internal fun finishAlarmExecution(taskId: String, token: String?) {
        if (inFlight[taskId] == token) inFlight.remove(taskId)
    }

    @Synchronized
    fun cancel(id: String, reason: String): CommitmentTask? {
        val current = mutableTasks.value.firstOrNull { it.id == id } ?: return null
        current.linkedAlarmId?.let(LuluAlarmSystem::cancel)
        val cancelled = update(id) { task ->
            task.copy(
                status = CommitmentTaskStatus.Cancelled,
                nextCheckAt = null,
                linkedAlarmId = null,
                lastActionResult = reason,
            )
        }
        return cancelled
    }

    /** Parent changes revoke only pending executions; completed evidence stays. */
    internal fun revokePendingLinkedPlans(id: String, reason: String) {
        val task = mutableTasks.value.firstOrNull { it.id == id } ?: return
        appContext?.let { context ->
            RoleDayScheduleStore.cancelPendingLinkedCommitmentPlans(
                context, task.characterId, id, reason)
        }
    }

    /**
     * A recurring authorized clock is independent of the result verification.
     * Both belong to this one existing task ID, never to two fake promises.
     */
    @Synchronized
    internal fun advanceDailyAfterAttempt(
        context: Context, taskId: String, executionToken: String?,
        now: Instant = Instant.now(), outcomeReview: Boolean = false,
    ) {
        if (outcomeReview) return
        val task = mutableTasks.value.firstOrNull { it.id == taskId } ?: return
        if (task.repeat != "daily" || task.executionToken == null ||
            task.executionToken != executionToken) return
        ensureNextDailyOccurrence(context, taskId, now)
    }

    @Synchronized
    internal fun ensureNextDailyOccurrence(context: Context, taskId: String,
        now: Instant = Instant.now(),
    ) {
        val task = mutableTasks.value.firstOrNull { it.id == taskId } ?: return
        if (task.repeat != "daily" || task.dueAt == null ||
            task.status in setOf(CommitmentTaskStatus.Cancelled, CommitmentTaskStatus.NeedsClarification))
            return
        if (task.status == CommitmentTaskStatus.Scheduled && task.linkedAlarmId != null)
            return

        val existing = task.dailyNextAlarmId
        if (existing != null && task.dailyNextDueAt?.let { !CommitmentAlarmTiming.expired(it, now) } == true &&
            LuluAlarmSystem.list().any { it.id == existing && it.characterId == task.characterId &&
                it.commitmentTaskId == task.id }) {
            if (task.status == CommitmentTaskStatus.Completed) {
                update(taskId) { old -> old.copy(
                    status = CommitmentTaskStatus.Scheduled, dueAt = old.dailyNextDueAt,
                    nextCheckAt = old.dailyNextDueAt, linkedAlarmId = old.dailyNextAlarmId,
                    dailyAttempts = old.dailyAttempts + DailyCommitmentAttempt(
                        old.dueAt!!, old.status.name, old.readableLastResult(), old.attemptCount),
                    dailyNextDueAt = null, dailyNextAlarmId = null,
                    executionToken = null, attemptCount = 0, followUpKind = "",
                    completionEvidenceIds = emptyList(), lastVerificationReason = "",
                    wakeAttemptHistory = emptyList(),
                    lastActionResult = old.lastActionResult.take(240) +
                        "；已履行的一轮留历史，下次每日行动依原时刻继续")
                }
            }
            return
        }
        if (existing != null) {
            LuluAlarmSystem.cancel(existing)
            update(taskId) { old ->
                val skipped = old.dailyNextDueAt
                    ?.takeIf { CommitmentAlarmTiming.expired(it, now) }
                old.copy(dailyNextDueAt = null, dailyNextAlarmId = null,
                    dailyAttempts = if (skipped == null) old.dailyAttempts
                        else old.dailyAttempts + DailyCommitmentAttempt(
                            skipped, "Expired", "设备恢复时已错过本轮每日时刻；未补发、未算履行", 0))
            }
        }
        val zone = task.timezone?.let { runCatching { java.time.ZoneId.of(it) }.getOrNull() }
            ?: java.time.ZoneId.systemDefault()
        val next = nextDailyCommitmentDue(task.dailyBaseDueAt ?: task.dueAt, now, zone)
        if (task.deliveryAction == "start_call" &&
            !MigratedDomainStores.characters.get(task.characterId).contactPolicy.proactiveCallsEnabled) {
            update(taskId) { old -> old.copy(lastActionResult =
                old.lastActionResult.take(220) + "；下一次每日来电未登记：主动来电权限关闭") }
            return
        }
        val characterName = MigratedDomainStores.characters.get(task.characterId).displayName
        LuluAlarmSystem.create(task.characterId, characterName, next, task.goal,
            commitmentTaskId = task.id).onSuccess { alarm ->
            val latest = mutableTasks.value.firstOrNull { it.id == taskId }
            if (latest == null || latest.status == CommitmentTaskStatus.Cancelled ||
                latest.dailyNextAlarmId != null) {
                LuluAlarmSystem.cancel(alarm.id)
                return@onSuccess
            }
            update(taskId) { current ->
                if (current.status in setOf(CommitmentTaskStatus.Completed, CommitmentTaskStatus.Expired))
                    current.copy(status = CommitmentTaskStatus.Scheduled,
                        dueAt = next, nextCheckAt = next, linkedAlarmId = alarm.id,
                        dailyAttempts = current.dailyAttempts + DailyCommitmentAttempt(
                            current.dueAt!!, current.status.name,
                            current.readableLastResult(), current.attemptCount),
                        executionToken = null, attemptCount = 0, followUpKind = "",
                        completionEvidenceIds = emptyList(), lastVerificationReason = "",
                        wakeAttemptHistory = emptyList(),
                        lastActionResult = current.lastActionResult.take(240) +
                            "；下一次每日执行已登记，上次真实回执保持在历史")
                else current.copy(dailyNextDueAt = next, dailyNextAlarmId = alarm.id)
            }
        }.onFailure { error ->
            update(taskId) { old -> old.copy(lastActionResult =
                old.lastActionResult.take(220) +
                    "；下次每日执行登记失败：" + error.message.orEmpty().take(120)) }
        }
    }

    /** Keep the daily self-plan and clock reminder stores from double-firing. */
    internal fun mergeDailyDuplicates() {
        appContext?.let(RoleDayScheduleStore::mergeMatchingDailyCallPlans)
    }

    /** Removing a 辞海 promise removes the executable obligation and alarm. */
    @Synchronized
    fun remove(id: String): Boolean {
        val current = mutableTasks.value.firstOrNull { it.id == id } ?: return false
        current.linkedAlarmId?.let(LuluAlarmSystem::cancel)
        current.dailyNextAlarmId?.let(LuluAlarmSystem::cancel)
        current.dailyAttempts.mapNotNull { it.reviewAlarmId }.forEach(LuluAlarmSystem::cancel)
        appContext?.let { RoleDayScheduleStore.removeLinkedCommitmentPlans(it, current.characterId, id) }
        persist(mutableTasks.value.filterNot { it.id == id })
        return true
    }

    /** Rephrasing an already-delivered promise does not undo its real alarm or call plan. */
    @Synchronized fun rebindReplySources(oldIds: Set<String>, newId: String) {
        if (newId.isBlank()) return
        val next = mutableTasks.value.map { task ->
            if (task.sourceEventIds.none(oldIds::contains)) task else task.copy(
                sourceEventIds = (task.sourceEventIds.filterNot(oldIds::contains) + newId).distinct(),
            )
        }
        persist(next)
    }

    /** A deleted/revised witness cannot keep an outcome falsely marked verified. */
    @Synchronized
    internal fun invalidateCompletionEvidence(eventId: String) {
        mutableTasks.value.filter { it.status == CommitmentTaskStatus.Completed &&
            eventId in it.completionEvidenceIds }.forEach { task ->
            update(task.id) { old -> old.copy(
                status = CommitmentTaskStatus.WaitingForFeedback,
                completionEvidenceIds = emptyList(),
                lastVerificationReason = "原核验证据已被撤回或修改，需要重新判断",
                lastActionResult = "原核验证据已失效，重新进入待核实；不自动重拨或重启旧闹钟",
            ) }
        }
    }

    @Synchronized
    fun removeBySourceEvent(eventId: String) {
        val affected = mutableTasks.value.filter { eventId in it.sourceEventIds }
        affected.forEach { task ->
            task.linkedAlarmId?.let(LuluAlarmSystem::cancel)
            task.dailyNextAlarmId?.let(LuluAlarmSystem::cancel)
            task.dailyAttempts.mapNotNull { it.reviewAlarmId }.forEach(LuluAlarmSystem::cancel)
        }
        persist(mutableTasks.value.filterNot { eventId in it.sourceEventIds })
    }

    @Synchronized
    fun clearCharacter(characterId: String) {
        mutableTasks.value.filter { it.characterId == characterId }
            .forEach { task ->
                task.linkedAlarmId?.let(LuluAlarmSystem::cancel)
                task.dailyNextAlarmId?.let(LuluAlarmSystem::cancel)
                task.dailyAttempts.mapNotNull { it.reviewAlarmId }.forEach(LuluAlarmSystem::cancel)
            }
        persist(mutableTasks.value.filterNot { it.characterId == characterId })
    }

    private fun persist(values: List<CommitmentTask>) {
        val next = values.sortedByDescending(CommitmentTask::updatedAt)
        prefs?.let { check(it.edit().putString(KEY_TASKS, encodeCommitmentTasks(next)).commit()) { "定时约定未能保存" } }
        mutableTasks.value = next
    }
}

