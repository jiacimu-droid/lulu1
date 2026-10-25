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
            "reschedule" -> updateCommitmentTask(characterId, draft, sourceEventIds, reschedule = true)
            "cancel" -> updateCommitmentTask(characterId, draft, sourceEventIds, cancel = true)
            "complete" -> updateCommitmentTask(characterId, draft, sourceEventIds, complete = true)
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
    // One accepted time slot is one task ID, even when the two parsers use
    // different wording ("十点半督促学习" versus "10:30喊她开始学习").
    // Match date as well as local time for once-only tasks; two different
    // days are not duplicates. A user asking for daily recurrence may upgrade
    // the existing one-off record rather than registering another alarm.
    val existing = CommitmentTaskStore.active(characterId).firstOrNull { task ->
        (task.sourceTurnId == sourceTurnId && task.goal.sameTaskText(draft.goal)) ||
            sameTimedReminderTask(task, draft) &&
                (task.sourceTurnId == sourceTurnId ||
                    task.repeat == "daily" || draft.repeat == "daily" ||
                    task.deliveryAction == draft.deliveryAction)
    }
    if (existing != null) {
        if (draft.repeat == "daily" && existing.repeat != "daily" &&
            sameTimedReminderTask(existing, draft)) {
            CommitmentTaskStore.update(existing.id) { current ->
                current.copy(repeat = "daily",
                    sourceEventIds = (current.sourceEventIds + sourceEventIds).distinct())
            }?.let { CommitmentLexiconSync.syncTask(it) }
        }
        return
    }
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
                promiseKind = when (draft.promiseKind) {
                    "long_term_supervision" -> PromiseKind.LongTermSupervision
                    "responsibility" -> PromiseKind.Responsibility
                    "reminder" -> PromiseKind.Reminder
                    else -> PromiseKind.Promise
                },
                createdAt = now,
                updatedAt = now,
            ),
        )
    }
    val ongoing = draft.dueAt == null && draft.promiseKind in setOf("long_term_supervision", "responsibility")
    val needsClarification = draft.needsClarification || (draft.dueAt == null && !ongoing)
    val wakeChoiceMissing = draft.dueAt != null &&
        listOf("叫醒", "起床", "喊醒", "醒来", "wake").any {
            (draft.goal + draft.completionCondition).contains(it, ignoreCase = true)
        } && draft.deliveryAction !in setOf("start_call", "alarm_only")
    var task = CommitmentTask(
        characterId = characterId,
        lexiconEntryId = lexiconId,
        sourceEventIds = sourceEventIds,
        sourceTurnId = sourceTurnId,
        goal = draft.goal,
        status = if (needsClarification) CommitmentTaskStatus.NeedsClarification
            else if (ongoing) CommitmentTaskStatus.WaitingForFeedback else CommitmentTaskStatus.Scheduled,
        dueAt = draft.dueAt,
        timezone = draft.timezone,
        nextCheckAt = draft.dueAt,
        completionCondition = draft.completionCondition,
        steps = draft.steps,
        deliveryAction = draft.deliveryAction.ifBlank { "send_private_message" },
        repeat = draft.repeat ?: "once",
        lastActionResult = if (needsClarification && wakeChoiceMissing)
            "已识别叫醒约定，但尚未选出真实来电或有声闹钟；没有登记叫醒任务"
            else if (needsClarification) "缺少必要条件，需要后续自然确认"
            else if (ongoing) "持续约定已保存，可在日程的未定时约定中查看；按真实进展跟进，未创建固定闹钟" else "",
    )
    task = CommitmentTaskStore.save(task)
    if (task.status == CommitmentTaskStatus.Scheduled) scheduleTaskAlarm(task)
    CommitmentTaskStore.snapshot(characterId).firstOrNull { it.id == task.id }?.let { CommitmentLexiconSync.syncTask(it) }
}

