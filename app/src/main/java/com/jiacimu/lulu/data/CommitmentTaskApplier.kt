package com.jiacimu.lulu.data

import com.jiacimu.lulu.LuluRepositories
import com.jiacimu.lulu.core.LexiconEntry
import com.jiacimu.lulu.core.LexiconSection
import com.jiacimu.lulu.core.PromiseKind
import com.jiacimu.lulu.system.LuluAlarmSystem
import java.time.Duration
import java.time.Instant
import java.util.UUID

/**
 * Per-draft execution outcome, so a failed change is a visible fact instead
 * of a silent no-op. The receipt built from these outcomes is recorded to
 * the shared timeline, where the next turn can see exactly which change
 * did not land and why, rather than a generic "nothing changed".
 */
internal data class CommitmentDraftOutcome(
    val action: String,
    val goal: String,
    val appliedTaskId: String?,
    val note: String,
)

internal suspend fun applyCommitmentTaskDrafts(
    characterId: String,
    sourceTurnId: String,
    sourceEventIds: List<String>,
    drafts: List<CommitmentTaskDraft>,
): List<CommitmentDraftOutcome> {
    return drafts.map { draft ->
        when (draft.action) {
            "create" -> createCommitmentTask(characterId, sourceTurnId, sourceEventIds, draft)
            "reschedule" -> updateCommitmentTask(characterId, draft, sourceEventIds, reschedule = true)
            "cancel" -> updateCommitmentTask(characterId, draft, sourceEventIds, cancel = true)
            "complete" -> updateCommitmentTask(characterId, draft, sourceEventIds, complete = true)
            "review" -> scheduleCommitmentReview(characterId, draft)
            else -> CommitmentDraftOutcome(draft.action, draft.goal, null, "未知的约定动作，未执行")
        }
    }
}

private suspend fun createCommitmentTask(
    characterId: String,
    sourceTurnId: String,
    sourceEventIds: List<String>,
    draft: CommitmentTaskDraft,
): CommitmentDraftOutcome {
    val now = Instant.now()
    // A role reply arrives as multiple bubbles. Extractor can see each growing batch and
    // may re-run, but a promised wake-up must create exactly one alarm-backed task.
    // One accepted time slot is one task ID, even when the two parsers use
    // different wording ("十点半督促学习" versus "10:30喊她开始学习").
    // Match date as well as local time for once-only tasks; two different
    // days are not duplicates. A user asking for daily recurrence may upgrade
    // the existing one-off record rather than registering another alarm.
    val existing = CommitmentTaskStore.active(characterId).firstOrNull { task ->
        // Do not duplicate a real alarm merely because the model rephrased
        // the same appointment or changed "once" to "daily". A different
        // outcome at the same clock is still an independent responsibility.
        matchesExistingCommitmentCreation(task, draft)
    }
    if (existing != null) {
        if (draft.startNow && sourceEventIds.any { it !in existing.sourceEventIds }) {
            val updated = CommitmentTaskStore.update(existing.id) { current ->
                current.copy(
                    sourceEventIds = (current.sourceEventIds + sourceEventIds).distinct(),
                    immediateRetryAt = now,
                    nextCheckAt = null,
                    linkedAlarmId = null,
                    followUpKind = "",
                )
            }
            if (updated != null) existing.linkedAlarmId?.let(LuluAlarmSystem::cancel)
        }
        if (draft.repeat == "daily" && existing.repeat != "daily" &&
            sameTimedReminderTask(existing, draft)) {
            CommitmentTaskStore.update(existing.id) { current ->
                current.copy(repeat = "daily",
                    dailyBaseDueAt = current.dailyBaseDueAt ?: current.dueAt,
                    sourceEventIds = (current.sourceEventIds + sourceEventIds).distinct())
            }?.let { CommitmentLexiconSync.syncTask(it) }
            return CommitmentDraftOutcome(draft.action, draft.goal, existing.id, "已有相同约定，已升级为每天，未新建")
        }
        return CommitmentDraftOutcome(draft.action, draft.goal, existing.id, "已有相同约定，未新建")
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
    // All entry points meet here: model output, primary decisions and recovery.
    // A sleeping person's wake-up duty must never schedule plain chat as its only channel.
    val wakeChoiceMissing = draft.dueAt != null &&
        listOf("叫醒", "起床", "喊醒", "醒来", "睡醒", "wake").any {
            (draft.goal + draft.completionCondition).contains(it, ignoreCase = true)
        } && draft.deliveryAction !in setOf("start_call", "alarm_only")
    val needsClarification = draft.needsClarification || wakeChoiceMissing ||
        (draft.dueAt == null && !ongoing)
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
        verificationMode = draft.verificationMode ?: "outcome",
        steps = draft.steps,
        deliveryAction = draft.deliveryAction.ifBlank { "send_private_message" },
        repeat = draft.repeat ?: "once",
        startNow = draft.startNow,
        dailyBaseDueAt = if ((draft.repeat ?: "once") == "daily") draft.dueAt else null,
        lastActionResult = if (needsClarification && wakeChoiceMissing)
            "已识别叫醒约定，但尚未选出真实来电或有声闹钟；没有登记叫醒任务"
            else if (needsClarification) "缺少必要条件，需要后续自然确认"
            else if (draft.startNow) "已承担当下责任，等待实际执行与结果证据"
            else if (ongoing) "持续约定已保存，可在日程的未定时约定中查看；按真实进展跟进，未创建固定闹钟" else "",
    )
    task = CommitmentTaskStore.save(task)
    if (task.status == CommitmentTaskStatus.Scheduled) scheduleTaskAlarm(task)
    CommitmentTaskStore.snapshot(characterId).firstOrNull { it.id == task.id }?.let { CommitmentLexiconSync.syncTask(it) }
    return CommitmentDraftOutcome(
        draft.action, draft.goal, task.id,
        task.readableLastResult().ifBlank { "已新建" },
    )
}

