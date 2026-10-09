package com.jiacimu.lulu.data

import com.jiacimu.lulu.LuluRepositories
import com.jiacimu.lulu.core.LexiconEntry
import com.jiacimu.lulu.core.LexiconSection
import com.jiacimu.lulu.core.PromiseKind
import com.jiacimu.lulu.system.LuluAlarmSystem
import java.time.Duration
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
    // A role reply arrives as multiple bubbles. Extractor can see each growing batch and
    // may re-run, but a promised wake-up must create exactly one alarm-backed task.
    val previousFromTurn = CommitmentTaskStore.snapshot(characterId).firstOrNull { task ->
        task.sourceTurnId == sourceTurnId && task.status.isActive() &&
            (task.goal.sameTaskText(draft.goal) ||
                (task.goal.contains("叫醒") && draft.goal.contains("叫醒")))
    }
    if (previousFromTurn != null) return
    val existingLexicon = LuluRepositories.lexicon.snapshot(characterId)
        .asSequence()
        .filter { it.section == LexiconSection.Promise }
        .filter { Duration.between(it.updatedAt, now).abs() <= Duration.ofMinutes(5) }
        .firstOrNull { entry -> entry.content.substringBefore("\n\n【任务进度】").sameTaskText(draft.goal) }
    val lexiconId = existingLexicon?.id ?: UUID.randomUUID().toString()
    if (existingLexicon == null) {
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
    }
    val needsClarification = draft.needsClarification || draft.dueAt == null
    var task = CommitmentTask(
        characterId = characterId,
        lexiconEntryId = lexiconId,
        sourceEventIds = sourceEventIds,
        sourceTurnId = sourceTurnId,
        goal = draft.goal,
        status = if (needsClarification) CommitmentTaskStatus.NeedsClarification else CommitmentTaskStatus.Scheduled,
        dueAt = draft.dueAt,
        timezone = draft.timezone,
        nextCheckAt = draft.dueAt,
        completionCondition = draft.completionCondition,
        steps = draft.steps,
        deliveryAction = draft.deliveryAction.takeIf { it == "start_call" } ?: "send_private_message",
        lastActionResult = if (needsClarification) "缺少明确时间或条件；需要在后续对话中自然追问，禁止擅自猜测" else "",
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
    val needsClarification = draft.needsClarification || draft.dueAt == null
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
                deliveryAction = if (draft.deliveryAction == "start_call") "start_call" else current.deliveryAction,
                linkedAlarmId = null,
                status = if (needsClarification) CommitmentTaskStatus.NeedsClarification else CommitmentTaskStatus.Scheduled,
                lastActionResult = if (needsClarification) {
                    "约定已更新，但仍缺少明确时间或条件；需要自然追问，禁止猜测"
                } else {
                    "约定时间或条件已更新；旧执行计划已取消"
                },
            )
            else -> current
        }
    }
    if (next?.status == CommitmentTaskStatus.Scheduled) scheduleTaskAlarm(next)
}

private fun findTargetTask(characterId: String, draft: CommitmentTaskDraft): CommitmentTask? {
    val active = CommitmentTaskStore.active(characterId)
    if (active.isEmpty()) return null
    draft.targetTaskId?.let { id ->
        active.firstOrNull { it.id == id }?.let { return it }
        // A model-provided target ID that no longer exists is stale evidence. Never fall through and
        // silently mutate a different responsibility.
        return null
    }
    val normalizedGoal = draft.goal.trim()
    if (normalizedGoal.isNotBlank()) {
        val semanticMatches = active.filter { task ->
            task.goal.sameTaskText(normalizedGoal) ||
                task.goal.contains(normalizedGoal, ignoreCase = true) ||
                normalizedGoal.contains(task.goal, ignoreCase = true)
        }
        if (semanticMatches.size == 1) return semanticMatches.single()
    }
    // A short user phrase like “不用了” is safe only when there is exactly one possible active
    // responsibility. With two or more tasks we leave state unchanged and let conversation clarify.
    return active.singleOrNull()
}

private fun scheduleTaskAlarm(task: CommitmentTask) {
    val dueAt = task.dueAt ?: return
    if (!dueAt.isAfter(Instant.now().plusSeconds(5))) {
        CommitmentTaskStore.update(task.id) { current ->
            current.copy(
                status = CommitmentTaskStatus.Expired,
                nextCheckAt = null,
                linkedAlarmId = null,
                lastActionResult = "约定时间已经过去，不能把补救当作准时履约",
            )
        }
        return
    }
    val character = MigratedDomainStores.characters.get(task.characterId)
    if (task.deliveryAction == "start_call" && !character.contactPolicy.proactiveCallsEnabled) {
        CommitmentTaskStore.update(task.id) { current ->
            current.copy(
                status = CommitmentTaskStatus.Blocked,
                nextCheckAt = null,
                linkedAlarmId = null,
                lastActionResult = "角色未开启主动来电，无法兑现电话约定；没有伪装为聊天提醒",
            )
        }
        return
    }
    val result = LuluAlarmSystem.create(task.characterId, character.displayName, dueAt, task.goal)
    result.onSuccess { alarm ->
        CommitmentTaskStore.update(task.id) { current ->
            current.copy(
                status = CommitmentTaskStatus.Scheduled,
                linkedAlarmId = alarm.id,
                nextCheckAt = dueAt,
                lastActionResult = if (LuluAlarmSystem.canScheduleExact()) {
                    "已在手机上登记精确闹钟；到点将触发通知与角色叫醒动作，尚未履行"
                } else {
                    "已登记非精确系统叫醒任务：手机未授予精确闹钟权限，可能延迟。请到人格页授予权限，不能保证准点"
                },
            )
        }
    }.onFailure { error ->
        CommitmentTaskStore.update(task.id) { current ->
            current.copy(
                status = CommitmentTaskStatus.Blocked,
                linkedAlarmId = null,
                lastActionResult = "安排提醒失败：${error.message.orEmpty().take(180)}",
            )
        }
    }
}

private fun String.sameTaskText(other: String): Boolean {
    val left = lowercase().replace(Regex("[\\p{P}\\p{S}\\s]+"), "")
    val right = other.lowercase().replace(Regex("[\\p{P}\\p{S}\\s]+"), "")
    if (left.isBlank() || right.isBlank()) return false
    return left == right || (left.length >= 6 && right.length >= 6 && (left.contains(right) || right.contains(left)))
}
