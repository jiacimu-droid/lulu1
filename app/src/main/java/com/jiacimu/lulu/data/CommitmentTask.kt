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
internal object ActiveCommitmentAttention {
    fun render(tasks: List<CommitmentTask>, now: Instant): String {
        val active = tasks.filter { it.status.isActive() }
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
                appendLine("taskId=${task.id}; goal=${task.goal.take(140)}; phase=$phase; " +
                    "nextReview=${next ?: "未安排"}; nextDaily=${task.dailyNextDueAt ?: "未登记"}; priorUnverified=${task.dailyAttempts.count { it.status != "Completed" }}; last=${task.readableLastResult().take(150)};" +
                    " ${CommitmentClockFacts.render(task, now)}")
                task.dailyAttempts.takeLast(3).forEach { old ->
                    appendLine("  旧轮次=${old.dueAt}; 状态=${old.status}; 已尝试=${old.attemptCount}; " +
                        "未确认的结果=${old.result.take(160)}; 原复查=${old.reviewAt ?: "无"}; " +
                        "实际复查=${old.reviewReceivedAt ?: "未有"}")
                }
            }
            appendLine("【目标剩余机会与等待代价｜由本人权衡，不是系统代选】原定行动、对方想达到的实际效果与后续复查时刻是不同事实。先看现在已经过了多久、实际效果是否得到证实、继续等待会不会让原目标越来越难实现；此前排过复查不是今天已经尽责，也不代表目标自动延期。")
            appendLine("若眼下就有获授权、确实有意义的行动，自己比较现在做与以后再做的效果；若倾向等待，要想明白到那个时刻预计会新增什么可核实的事实、等待的好处是否值得消耗这段时间。没有新增证据只继续等，可能只是在把问题推后。可以改变方法、联系、观察、短暂等待或暂停，按本人的关系与真实现场选择；不要把固定五分钟或半小时当作默认间隔，也不要机械重复打扰。")
            appendLine("设备静默、没有回复、播放白噪声或熄屏都不能单独证明她入睡；无证据仍不能推断对方已经睡着或仍然醒着。关心一个时效性目标，也不能只凭害怕吵到对方就无条件延后。对方明确表示不愿被联系时尊重真实边界，遇到不知道的情况如实保持未知。")
            appendLine("用户明确要求过的主动提醒、陪伴或监督，是已授权的关系约定，不是你临时猜她是否想聊天。没有新回复、担心提示音或猜测她可能困了，都不足以替她撤销原约定。")
            appendLine("对仍待落实的责任，由你依据真实时间、原约定和权限选择：现在用真实消息/来电/已授权设备观察推进，或确有理由暂缓并用现有 schedule_commitment_review / 个人日程工具登记下一次行动。不要把弹窗、心声、端水等待、电话挂断或用户沉默当成哄睡成功，也不要机械连续打扰。")
            appendLine("已有后续闹钟不要重复创建，但它只是未来的一次检查：出现新事实或现在仍有更合适的行动机会时，仍由你决定此刻是否行动，不能拿那次闹钟当成停止判断的理由；需要调整复查则沿原 taskId 修改，执行是否成功仍以独立回执为准。")
        }.trim()
    }
}

/** Answering is delivery evidence, not cancellation of the pending result check.
 * Declining stops retry calls, while an observation-only check remains valid. */
internal fun CommitmentTask.keepsFollowUpAfterCallResponse(answered: Boolean): Boolean =
    linkedAlarmId != null && nextCheckAt != null &&
        (followUpKind == "verification" || (answered && followUpKind == "retry"))
