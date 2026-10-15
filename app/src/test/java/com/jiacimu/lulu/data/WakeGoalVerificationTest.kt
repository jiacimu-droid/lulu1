package com.jiacimu.lulu.data

import org.junit.Assert.*
import org.junit.Test
import java.time.Instant

class WakeGoalVerificationTest {
    @Test fun noReplyOrRingtoneNeverCountsAsGoalSuccess() {
        assertFalse(WakeGoalVerification.explicitlyAwake(""))
        assertFalse(WakeGoalVerification.explicitlyAwake("嗯"))
        assertFalse(WakeGoalVerification.explicitlyAwake("怎么啦"))
        assertFalse(WakeGoalVerification.explicitlyAwake("我还没醒"))
        assertFalse(WakeGoalVerification.explicitlyAwake("我醒了，但是又睡过去了，我没醒"))
    }

    @Test fun clearAffirmationIncludesNoMusicPreference() {
        assertTrue(WakeGoalVerification.explicitlyAwake("我醒了"))
        assertTrue(WakeGoalVerification.explicitlyAwake("我醒了，不过别放歌"))
        assertTrue(WakeGoalVerification.explicitlyAwake("我起来啦"))
    }
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
        assertTrue(WakeGoalVerification.explicitlyAwake("我已经醒了啦"))
    }

}
