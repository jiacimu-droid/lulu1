package com.jiacimu.lulu.data

import org.junit.Assert.*
import org.junit.Test
import java.time.Instant

class WakeGoalVerificationTest {
    @Test fun dueWakeCanFinishBeforeExecutorPersistsFeedback() {
        val now = Instant.parse("2026-10-15T07:00:00Z")
        val running = CommitmentTask(
            characterId = "role", goal = "按约定叫醒用户",
            status = CommitmentTaskStatus.Running, dueAt = now.minusSeconds(5),
        )
        assertTrue(WakeGoalVerification.eligibleToComplete(running, now))
        assertTrue(WakeGoalVerification.eligibleToComplete(
            running.copy(status = CommitmentTaskStatus.WaitingForFeedback), now))
        assertTrue(WakeGoalVerification.eligibleToComplete(
            running.copy(status = CommitmentTaskStatus.Blocked, attemptCount = 1), now))
        assertFalse(WakeGoalVerification.eligibleToComplete(
            running.copy(status = CommitmentTaskStatus.Completed), now))
    }

    @Test fun futureWakeMustNotBeAccidentallyCancelled() {
        val now = Instant.parse("2026-10-15T07:00:00Z")
        val future = CommitmentTask(
            characterId = "role", goal = "明天叫醒用户",
            status = CommitmentTaskStatus.Scheduled, dueAt = now.plusSeconds(86_400),
        )
        assertFalse(WakeGoalVerification.eligibleToComplete(future, now))
        assertFalse(WakeGoalVerification.eligibleToComplete(
            future.copy(status = CommitmentTaskStatus.NeedsClarification, dueAt = null), now))
    }

}
