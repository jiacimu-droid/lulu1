package com.jiacimu.lulu.data

import com.jiacimu.lulu.system.LuluAlarmSystem

/**
 * Delivering a ringtone or talking is a recorded *attempt*; only clear user
 * confirmation can finish the wake-up goal. Silence is not proof of sleep.
 */
internal object WakeGoalVerification {
    fun explicitlyAwake(text: String): Boolean {
        val clean = text.replace(Regex("""\s+"""), "")
        if (Regex("还没醒|没醒|没起来|不想起|没起床|醒了再说|没完全醒").containsMatchIn(clean))
            return false
        return Regex("我醒了|我醒啦|已经醒了|睡醒了|我起来了|我起床了|起床啦|起来啦").containsMatchIn(clean)
    }

    fun confirm(characterId: String, text: String): Boolean {
        if (!explicitlyAwake(text)) return false
        val outstanding = CommitmentTaskStore.snapshot(characterId).filter {
            it.isWakeResponsibility() &&
                it.status == CommitmentTaskStatus.WaitingForFeedback
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