private suspend fun updateCommitmentTask(
    characterId: String,
    draft: CommitmentTaskDraft,
    sourceEventIds: List<String>,
    reschedule: Boolean = false,
    cancel: Boolean = false,
    complete: Boolean = false,
) {
    val target = findTargetTask(characterId, draft) ?: return
    target.linkedAlarmId?.let(LuluAlarmSystem::cancel)
    val ongoing = draft.dueAt == null && draft.promiseKind in setOf("long_term_supervision", "responsibility")
    val needsClarification = draft.needsClarification || (draft.dueAt == null && !ongoing)
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
                deliveryAction = draft.deliveryAction.ifBlank { current.deliveryAction },
                repeat = draft.repeat ?: current.repeat,
                linkedAlarmId = null,
                lastAlarmScheduledAt = null,
                executionToken = null,
                lastAlarmReceivedAt = null,
                lastAlarmDeliverySource = "",
                attemptCount = 0,
                status = if (needsClarification) CommitmentTaskStatus.NeedsClarification
                    else if (ongoing) CommitmentTaskStatus.WaitingForFeedback else CommitmentTaskStatus.Scheduled,
                lastActionResult = if (needsClarification) {
                    "约定已更新，但仍缺少明确时间或条件；需要自然追问，禁止猜测"
                } else {
                    "约定时间或条件已更新；旧执行计划已取消"
                },
            )
            else -> current
        }.let { it.copy(sourceEventIds = (it.sourceEventIds + sourceEventIds).distinct()) }
    }
    if (next?.status == CommitmentTaskStatus.Scheduled) scheduleTaskAlarm(next)
    CommitmentTaskStore.snapshot(characterId).firstOrNull { it.id == target.id }?.let { CommitmentLexiconSync.syncTask(it) }
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
    return null // Only the model-selected exact ID may mutate an existing responsibility.
}

private fun scheduleTaskAlarm(task: CommitmentTask) {
    val dueAt = task.dueAt ?: return
    // A new incoming-call alarm can never hang up the call already in progress.
    // Fail visibly instead of turning a same-session hangup request into a
    // surprise midnight callback. The live call owns its own timer.
    if (task.isMisroutedLiveHangup()) {
        CommitmentTaskStore.update(task.id) { current ->
            current.copy(
                status = CommitmentTaskStatus.Blocked,
                nextCheckAt = null,
                linkedAlarmId = null,
                lastActionResult = "当前电话的定时挂断不能使用新来电闹钟；请在通话中单独安排",
            )
        }
        return
    }
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
    val result = LuluAlarmSystem.create(task.characterId, character.displayName, dueAt, task.goal,
        commitmentTaskId = task.id)
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
        // Only AFTER this single native alarm succeeds, retire any competing
        // personal-plan worker for the same user-directed reminder, including
        // one-off study prompts. Independent character life plans are untouched.
        CommitmentTaskStore.mergeDailyDuplicates()
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

/** Exact local clock slot and shared subject; unrelated appointments stay independent. */
internal fun sameDailyClockAndPurpose(
    firstGoal: String, firstAt: Instant, secondGoal: String, secondAt: Instant,
    zone: java.time.ZoneId = java.time.ZoneId.systemDefault(),
): Boolean {
    val a = firstAt.atZone(zone)
    val b = secondAt.atZone(zone)
    if (a.hour != b.hour || a.minute != b.minute) return false
    val themes = listOf(
        listOf("睡", "晚安", "休息", "哄睡", "入眠"),
        listOf("学习", "复习", "背书", "做题"),
        listOf("起床", "叫醒", "晨起"),
    )
    return themes.any { terms ->
        terms.any(firstGoal::contains) && terms.any(secondGoal::contains)
    }
}

/** Same local date+minute for one-offs, same wall-clock for daily promises. */
internal fun sameTimedReminderTask(existing: CommitmentTask, draft: CommitmentTaskDraft): Boolean {
    val oldAt = existing.dueAt ?: return false
    val newAt = draft.dueAt ?: return false
    val zone = existing.timezone?.let { runCatching { java.time.ZoneId.of(it) }.getOrNull() }
        ?: java.time.ZoneId.systemDefault()
    val old = oldAt.atZone(zone)
    val fresh = newAt.atZone(zone)
    if (existing.repeat != "daily" && draft.repeat != "daily" &&
        old.toLocalDate() != fresh.toLocalDate()) return false
    return sameDailyClockAndPurpose(existing.goal, oldAt, draft.goal, newAt, zone)
}
