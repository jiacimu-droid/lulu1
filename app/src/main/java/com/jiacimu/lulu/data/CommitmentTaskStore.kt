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
        val next = transform(current).copy(
            id = current.id,
            characterId = current.characterId,
            createdAt = current.createdAt,
            revision = current.revision + 1L,
            updatedAt = Instant.now(),
        )
        persist(listOf(next) + mutableTasks.value.filterNot { it.id == id })
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
                        status = CommitmentTaskStatus.Expired,
                        nextCheckAt = null,
                        linkedAlarmId = null,
                        revision = task.revision + 1L,
                        updatedAt = now,
                        lastActionResult = "应用恢复时发现有限重试时点已经错过；原任务结果仍未确认，停止自动追加动作",
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
                task.status !in setOf(CommitmentTaskStatus.Cancelled, CommitmentTaskStatus.NeedsClarification) &&
                !task.dueAt.isAfter(now.minusSeconds(120)) &&
                (task.executionToken == null || inFlight[task.id] != task.executionToken)
        }
        overdue.forEach { task ->
            val zone = task.timezone?.let { runCatching { java.time.ZoneId.of(it) }.getOrNull() }
                ?: java.time.ZoneId.systemDefault()
            val next = nextDailyCommitmentDue(task.dueAt!!, now, zone)
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
                    executionToken = null, attemptCount = 0,
                    lastActionResult = "已跳过错过的时段并恢复下一次每日安排；未补打旧电话") }
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
            it.linkedAlarmId == alarmId && it.status.isActive()
        } ?: return null
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
        val deadline = scheduledAt ?: current.nextCheckAt ?: current.dueAt
        if (deadline != null && !CommitmentAlarmTiming.due(deadline, receivedAt)) {
            if (CommitmentAlarmTiming.expired(deadline, receivedAt)) update(current.id) { old -> old.copy(
                status = CommitmentTaskStatus.Expired, linkedAlarmId = null, nextCheckAt = null,
                lastAlarmScheduledAt = deadline, lastAlarmReceivedAt = receivedAt,
                lastAlarmDeliverySource = deliverySource,
                lastActionResult = "系统回调迟于约定超过两分钟；未补拨，未记作准时履约",
            ) }
            return null
        }
        val claimed = current.copy(
            status = CommitmentTaskStatus.Running,
            linkedAlarmId = null,
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

    @Synchronized
    internal fun finishAlarmExecution(taskId: String, token: String?) {
        if (inFlight[taskId] == token) inFlight.remove(taskId)
    }

    @Synchronized
    fun cancel(id: String, reason: String): CommitmentTask? {
        val current = mutableTasks.value.firstOrNull { it.id == id } ?: return null
        current.linkedAlarmId?.let(LuluAlarmSystem::cancel)
        return update(id) { task ->
            task.copy(
                status = CommitmentTaskStatus.Cancelled,
                nextCheckAt = null,
                linkedAlarmId = null,
                lastActionResult = reason,
            )
        }
    }

    /**
     * A daily incoming-call promise is ONE durable task. A completed attempt
     * advances its local clock to tomorrow, independently of whether the user
     * answered; it does not replay today's missed phone call.
     */
    @Synchronized
    internal fun advanceDailyAfterAttempt(
        context: Context,
        taskId: String,
        executionToken: String?,
        now: Instant = Instant.now(),
    ) {
        val task = mutableTasks.value.firstOrNull { it.id == taskId } ?: return
        if (task.repeat != "daily" || task.executionToken == null ||
            task.executionToken != executionToken ||
            task.status in setOf(CommitmentTaskStatus.Cancelled, CommitmentTaskStatus.Expired,
                CommitmentTaskStatus.NeedsClarification)) return
        val previousDue = task.dueAt ?: return
        val zone = task.timezone?.let { runCatching { java.time.ZoneId.of(it) }.getOrNull() }
            ?: java.time.ZoneId.systemDefault()
        val next = nextDailyCommitmentDue(previousDue, now, zone)
        task.linkedAlarmId?.let(LuluAlarmSystem::cancel)
        val allowed = task.deliveryAction != "start_call" ||
            MigratedDomainStores.characters.get(task.characterId).contactPolicy.proactiveCallsEnabled
        if (!allowed) {
            update(taskId) { it.copy(status = CommitmentTaskStatus.Blocked,
                linkedAlarmId = null, nextCheckAt = null, executionToken = null,
                lastActionResult = "下次每日来电无法登记：角色主动来电权限已关闭") }
            return
        }
        val name = MigratedDomainStores.characters.get(task.characterId).displayName
        val result = LuluAlarmSystem.create(task.characterId, name, next, task.goal,
            commitmentTaskId = task.id)
        result.onSuccess { alarm ->
            update(taskId) { current ->
                if (current.executionToken != executionToken ||
                    current.status == CommitmentTaskStatus.Cancelled) current else current.copy(
                    status = CommitmentTaskStatus.Scheduled,
                    dueAt = next,
                    nextCheckAt = next,
                    linkedAlarmId = alarm.id,
                    executionToken = null,
                    attemptCount = 0,
                    lastActionResult = current.lastActionResult.take(220) +
                        "；每日循环已登记下次时刻：${next.atZone(zone)}",
                )
            }
        }.onFailure { error ->
            update(taskId) { current -> current.copy(
                status = CommitmentTaskStatus.Blocked,
                linkedAlarmId = null, nextCheckAt = null, executionToken = null,
                lastActionResult = "每日循环下次登记失败：${error.message.orEmpty().take(150)}",
            ) }
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

    @Synchronized
    fun removeBySourceEvent(eventId: String) {
        val affected = mutableTasks.value.filter { eventId in it.sourceEventIds }
        affected.forEach { task -> task.linkedAlarmId?.let(LuluAlarmSystem::cancel) }
        persist(mutableTasks.value.filterNot { eventId in it.sourceEventIds })
    }

    @Synchronized
    fun clearCharacter(characterId: String) {
        mutableTasks.value.filter { it.characterId == characterId }
            .forEach { task -> task.linkedAlarmId?.let(LuluAlarmSystem::cancel) }
        persist(mutableTasks.value.filterNot { it.characterId == characterId })
    }

    private fun persist(values: List<CommitmentTask>) {
        val next = values.sortedByDescending(CommitmentTask::updatedAt)
        prefs?.let { check(it.edit().putString(KEY_TASKS, encodeCommitmentTasks(next)).commit()) { "定时约定未能保存" } }
        mutableTasks.value = next
    }
}
