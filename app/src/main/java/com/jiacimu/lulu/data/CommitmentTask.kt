package com.jiacimu.lulu.data

import java.time.Instant
import java.time.Duration
import java.time.format.DateTimeFormatter
import java.time.LocalDate
import java.time.ZoneId
import java.util.UUID

/** An earlier daily occurrence is not erased when the next authorized day starts. */
data class DailyCommitmentAttempt(
    val dueAt: Instant,
    val status: String,
    val result: String,
    val attemptCount: Int,
    val reviewAlarmId: String? = null,
    val reviewAt: Instant? = null,
    val reviewReceivedAt: Instant? = null,
)

data class CommitmentTask(
    val id: String = UUID.randomUUID().toString(),
    val characterId: String,
    val lexiconEntryId: String? = null,
    val sourceEventIds: List<String> = emptyList(),
    val sourceTurnId: String? = null,
    val goal: String,
    val status: CommitmentTaskStatus = CommitmentTaskStatus.NeedsClarification,
    val dueAt: Instant? = null,
    val timezone: String? = null,
    val nextCheckAt: Instant? = null,
    val completionCondition: String = "",
    /** Result evidence is never interchangeable with a reminder/call attempt. */
    val verificationMode: String = "outcome",
    val completionEvidenceIds: List<String> = emptyList(),
    val lastVerificationReason: String = "",
    val steps: List<String> = emptyList(),
    /** Concrete action promised by the role; a call must not silently become a chat message. */
    val deliveryAction: String = "send_private_message",
    /** Recurring clock commitments keep the same task ID; one-shot is legacy default. */
    val repeat: String = "once",
    /**
     * The recurring wall-clock anchor for a daily task. A one-time
     * reschedule changes dueAt only and leaves this untouched, so the next
     * day rolls back to the usual time; only a permanent change moves it.
     * Null for one-time tasks and for daily tasks saved before this field.
     */
    val dailyBaseDueAt: Instant? = null,
    /** Next daily action is independent of the current occurrence's review. */
    val dailyNextDueAt: Instant? = null,
    val dailyNextAlarmId: String? = null,
    val dailyAttempts: List<DailyCommitmentAttempt> = emptyList(),
    val currentStep: Int = 0,
    val attemptCount: Int = 0,
    val lastActionResult: String = "",
    val revision: Long = 1L,
    val linkedAlarmId: String? = null,
    val executionToken: String? = null,
    /** Actual OS/worker dispatch, separate from a promise or the user answering. */
    val lastAlarmScheduledAt: Instant? = null,
    val lastAlarmReceivedAt: Instant? = null,
    val lastAlarmDeliverySource: String = "",
    /** "verification" is observation-only; "retry" remains a separate existing callback. */
    val followUpKind: String = "",
    val createdAt: Instant = Instant.now(),
    val updatedAt: Instant = Instant.now(),
    /** Current wake rounds with verified audio evidence and chosen reasons. */
    val wakeAttemptHistory: List<String> = emptyList(),
    /** Accepted for this moment, with its next step owned by the ordinary role executor. */
    val startNow: Boolean = false,
    /** A new explicit retry may not be fulfilled by an older attempt's receipt. */
    val immediateRetryAt: Instant? = null,
)

/**
 * Legacy task receipts are evidence and remain unchanged on disk. Their old
 * destination label is translated only when projected into current UI/prompt.
 */
internal fun CommitmentTask.readableLastResult(): String = lastActionResult
    .replace("持续约定已记入辞海", "持续约定已保存，可在日程的未定时约定中查看")
    .replace("对应辞海约定已删除", "对应约定记录已删除")

internal fun CommitmentTask.isOutcomeReview(): Boolean =
    followUpKind == "verification"

/** A queued outcome review still owns tonight's occurrence across midnight. */
internal fun CommitmentTask.hasPendingOutcomeReview(now: Instant): Boolean =
    status == CommitmentTaskStatus.WaitingForFeedback &&
        followUpKind == "verification" && linkedAlarmId != null &&
        nextCheckAt?.isAfter(now) == true

/**
 * A past-due accepted commitment still needs a decision about its unknown
 * outcome, whether it repeats daily or was a one-time promise. This is only
 * a recovery candidate: CompanionSleepHandoff.uncovered checks the actual
 * alarm/linked-plan authority before scheduling any model work.
 */
internal fun CommitmentTask.needsUnverifiedCommitmentRecoveryCandidate(now: Instant): Boolean =
    status in setOf(CommitmentTaskStatus.WaitingForFeedback, CommitmentTaskStatus.Blocked) &&
        dueAt?.isBefore(now) == true

