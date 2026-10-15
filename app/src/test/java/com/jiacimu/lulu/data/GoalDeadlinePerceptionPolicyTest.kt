package com.jiacimu.lulu.data

import java.time.Instant
import org.junit.Assert.*
import org.junit.Test

class GoalDeadlinePerceptionPolicyTest {
    private val now = Instant.parse("2026-10-15T08:00:00Z")

    @Test fun twentyMinuteWakeLetsCharacterSleepUntilRealAlarm() {
        val task = CommitmentTask(characterId = "role", goal = "二十分钟后叫我",
            status = CommitmentTaskStatus.Scheduled,
            dueAt = now.plusSeconds(1200), linkedAlarmId = "real-alarm")
        assertTrue(GoalDeadlinePerceptionPolicy.deferPassiveCheck(listOf(task), now))
        assertFalse(GoalDeadlinePerceptionPolicy.deferPassiveCheck(
            listOf(task.copy(linkedAlarmId = null)), now))
        assertFalse(GoalDeadlinePerceptionPolicy.deferPassiveCheck(
            listOf(task.copy(dueAt = now.plusSeconds(3600))), now))
    }

    @Test fun followUpGoalHasItsOwnClockButCompletedGoalDoesNot() {
        val task = CommitmentTask(characterId = "role", goal = "未确认用户醒来",
            status = CommitmentTaskStatus.WaitingForFeedback,
            nextCheckAt = now.plusSeconds(300), linkedAlarmId = "followup")
        assertTrue(GoalDeadlinePerceptionPolicy.deferPassiveCheck(listOf(task), now))
        assertFalse(GoalDeadlinePerceptionPolicy.deferPassiveCheck(
            listOf(task.copy(status = CommitmentTaskStatus.Completed)), now))
    }
    @Test fun protectedAlarmCannotDropNextAutonomousWakeOrPostponeItForever() {
        val tenMinutes = CommitmentTask(characterId = "role", goal = "十分钟后叫醒",
            status = CommitmentTaskStatus.Scheduled,
            dueAt = now.plusSeconds(600), linkedAlarmId = "clock-1")
        val twentyMinutes = CommitmentTask(characterId = "role", goal = "二十分钟后提醒",
            status = CommitmentTaskStatus.Scheduled,
            dueAt = now.plusSeconds(1200), linkedAlarmId = "clock-2")
        assertEquals(now.plusSeconds(1260),
            GoalDeadlinePerceptionPolicy.passiveResumeAt(
                listOf(tenMinutes, twentyMinutes), now))
        assertNull(GoalDeadlinePerceptionPolicy.passiveResumeAt(
            listOf(tenMinutes, twentyMinutes), now.plusSeconds(1201)))
        assertNull(GoalDeadlinePerceptionPolicy.passiveResumeAt(
            listOf(tenMinutes.copy(linkedAlarmId = null)), now))
        assertNull(GoalDeadlinePerceptionPolicy.passiveResumeAt(
            listOf(tenMinutes.copy(status = CommitmentTaskStatus.Completed)), now))
    }

}
