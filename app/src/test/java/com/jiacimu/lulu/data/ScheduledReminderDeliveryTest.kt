package com.jiacimu.lulu.data

import org.junit.Assert.*
import org.junit.Test
import java.time.Instant

class ScheduledReminderDeliveryTest {
    @Test fun timedStudyPromiseUsesPopupAndRoleDecisionNotScriptedPrivateChat() {
        val task = CommitmentTask(
            characterId = "role",
            goal = "十点半准时来喊我学习",
            deliveryAction = "send_private_message",
            dueAt = Instant.parse("2026-10-24T02:30:00Z"),
            status = CommitmentTaskStatus.Running,
        )
        assertTrue(shouldShowScheduledReminderPopup(task))
        assertFalse(shouldShowScheduledReminderPopup(task.copy(dueAt = null)))
        assertFalse(shouldShowScheduledReminderPopup(task.copy(deliveryAction = "start_call")))
        assertFalse(shouldShowScheduledReminderPopup(task.copy(deliveryAction = "alarm_only")))
    }

    @Test fun bedtimeAndWakeUpHaveDifferentExecutionSemantics() {
        val timed = Instant.parse("2026-10-24T15:30:00Z")
        val bedtime = CommitmentTask(
            characterId = "role", goal = "每晚11点半喊我睡觉",
            dueAt = timed, deliveryAction = "send_private_message")
        val wake = bedtime.copy(goal = "早上九点叫醒我起床")
        assertTrue(shouldShowScheduledReminderPopup(bedtime))
        assertFalse(shouldShowScheduledReminderPopup(wake))
    }
}
