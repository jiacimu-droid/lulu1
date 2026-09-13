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
    private val mutableTasks = MutableStateFlow<List<CommitmentTask>>(emptyList())
    val tasks: StateFlow<List<CommitmentTask>> = mutableTasks.asStateFlow()

    @Synchronized
    fun initialize(context: Context) {
        if (prefs != null) return
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
     * A missed scheduled step is historical failure, not permission to pretend a late rescue was on
     * time. A process that died after atomically claiming a step is left Blocked instead of replaying
     * the action, because duplicate calls/messages are worse than asking for explicit confirmation.
     */
    @Synchronized
    fun reconcileAfterRestart(now: Instant = Instant.now()) {
        var changed = false
        val next = mutableTasks.value.map { task ->
            when {
                task.status == CommitmentTaskStatus.Running -> {
                    changed = true
                    task.copy(
                        status = CommitmentTaskStatus.Blocked,
                        nextCheckAt = null,
                        linkedAlarmId = null,
                        revision = task.revision + 1L,
                        updatedAt = now,
                        lastActionResult = "应用上次在任务动作已领取后中断；无法确认附加消息/来电是否完成，为避免重复执行已停止自动重放，等待用户反馈",
                    )
                }
                task.status == CommitmentTaskStatus.Scheduled &&
                    task.dueAt?.isAfter(now) == false -> {
                    task.linkedAlarmId?.let(LuluAlarmSystem::cancel)
                    changed = true
                    task.copy(
                        status = CommitmentTaskStatus.Expired,
                        nextCheckAt = null,
                        linkedAlarmId = null,
                        revision = task.revision + 1L,
                        updatedAt = now,
                        lastActionResult = "应用恢复时发现约定时点已经错过；不能把恢复后的补救冒充准时履约",
                    )
                }
                task.status == CommitmentTaskStatus.WaitingForFeedback &&
                    task.nextCheckAt != null &&
                    !task.nextCheckAt.isAfter(now) &&
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
    }

    /**
     * Atomically claims exactly one alarm-backed step. linkedAlarmId is a one-shot execution token:
     * the first receiver switches the task to Running and clears it; duplicate broadcasts therefore
     * cannot execute the same task revision again.
     */
    @Synchronized
    fun claimAlarmExecution(alarmId: String): CommitmentTask? {
        if (alarmId.isBlank()) return null
        val current = mutableTasks.value.firstOrNull {
            it.linkedAlarmId == alarmId && it.status.isActive()
        } ?: return null
        val claimed = current.copy(
            status = CommitmentTaskStatus.Running,
            linkedAlarmId = null,
            revision = current.revision + 1L,
            updatedAt = Instant.now(),
            lastActionResult = "到期步骤已领取，正在执行",
        )
        persist(listOf(claimed) + mutableTasks.value.filterNot { it.id == current.id })
        return claimed
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
        mutableTasks.value = next
        prefs?.edit()?.putString(KEY_TASKS, encodeCommitmentTasks(next))?.commit()
    }
}
