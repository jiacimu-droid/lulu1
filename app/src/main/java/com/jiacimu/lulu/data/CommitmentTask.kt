package com.jiacimu.lulu.data

import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.util.UUID

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

/** Unverified nightly results remain responsibilities after the first reminder fires. */
internal fun CommitmentTask.needsUnverifiedDailyRecovery(now: Instant): Boolean =
    repeat == "daily" && status == CommitmentTaskStatus.WaitingForFeedback &&
        dueAt?.isBefore(now) == true && !hasPendingOutcomeReview(now) &&
        !(followUpKind == "retry" && linkedAlarmId != null && nextCheckAt?.isAfter(now) == true)

/** Calendar projection keeps past unverified duties visible without duplicating them. */
internal fun CommitmentTask.visibleOnScheduleDate(
    date: LocalDate, zone: ZoneId, today: LocalDate,
): Boolean {
    val scheduledDate = (dueAt ?: createdAt).atZone(zone).toLocalDate()
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



/** Answering is delivery evidence, not cancellation of the pending result check.
 * Declining stops retry calls, while an observation-only check remains valid. */
internal fun CommitmentTask.keepsFollowUpAfterCallResponse(answered: Boolean): Boolean =
    linkedAlarmId != null && nextCheckAt != null &&
        (followUpKind == "verification" || (answered && followUpKind == "retry"))
