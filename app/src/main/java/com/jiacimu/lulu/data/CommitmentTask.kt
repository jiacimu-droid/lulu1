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

/** Calendar projection keeps past unverified duties visible without duplicating them. */
internal fun CommitmentTask.visibleOnScheduleDate(
    date: LocalDate, zone: ZoneId, today: LocalDate,
): Boolean {
    val scheduledDate = (dueAt ?: createdAt).atZone(zone).toLocalDate()
    if (repeat == "daily" && dailyNextDueAt?.atZone(zone)?.toLocalDate() == date) return true
    if (repeat == "daily" && dueAt != null && status.isActive()) {
        if (date.isBefore(createdAt.atZone(zone).toLocalDate())) return false
        return !date.isBefore(scheduledDate) ||
            lastAlarmScheduledAt?.atZone(zone)?.toLocalDate() == date ||
            lastAlarmReceivedAt?.atZone(zone)?.toLocalDate() == date
    }
    if (date == scheduledDate) return true
    return date == today && dueAt != null && scheduledDate.isBefore(today) && status.isActive()
}

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
        val ongoing = active.filter { it.dueAt == null }
        val timed = active.filter { it.dueAt != null }
        if (ongoing.isEmpty()) return timed.take(limit)

        // Preserve most of the window for time-bound commitments while never
        // starving duties without a clock, even if there are many old alarms.
        val reserved = if (timed.isEmpty()) limit else minOf(3, (limit / 4).coerceAtLeast(1))
        val timedCount = minOf(timed.size, limit - reserved)
        val ongoingCount = minOf(ongoing.size, limit - timedCount)
        val offset = ((now.epochSecond / 300L) * ongoingCount.toLong())
            .mod(ongoing.size.toLong()).toInt()
        val sampled = (0 until ongoingCount).map { ongoing[(offset + it) % ongoing.size] }
        return timed.take(timedCount) + sampled
    }
}

internal object ActiveCommitmentAttention {
    fun render(tasks: List<CommitmentTask>, now: Instant, summaryAlreadyListed: Boolean = false): String {
        val active = PersistentResponsibilityAttention.select(tasks, now)
        val needsAttention = active.filter { task ->
            task.status == CommitmentTaskStatus.WaitingForFeedback ||
                task.status == CommitmentTaskStatus.Blocked ||
                task.dueAt == null ||
                !task.dueAt.isAfter(now)
        }
        if (needsAttention.isEmpty()) return ""
        return buildString {
            appendLine("【已接受且尚待履行或核实的主人约定｜比普通关心的猜测更有依据】")
            needsAttention.take(12).forEach { task ->
                val phase = when (task.status) {
                    CommitmentTaskStatus.WaitingForFeedback -> "已尝试一步，真实效果尚未证实"
                    CommitmentTaskStatus.Blocked -> "执行受阻，仍需本人处理失败"
                    CommitmentTaskStatus.NeedsClarification -> "尚待决定可执行的下一步"
                    CommitmentTaskStatus.Running -> "执行中，先等真实执行回执"
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
                task.dailyAttempts.takeLast(3).forEach { old ->
                    appendLine("  旧轮次=${old.dueAt}; 状态=${old.status}; 已尝试=${old.attemptCount}; " +
                        "未确认的结果=${old.result.take(160)}; 原复查=${old.reviewAt ?: "无"}; " +
                        "实际复查=${old.reviewReceivedAt ?: "未有"}")
                }
            }
            appendLine("【目标剩余机会与等待代价】原定时间、动作送达与目标效果不是同一事实。结合实际证据判断现在行动或等待；等待应有值得复查的时刻及预期新增依据，不机械每五分钟或半小时检查。")
            appendLine("没有回复、手机熄屏、播放音乐或发出提醒，都不能证明用户已经入睡、醒来或达标，也不自动取消已接受的监督责任。尊重用户明确拒绝及权限；可选择联系、获授权观察、改变方法、暂停或沿原 taskId 安排/调整复查。已有闹钟不证明已履约，不重复建立；执行成功与结果达成仍以各自回执核实。")
        }.trim()
    }
}

/** Answering is delivery evidence, not cancellation of the pending result check.
 * Declining stops retry calls, while an observation-only check remains valid. */
internal fun CommitmentTask.keepsFollowUpAfterCallResponse(answered: Boolean): Boolean =
    linkedAlarmId != null && nextCheckAt != null &&
        (followUpKind == "verification" || (answered && followUpKind == "retry"))