/**
 * A model can describe "just this time, half an hour later" with repeat=once.
 * That refers to ONE OCCURRENCE, not to the lifetime of the existing daily
 * promise. Only an explicit permanent change may remove daily recurrence.
 */
internal fun resolvedCommitmentRepeatAfterReschedule(
    original: CommitmentTask, draft: CommitmentTaskDraft,
): String = when {
    original.repeat == "daily" && draft.repeat == "once" && !draft.permanent -> "daily"
    else -> draft.repeat ?: original.repeat
}

/** Previous occurrence and its verification must survive tomorrow's correction. */
internal fun isUpcomingDailyOccurrenceCorrection(
    original: CommitmentTask, draft: CommitmentTaskDraft,
): Boolean {
    if (original.repeat != "daily" || original.dailyNextDueAt == null ||
        original.dailyNextAlarmId == null || original.dueAt == null || draft.dueAt == null)
        return false
    val zone = original.timezone?.let { runCatching { java.time.ZoneId.of(it) }.getOrNull() }
        ?: java.time.ZoneId.systemDefault()
    val nextDate = original.dailyNextDueAt.atZone(zone).toLocalDate()
    return draft.dueAt.atZone(zone).toLocalDate() == nextDate &&
        original.dueAt.atZone(zone).toLocalDate() != nextDate
}