/** Legacy daily-specific predicate, retained for existing occurrence checks. */
internal fun CommitmentTask.needsUnverifiedDailyRecovery(now: Instant): Boolean =
    repeat == "daily" && needsUnverifiedCommitmentRecoveryCandidate(now) &&
        !hasPendingOutcomeReview(now) &&
        !(followUpKind == "retry" && linkedAlarmId != null && nextCheckAt?.isAfter(now) == true)

/**
 * A daily appointment may already have moved to tomorrow while the last
 * occurrence still matters now. Keep the actual attempt (including a
 * delivery-only completion) available to the SAME role for a bounded
 * after-midnight continuation; never turn it into a second alarm or claim
 * the owner has/has not slept.
 */
internal fun CommitmentTask.recentDailyOutcomeContext(now: Instant): DailyCommitmentAttempt? {
    if (repeat != "daily") return null
    val last = dailyAttempts.lastOrNull { !it.dueAt.isAfter(now) } ?: return null
    val elapsed = Duration.between(last.dueAt, now)
    if (elapsed.isNegative || elapsed > Duration.ofHours(16)) return null
    // An outcome-verified completion is closed. A delivery-only success
    // proves the reminder happened, not a separately promised life outcome.
    return last.takeIf { it.status !in setOf("Cancelled", "Completed") ||
        (it.status == "Completed" && verificationMode == "delivery") }
}

/**
 * Calendar dates describe actual appointments, not an open-ended backlog.
 * An unverified ONE-OFF from yesterday belongs to yesterday (and to the
 * responsibility review), NEVER to today's new appointments.
 */
internal fun CommitmentTask.scheduleOccurrenceAt(date: LocalDate, zone: ZoneId): Instant? {
    if (status == CommitmentTaskStatus.Cancelled &&
        lastActionResult.startsWith("重复定时约定已整理")) return null
    val scheduledAt = dueAt ?: return if (createdAt.atZone(zone).toLocalDate() == date)
        createdAt else null
    if (repeat != "daily") return scheduledAt.takeIf {
        it.atZone(zone).toLocalDate() == date
    }
    // Real attempts and actual armed occurrences take precedence over the
    // recurring wall clock, including a one-day postponement.
    dailyAttempts.firstOrNull { it.dueAt.atZone(zone).toLocalDate() == date }
        ?.let { return it.dueAt }
    if (scheduledAt.atZone(zone).toLocalDate() == date) return scheduledAt
    dailyNextDueAt?.takeIf { it.atZone(zone).toLocalDate() == date }
        ?.let { return it }
    if (!status.isActive()) return null
    // The daily responsibility persists, but its last attempt's changed
    // clock is not the permanent clock. A projected occurrence is a calendar
    // expectation, NOT proof that an alarm was successfully registered.
    val base = dailyBaseDueAt ?: scheduledAt
    val first = maxOf(createdAt.atZone(zone).toLocalDate(),
        base.atZone(zone).toLocalDate())
    if (date.isBefore(first)) return null
    return java.time.ZonedDateTime.of(date, base.atZone(zone).toLocalTime(), zone)
        .toInstant()
}

internal fun CommitmentTask.visibleOnScheduleDate(
    date: LocalDate, zone: ZoneId, today: LocalDate,
): Boolean = scheduleOccurrenceAt(date, zone) != null

/** Unverified previous-day reminders still need review under their original
 * task ID, but they are not today's booked calls or alarms. */
internal fun CommitmentTask.isOverdueOneOffOn(date: LocalDate, zone: ZoneId): Boolean =
    repeat != "daily" && dueAt != null && dueAt.atZone(zone).toLocalDate().isBefore(date) &&
        status.isActive()


/** An undated responsibility still needs a model-owned next-step review.
 * NeedsClarification is not permission to assume the missing user condition,
 * but it must not disappear from the character's planning opportunities. */
internal fun CommitmentTask.needsUndatedActionReview(): Boolean =
    dueAt == null && status in setOf(
        CommitmentTaskStatus.NeedsClarification,
        CommitmentTaskStatus.WaitingForFeedback,
    )

/** The canonical state transition owns cancelling obsolete child plans.
 * Changes to receipts alone never invalidate the original next-step choices. */
internal fun CommitmentTask.invalidatesUndatedFollowUps(next: CommitmentTask): Boolean =
    needsUndatedActionReview() && (
        !next.needsUndatedActionReview() ||
            goal != next.goal ||
            immediateRetryAt != next.immediateRetryAt ||
            completionCondition != next.completionCondition ||
            verificationMode != next.verificationMode ||
            deliveryAction != next.deliveryAction ||
            steps != next.steps ||
            repeat != next.repeat
    )

internal fun CommitmentTaskStatus.isActive(): Boolean = this in setOf(
    CommitmentTaskStatus.NeedsClarification,
    CommitmentTaskStatus.Scheduled,
    CommitmentTaskStatus.Running,
    CommitmentTaskStatus.WaitingForFeedback,
    CommitmentTaskStatus.Blocked,
)

