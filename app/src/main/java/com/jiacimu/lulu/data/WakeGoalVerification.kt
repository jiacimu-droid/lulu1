package com.jiacimu.lulu.data

import com.jiacimu.lulu.system.LuluAlarmSystem
import java.time.Instant

/**
 * Delivering a ringtone or talking is a recorded *attempt*; only clear user
 * confirmation can finish the wake-up goal. Silence is not proof of sleep.
 */
internal object WakeGoalVerification {
    /** Only confirm an already due or attempted wake-up; do not cancel future alarms. */
    internal fun eligibleToComplete(task: CommitmentTask, now: Instant): Boolean =
        task.isWakeResponsibility() && task.status.isActive() &&
            (task.attemptCount > 0 || (task.dueAt != null && !task.dueAt.isAfter(now)))

    /** Invoked after the role's normal semantic decision confirms this NEW user turn. */
    fun confirmFromModel(characterId: String, now: Instant = Instant.now()): Boolean {
        val outstanding = CommitmentTaskStore.snapshot(characterId).filter {
            eligibleToComplete(it, now)
        }
        outstanding.forEach { task ->
            task.linkedAlarmId?.let(LuluAlarmSystem::cancel)
            CommitmentTaskStore.update(task.id) { previous ->
                previous.copy(status = CommitmentTaskStatus.Completed,
                    nextCheckAt = null, linkedAlarmId = null,
                    lastActionResult = "用户明确回复已经醒来，叫醒目标确认完成；后续提醒已取消")
            }
        }
        return outstanding.isNotEmpty()
    }
}
