package com.jiacimu.lulu.data

import com.jiacimu.lulu.LuluRepositories
import com.jiacimu.lulu.core.LexiconEntry
import com.jiacimu.lulu.core.LexiconSection
import com.jiacimu.lulu.core.PromiseKind
import com.jiacimu.lulu.system.LuluAlarmSystem
import java.time.Instant
import java.util.UUID

internal suspend fun applyCommitmentTaskDrafts(
    characterId: String,
    sourceTurnId: String,
    sourceEventIds: List<String>,
    drafts: List<CommitmentTaskDraft>,
) {
    drafts.forEach { draft ->
        when (draft.action) {
            "create" -> createCommitmentTask(characterId, sourceTurnId, sourceEventIds, draft)
            "reschedule" -> updateCommitmentTask(characterId, draft, reschedule = true)
            "cancel" -> updateCommitmentTask(characterId, draft, cancel = true)
            "complete" -> updateCommitmentTask(characterId, draft, complete = true)
        }
    }
}

private suspend fun createCommitmentTask(
    characterId: String,
    sourceTurnId: String,
    sourceEventIds: List<String>,
    draft: CommitmentTaskDraft,
) {
    val now = Instant.now()
    val lexiconId = UUID.randomUUID().toString()
    LuluRepositories.lexicon.save(
        LexiconEntry(
            id = lexiconId,
            characterId = characterId,
            section = LexiconSection.Promise,
            title = draft.goal.take(24).ifBlank { "新的约定" },
            content = draft.goal,
            promiseKind = PromiseKind.Promise,
            createdAt = now,
            updatedAt = now,
        ),
    )
    var task = CommitmentTask(
        characterId = characterId,
        lexiconEntryId = lexiconId,
        sourceEventIds = sourceEventIds,
        sourceTurnId = sourceTurnId,
        goal = draft.goal,
        status = if (draft.needsClarification || draft.dueAt == null) CommitmentTaskStatus.NeedsClarification else CommitmentTaskStatus.Scheduled,
        dueAt = draft.dueAt,
        timezone = draft.timezone,
        nextCheckAt = draft.dueAt,
        completionCondition = draft.completionCondition,
        steps = draft.steps,
    )
    task = CommitmentTaskStore.save(task)
    if (task.status == CommitmentTaskStatus.Scheduled) scheduleTaskAlarm(task)
}

private fun updateCommitmentTask(
    characterId: String,
    draft: CommitmentTaskDraft,
    reschedule: Boolean = false,
    cancel: Boolean = false,
    complete: Boolean = false,
) {
    val target = findTargetTask(characterId, draft) ?: return
    target.linkedAlarmId?.let(LuluAlarmSystem::cancel)
    val next = CommitmentTaskStore.update(target.id) { current ->
        when {
            cancel -> current.copy(status = CommitmentTaskStatus.Cancelled, nextCheckAt = null, linkedAlarmId = null, lastActionResult = "用户取消了约定")
            complete -> current.copy(status = CommitmentTaskStatus.Completed, nextCheckAt = null, linkedAlarmId = null, lastActionResult = "用户确认目标已完成")
            reschedule -> current.copy(
                goal = draft.goal.ifBlank { current.goal },
                dueAt = draft.dueAt,
                timezone = draft.timezone ?: current.timezone,
                nextCheckAt = draft.dueAt,
                completionCondition = draft.completionCondition.ifBlank { current.completionCondition },
                steps = draft.steps.ifEmpty { current.steps },
                linkedAlarmId = null,
                status = if (draft.needsClarification || draft.dueAt == null) CommitmentTaskStatus.NeedsClarification else CommitmentTaskStatus.Scheduled,
                lastActionResult = "约定时间或条件已更新",
            )
            else -> current
        }
    }
    if (next?.status == CommitmentTaskStatus.Scheduled) scheduleTaskAlarm(next)
}

private fun findTargetTask(characterId: String, draft: CommitmentTaskDraft): CommitmentTask? {
    val active = CommitmentTaskStore.active(characterId)
    draft.targetTaskId?.let { id -> active.firstOrNull { it.id == id }?.let { return it } }
    val key = draft.goal.filterNot(Char::isWhitespace).take(10)
    return active.firstOrNull { key.isNotBlank() && it.goal.contains(key, ignoreCase = true) } ?: active.firstOrNull()
}

private fun scheduleTaskAlarm(task: CommitmentTask) {
    val dueAt = task.dueAt ?: return
    if (!dueAt.isAfter(Instant.now().plusSeconds(5))) return
    val character = MigratedDomainStores.characters.get(task.characterId)
    val alarm = LuluAlarmSystem.create(task.characterId, character.displayName, dueAt, task.goal).getOrNull() ?: return
    CommitmentTaskStore.update(task.id) { current ->
        current.copy(linkedAlarmId = alarm.id, lastActionResult = "已安排提醒；任务仍等待实际完成")
    }
}