private suspend fun reviseNextDailyOccurrence(
    oldTask: CommitmentTask, draft: CommitmentTaskDraft,
    evidenceIds: List<String>, delivery: String,
): CommitmentDraftOutcome {
    val nextDue = draft.dueAt ?: return CommitmentDraftOutcome(
        draft.action, draft.goal, null, "没有新时刻，未改变下一轮每日约定")
    val now = Instant.now()
    if (!nextDue.isAfter(now.plusSeconds(5))) return CommitmentDraftOutcome(
        draft.action, draft.goal, null, "新时刻已到或已过，原每日闹钟仍保留；需本人重新判断")
    if (delivery == "start_call" &&
        !MigratedDomainStores.characters.get(oldTask.characterId).contactPolicy.proactiveCallsEnabled)
        return CommitmentDraftOutcome(draft.action, draft.goal, null,
            "未获主动来电授权，原每日闹钟仍保留")
    val character = MigratedDomainStores.characters.get(oldTask.characterId)
    val newAlarm = LuluAlarmSystem.create(oldTask.characterId, character.displayName,
        nextDue, draft.goal.ifBlank { oldTask.goal }, commitmentTaskId = oldTask.id)
        .getOrElse { error ->
            return CommitmentDraftOutcome(draft.action, draft.goal, null,
                "下一次每日提醒改期未登记：${error.message.orEmpty().take(130)}；原闹钟保持不变")
        }
    val saved = CommitmentTaskStore.update(oldTask.id) { current ->
        if (current.dailyNextAlarmId != oldTask.dailyNextAlarmId ||
            current.dailyNextDueAt != oldTask.dailyNextDueAt) current
        else current.copy(
            dailyNextDueAt = nextDue,
            dailyNextAlarmId = newAlarm.id,
            dailyBaseDueAt = if (draft.permanent) nextDue else
                current.dailyBaseDueAt ?: current.dueAt,
            deliveryAction = delivery,
            sourceEventIds = (current.sourceEventIds + evidenceIds).distinct(),
            // Do not erase yesterday's outcome, number of attempts, review
            // callback, or the underlying long-term daily responsibility.
            lastActionResult = current.readableLastResult().take(220) +
                if (draft.permanent) "；从此每天改为新时刻" else "；只改下次每日提醒，原固定时刻不变",
        )
    }
    if (saved == null || saved.dailyNextAlarmId != newAlarm.id) {
        LuluAlarmSystem.cancel(newAlarm.id)
        return CommitmentDraftOutcome(draft.action, draft.goal, null,
            "原每日约定刚被其他决定修改；未覆盖新状态")
    }
    // The store cancelled the obsolete next-day alarm after writing the new
    // token. Yesterday's pending result-check and evidence stay intact.
    CommitmentLexiconSync.syncTask(saved)
    return CommitmentDraftOutcome(draft.action, draft.goal, saved.id,
        "已原位调整下一次每日提醒，保留上次真实回执；未新建责任")
}