/** A fresh incoming call or alarm cannot terminate an already-connected call. */
internal fun CommitmentTask.isMisroutedLiveHangup(): Boolean =
    listOf("挂断电话", "挂电话", "挂掉电话", "结束通话", "挂断这通", "挂断本次")
        .any(goal::contains)

/** Future local wall-clock recurrence: skip missed days, never replay old calls. */
internal fun nextDailyCommitmentDue(
    previous: java.time.Instant,
    now: java.time.Instant,
    zone: java.time.ZoneId,
): java.time.Instant {
    val local = previous.atZone(zone)
    val today = now.atZone(zone).toLocalDate()
    val first = local.toLocalDate().plusDays(1)
    val date = if (today.isAfter(first)) today else first
    var candidate = java.time.ZonedDateTime.of(date, local.toLocalTime(), zone)
    if (!candidate.toInstant().isAfter(now.plusSeconds(5))) candidate = candidate.plusDays(1)
    return candidate.toInstant()
}



/**
 * A promise's original action clock and its outcome-review clock are different
 * facts. A review at 01:30 cannot silently redefine the original 23:30 promise
 * as a 01:30 bedtime. Present arithmetic, never decide whether to contact,
 * claim sleep, or set a default bedtime on behalf of the character or user.
 */
internal object CommitmentClockFacts {
    fun render(task: CommitmentTask, now: Instant, zone: ZoneId = ZoneId.systemDefault()): String {
        val format = DateTimeFormatter.ofPattern("M月d日 HH:mm")
        fun local(at: Instant): String = at.atZone(zone).format(format)
        fun elapsed(seconds: Long): String =
            if (seconds < 60) "不足1分钟" else "${seconds / 60}分钟"
        fun clock(label: String, at: Instant): String {
            val delta = Duration.between(at, now).seconds
            val relation = when {
                delta > 0 -> "已过去${elapsed(delta)}"
                delta < 0 -> "距离现在还有${elapsed(-delta)}"
                else -> "正是此刻"
            }
            return "$label=${local(at)}（$relation）"
        }
        val original = task.dueAt?.let { clock("原定行动时间", it) }
        val review = task.nextCheckAt?.let { clock("结果复查时间", it) }
        return (listOfNotNull(original, review) +
            if (original != null && review != null)
                listOf("结果复查只是查证下一步，原定行动时间没有因此被改写")
            else emptyList()).joinToString("；")
    }
}

/**
 * Facts for the SAME character decision, not a second planner or personality rule.
 * An accepted duty differs from a passing personal thought: unverified work must
 * remain visible even when the most recent user message was quiet.
 */
/**
 * A bounded, rotating read-only attention view over the authoritative duties.
 * Dated tasks have historically sorted ahead of all undated commitments: a
 * simple take(12) could hide every persistent responsibility forever.
 *
 * Each existing decision sees urgent dated work AND some ongoing duties.
 * Rotation uses the current five-minute observation window; it never wakes
 * the role, creates alarms, or makes a semantic choice for the character.
 */
internal object PersistentResponsibilityAttention {
    fun select(tasks: List<CommitmentTask>, now: Instant, limit: Int = 12): List<CommitmentTask> {
        if (limit <= 0) return emptyList()
        val active = tasks.filter { it.status.isActive() }
        if (active.size <= limit) return active
        // Tomorrow's scheduled slot must not starve a still-relevant receipt
        // from last night behind many unrelated future alarms.
        val recent = active.filter { it.recentDailyOutcomeContext(now) != null }
            .take(minOf(2, limit))
        val immediate = active.filter { it.startNow }.take(minOf(3, limit - recent.size))
        val priority = recent + immediate
        val recentIds = priority.map(CommitmentTask::id).toSet()
        val candidates = active.filterNot { it.id in recentIds }
        val room = limit - priority.size
        if (room == 0) return priority
        val ongoing = candidates.filter { it.dueAt == null }
        val timed = candidates.filter { it.dueAt != null }
        if (ongoing.isEmpty()) return priority + timed.take(room)

        // Preserve most of the window for time-bound commitments while never
        // starving duties without a clock, even if there are many old alarms.
        val reserved = if (timed.isEmpty()) room else minOf(3, (room / 4).coerceAtLeast(1))
        val timedCount = minOf(timed.size, room - reserved)
        val ongoingCount = minOf(ongoing.size, room - timedCount)
        val offset = ((now.epochSecond / 300L) * ongoingCount.toLong())
            .mod(ongoing.size.toLong()).toInt()
        val sampled = (0 until ongoingCount).map { ongoing[(offset + it) % ongoing.size] }
        return priority + timed.take(timedCount) + sampled
    }
}

internal object ActiveCommitmentAttention {
    fun render(tasks: List<CommitmentTask>, now: Instant, summaryAlreadyListed: Boolean = false): String {
        val active = PersistentResponsibilityAttention.select(tasks, now)
        val needsAttention = active.filter { task ->
            task.status == CommitmentTaskStatus.WaitingForFeedback ||
                task.status == CommitmentTaskStatus.Blocked ||
                task.dueAt == null ||
                !task.dueAt.isAfter(now) ||
                task.recentDailyOutcomeContext(now) != null
        }
        if (needsAttention.isEmpty()) return ""
        return buildString {
            appendLine("【已接受且尚待履行或核实的主人约定｜比普通关心的猜测更有依据】")
            needsAttention.take(12).forEach { task ->
                val previous = task.recentDailyOutcomeContext(now)
                val phase = when {
                    task.status == CommitmentTaskStatus.Scheduled && previous != null ->
                        "下一次每日时刻已登记；上一轮动作与真实效果需分开判断"
                    task.startNow && task.status == CommitmentTaskStatus.WaitingForFeedback ->
                        "当下责任仍未核实；查看已有回执，再采取可行下一步或核实结果"
                    task.status == CommitmentTaskStatus.WaitingForFeedback ->
                        "已尝试一步，实际效果尚未证实"
                    task.status == CommitmentTaskStatus.Blocked ->
                        "执行受阻，仍需本人处理失败"
                    task.status == CommitmentTaskStatus.NeedsClarification ->
                        "尚待决定可执行的下一步"
                    task.status == CommitmentTaskStatus.Running ->
                        "执行中，先等真实执行回执"
                    else -> "约定已到期或尚无具体行动时刻"
                }
                val next = task.nextCheckAt?.takeIf { it.isAfter(now) }
                val line = if (summaryAlreadyListed) {
                    // The caller already delivered the complete goal, condition,
                    // status and last result for this exact task ID. Do not repeat.
                    "taskId=${task.id}; phase=$phase; " +
                        "nextDaily=${task.dailyNextDueAt ?: "未登记"}; priorUnverified=${task.dailyAttempts.count { it.status != "Completed" }}; " +
                        CommitmentClockFacts.render(task, now)
                } else {
                    "taskId=${task.id}; goal=${task.goal.take(140)}; phase=$phase; " +
                        "nextReview=${next ?: "未安排"}; nextDaily=${task.dailyNextDueAt ?: "未登记"}; priorUnverified=${task.dailyAttempts.count { it.status != "Completed" }}; last=${task.readableLastResult().take(150)};" +
                        " ${CommitmentClockFacts.render(task, now)}"
                }
                appendLine(line)
                if (previous != null) {
                    appendLine("  最近一次每日轮次=${previous.dueAt}; 记录状态=${previous.status}; 实际回执=${previous.result.take(140)}")
                    appendLine(if (previous.status == "Completed")
                        "  该轮只核实了指定动作的投递；它不是用户已入睡等其他目标的证据。是否另有尚未完成的持续责任，须依原约定与最新事实由本人判断。"
                    else
                        "  该轮结果尚未核实；新的每日执行时刻不替代旧轮次的结果，也不能把未确认当作完成。")
                }
                task.dailyAttempts.takeLast(3).forEach { old ->
                    appendLine("  旧轮次=${old.dueAt}; 状态=${old.status}; 已尝试=${old.attemptCount}; " +
                        "未确认的结果=${old.result.take(160)}; 原复查=${old.reviewAt ?: "无"}; " +
                        "实际复查=${old.reviewReceivedAt ?: "未有"}")
                }
            }
            appendLine("【目标剩余机会与等待代价】原定行动、实际送达与结果复查时刻是不同事实。先比较现在做与以后再做的真实机会；若等待，选预计能得到新证据的时刻，不机械按五分钟或半小时循环。")
            appendLine("没有新回复、手机熄屏、音乐或弹窗都不是结果证据；无证据仍不能推断对方已经睡着或仍然醒着。原有监督责任不会因此取消。尊重真实拒绝与授权；可自主行动、观察、换方法、暂停，或用 schedule_commitment_review 沿原 taskId 复查。已有闹钟不代表履约，也不能把闹钟当成停止判断的理由；不重复建闹钟，执行与目标效果分别核验。")
        }.trim()
    }
}

/** Answering is delivery evidence, not cancellation of the pending result check.
 * Declining stops retry calls, while an observation-only check remains valid. */
internal fun CommitmentTask.keepsFollowUpAfterCallResponse(answered: Boolean): Boolean =
    linkedAlarmId != null && nextCheckAt != null &&
        (followUpKind == "verification" || (answered && followUpKind == "retry"))