private suspend fun updateCommitmentTask(
    characterId: String,
    draft: CommitmentTaskDraft,
    sourceEventIds: List<String>,
    reschedule: Boolean = false,
    cancel: Boolean = false,
    complete: Boolean = false,
): CommitmentDraftOutcome {
    // No silent drop: a change that names no existing responsibility must
    // say so in its outcome, so the receipt can tell the next turn that
    // this change did not land at all.
    val target = findTargetTask(characterId, draft)
        ?: return CommitmentDraftOutcome(
            draft.action, draft.goal, null, "没有选中已有约定的精确ID，未更改任何任务",
        )
    val ongoing = draft.dueAt == null && draft.promiseKind in setOf("long_term_supervision", "responsibility")
    val needsClarification = draft.needsClarification || (draft.dueAt == null && !ongoing)
    // An uncertain correction must not invalidate a previously registered alarm.
    // Retaining its ID while changing status to NeedsClarification stops its callback.
    if (reschedule && needsClarification) return CommitmentDraftOutcome(
        draft.action, draft.goal, null,
        "新的执行时间或必要条件尚未确认，原约定与真实闹钟保持不变；不可声称改期成功",
    )
    val requestedGoal = draft.goal.ifBlank { target.goal }
    val requestedCondition = draft.completionCondition.ifBlank { target.completionCondition }
    val timedWake = reschedule && draft.dueAt != null &&
        listOf("叫醒", "起床", "喊醒", "醒来", "睡醒", "wake").any {
            (requestedGoal + requestedCondition).contains(it, ignoreCase = true)
        }
    // A correction cannot quietly downgrade an existing alarm to a text message.
    // This preserves a previously chosen channel; it does not invent a new call.
    val resolvedDelivery = when {
        timedWake && draft.deliveryAction !in setOf("start_call", "alarm_only") &&
            target.deliveryAction in setOf("start_call", "alarm_only") -> target.deliveryAction
        draft.deliveryAction.isBlank() -> target.deliveryAction
        else -> draft.deliveryAction
    }
    if (timedWake && resolvedDelivery !in setOf("start_call", "alarm_only"))
        return CommitmentDraftOutcome(draft.action, draft.goal, null,
            "定时叫醒不能改为普通文字；没有可保留的真实来电或本机铃声渠道，原约定保持不变")
    val resolvedRepeat = resolvedCommitmentRepeatAfterReschedule(target, draft)
    // When yesterday's attempt is still waiting for proof, today's future
    // daily alarm belongs to dailyNextDueAt, NOT yesterday's dueAt/receipt.
    // Change the upcoming alarm atomically after registering its replacement.
    if (reschedule && resolvedRepeat == "daily" &&
        isUpcomingDailyOccurrenceCorrection(target, draft)) {
        return reviseNextDailyOccurrence(target, draft, sourceEventIds, resolvedDelivery)
    }
    // One-time versus permanent: a daily task's anchor is its usual time.
    // A plain postponement moves only this occurrence; only an explicit
    // permanent change moves the anchor, so tomorrow rolls back to usual.
    val newDailyBaseDueAt = when {
        resolvedRepeat != "daily" -> target.dailyBaseDueAt
        draft.permanent && draft.dueAt != null -> draft.dueAt
        else -> target.dailyBaseDueAt ?: target.dueAt
    }
    val dailyRescheduleNote = if (reschedule && resolvedRepeat == "daily" &&
        draft.dueAt != null && draft.dueAt != target.dueAt
    ) {
        if (draft.permanent) "；每天本来时刻已改为新时间" else "；本次只改当次，每天本来时刻未变"
    } else ""
    // No-gap rule: a reschedule that lands in "needs clarification" has no
    // new time to schedule, so cancelling the old alarm would leave the
    // responsibility with no alarm at all. Keep the old alarm until a clear
    // new time actually replaces it. Cancel/complete still cancel as before.
    // Failed verification must not cancel the real pending reminder/review.
    if (!complete && !(reschedule && needsClarification)) target.linkedAlarmId?.let(LuluAlarmSystem::cancel)
    if (reschedule && !needsClarification) target.dailyNextAlarmId?.let(LuluAlarmSystem::cancel)
    val next = CommitmentTaskStore.update(target.id) { current ->
        when {
            cancel -> current.copy(status = CommitmentTaskStatus.Cancelled, nextCheckAt = null, linkedAlarmId = null, followUpKind = "", lastActionResult = "用户取消了约定")
            complete -> {
                val proof = CommitmentOutcomeVerification.evaluate(
                    current, draft.completionEvidenceIds, draft.verificationReason,
                    SharedExperienceTimeline.eventsByIds(current.characterId, draft.completionEvidenceIds),
                )
                if (proof.accepted) current.copy(
                    status = CommitmentTaskStatus.Completed, nextCheckAt = null,
                    linkedAlarmId = null, followUpKind = "",
                    completionEvidenceIds = proof.evidenceIds,
                    lastVerificationReason = draft.verificationReason.take(240),
                    lastActionResult = "目标根据实际证据完成核验：${proof.evidenceIds.joinToString()}；${draft.verificationReason.take(140)}",
                ) else current.copy(
                    lastVerificationReason = proof.reason,
                    lastActionResult = "尝试结案未通过证据核验：${proof.reason}；目标仍未完成",
                )
            }
            reschedule -> current.copy(
                goal = draft.goal.ifBlank { current.goal },
                dueAt = draft.dueAt,
                startNow = current.startNow && draft.dueAt == null,
                timezone = draft.timezone ?: current.timezone,
                nextCheckAt = draft.dueAt,
                completionCondition = draft.completionCondition.ifBlank { current.completionCondition },
                // Rescheduling does not silently downgrade the original proof standard.
                verificationMode = current.verificationMode,
                steps = draft.steps.ifEmpty { current.steps },
                deliveryAction = resolvedDelivery,
                repeat = resolvedRepeat,
                dailyBaseDueAt = newDailyBaseDueAt,
                dailyNextDueAt = if (needsClarification) current.dailyNextDueAt else null,
                dailyNextAlarmId = if (needsClarification) current.dailyNextAlarmId else null,
                // When the new time is unclear, keep the old alarm's identity
                // so the kept alarm above still belongs to this task.
                linkedAlarmId = if (needsClarification) current.linkedAlarmId else null,
                followUpKind = "",
                completionEvidenceIds = emptyList(),
                lastVerificationReason = "",
                lastAlarmScheduledAt = if (needsClarification) current.lastAlarmScheduledAt else null,
                executionToken = if (needsClarification) current.executionToken else null,
                lastAlarmReceivedAt = null,
                lastAlarmDeliverySource = "",
                attemptCount = 0,
                status = if (needsClarification) CommitmentTaskStatus.NeedsClarification
                    else if (ongoing) CommitmentTaskStatus.WaitingForFeedback else CommitmentTaskStatus.Scheduled,
                // Time and wording together: when the model reschedules to a
                // new time but supplies no new title, the old title is carried
                // over verbatim, so it can still name the old time (the
                // screenshot case: a 09:30 item titled "after 8:30"). The
                // program must not rewrite the model's wording, but it can
                // record the plain fact that the title was not updated with
                // the time, so the next turn and the schedule UI can see the
                // mismatch instead of silently showing a stale title.
                lastActionResult = (if (needsClarification) {
                    "约定已更新，但仍缺少明确时间或条件；需要自然追问，禁止猜测"
                } else if (draft.goal.isBlank() && draft.dueAt != null && draft.dueAt != current.dueAt) {
                    "约定时间或条件已更新；旧执行计划已取消；标题沿用旧文字，未随时间一起更新"
                } else {
                    "约定时间或条件已更新；旧执行计划已取消"
                }) + dailyRescheduleNote,
            )
            else -> current
        }.let { it.copy(sourceEventIds = (it.sourceEventIds + sourceEventIds).distinct()) }
    }
    if (complete && next?.status == CommitmentTaskStatus.Completed)
        target.linkedAlarmId?.let(LuluAlarmSystem::cancel)
    // Parent update centrally invalidates obsolete future child actions.
    // Failed verification changes only its receipt and keeps them scheduled.
    // Only an actual reschedule may register a new alarm. A rejected
    // completion must not reschedule the existing one or claim it was applied.
    if (reschedule && next?.status == CommitmentTaskStatus.Scheduled) scheduleTaskAlarm(next)
    CommitmentTaskStore.snapshot(characterId).firstOrNull { it.id == target.id }?.let { CommitmentLexiconSync.syncTask(it) }
    val completed = next?.status == CommitmentTaskStatus.Completed
    return CommitmentDraftOutcome(
        draft.action, draft.goal,
        if (complete && !completed) null else target.id,
        next?.readableLastResult().orEmpty(),
    )
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
    val registeringAt = Instant.now()
    val needsImmediateDelivery = !dueAt.isAfter(registeringAt.plusSeconds(5))
    if (needsImmediateDelivery && CommitmentAlarmTiming.expired(dueAt, registeringAt)) {
        // The model's accepted commitment reached persistence after the original
        // OS deadline. It cannot be discarded as an inactive task: the owner
        // still needs an opportunity to decide whether it matters NOW.
        CommitmentTaskStore.update(task.id) { current ->
            current.copy(
                status = CommitmentTaskStatus.Blocked,
                nextCheckAt = null,
                linkedAlarmId = null,
                lastActionResult = "任务保存时已晚于原定投递窗口；尚未发出任何提醒或来电。" +
                    "原时刻=$dueAt；实际保存=$registeringAt；需本人重新决定补救，不能冒充准时履约",
            )
        }
        CommitmentTaskStore.handoffMissedCommitment(task.characterId, dueAt)
        return
    }
    // For a small API/network delay, keep the already chosen delivery action
    // instead of failing before an AlarmManager callback can even be registered.
    // dueAt remains the ORIGINAL promise; this is only the real OS handoff time.
    val physicalAlarmAt = if (needsImmediateDelivery) registeringAt.plusSeconds(8) else dueAt
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
    val result = LuluAlarmSystem.create(task.characterId, character.displayName, physicalAlarmAt, task.goal,
        commitmentTaskId = task.id)
    result.onSuccess { alarm ->
        CommitmentTaskStore.update(task.id) { current ->
            current.copy(
                status = CommitmentTaskStatus.Scheduled,
                linkedAlarmId = alarm.id,
                nextCheckAt = dueAt,
                lastActionResult = (if (LuluAlarmSystem.canScheduleExact()) {
                    "已在手机上登记精确闹钟；到点将触发通知与角色叫醒动作，尚未履行"
                } else {
                    "已登记非精确系统叫醒任务：手机未授予精确闹钟权限，可能延迟。请到人格页授予权限，不能保证准点"
                }) + if (needsImmediateDelivery)
                    "；原约定=$dueAt，实际登记投递=$physicalAlarmAt；执行如实标记延迟，不算准点"
                    else "",
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

/** Date annotations only clarify an already structured dueAt. They do not create
 * another obligation when the action and actual instant are identical. This
 * strips ONLY a parenthetical calendar date, never an arbitrary goal phrase. */
private fun String.sameTaskText(other: String): Boolean {
    val calendarNote = Regex("[（(]\\s*(?:20\\d{2}年)?\\d{1,2}月\\d{1,2}日\\s*[)）]")
    fun normalize(value: String): String = value.lowercase()
        .replace(calendarNote, "")
        .replace(Regex("[\\p{P}\\p{S}\\s]+"), "")
    val left = normalize(this)
    val right = normalize(other)
    return left.isNotBlank() && left == right
}

/** A short appended clarification ("准备哄睡", date annotation, etc.) does
 * not create a second appointment. This is deliberately narrower than topic:
 * two separately described study/sleep duties at 23:00 are NOT necessarily
 * the same goal. The role must use targetTaskId to revise those. */
internal fun String.nearlySameTaskText(other: String): Boolean {
    val calendarNote = Regex("[（(]\\s*(?:20\\d{2}年)?\\d{1,2}月\\d{1,2}日\\s*[)）]")
    fun normalized(value: String): String = value.lowercase()
        .replace(calendarNote, "")
        .replace("今夜", "今晚")
        .replace(Regex("[\\p{P}\\p{S}\\s]+"), "")
    val a = normalized(this)
    val b = normalized(other)
    val shorter = if (a.length <= b.length) a else b
    val longer = if (a.length > b.length) a else b
    return shorter.length >= 10 && shorter.length * 5 >= longer.length * 3 &&
        longer.startsWith(shorter)
}

/** The repeat mode is NOT the identity of a single concrete promise.
 * One daily reminder and today's once-only copy must share one task/alarm.
 * Plain "same topic" is intentionally insufficient to suppress another
 * independently verifiable action at that clock time. */
internal fun matchesExistingCommitmentCreation(
    existing: CommitmentTask, draft: CommitmentTaskDraft,
): Boolean {
    if (existing.startNow != draft.startNow ||
        existing.deliveryAction != draft.deliveryAction.ifBlank { "send_private_message" }) return false
    val oldAt = existing.dueAt
    val newAt = draft.dueAt
    if (oldAt == null || newAt == null)
        return oldAt == null && newAt == null && existing.goal.sameTaskText(draft.goal)
    val zone = existing.timezone?.let { runCatching { java.time.ZoneId.of(it) }.getOrNull() }
        ?: java.time.ZoneId.systemDefault()
    val oldLocal = oldAt.atZone(zone)
    val newLocal = newAt.atZone(zone)
    // One daily reminder can cover a one-off mention of today's occurrence.
    // For two one-offs, the actual calendar date must also match.
    val sameSlot = oldLocal.hour == newLocal.hour &&
        oldLocal.minute == newLocal.minute &&
        (existing.repeat == "daily" || draft.repeat == "daily" ||
            oldLocal.toLocalDate() == newLocal.toLocalDate())
    if (!sameSlot) return false
    return existing.goal.sameTaskText(draft.goal) ||
        existing.goal.nearlySameTaskText(draft.goal)
}

/** Conservative identity for persisted legacy rows. Do not merge two different
 * outcomes/intentions merely because they both mention studying or sleeping. */
internal fun sameRecordedTimedCommitment(first: CommitmentTask, second: CommitmentTask): Boolean =
    first.id != second.id &&
    first.characterId == second.characterId &&
    first.dueAt != null && first.dueAt == second.dueAt &&
    first.deliveryAction == second.deliveryAction &&
    first.verificationMode == second.verificationMode &&
    first.startNow == second.startNow &&
    (first.goal.sameTaskText(second.goal) ||
        first.goal.nearlySameTaskText(second.goal))

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



