package com.jiacimu.lulu.data

import org.junit.Assert.*
import org.junit.Test
import java.time.Instant

class BedtimeSupervisionReviewTest {
    private val due = Instant.parse("2026-10-26T15:30:00Z")

    @Test fun remindersAndOrdinarySleepCallsAreNotMisclassifiedAsSupervision() {
        assertFalse(CommitmentTask(characterId = "r", goal = "我来给你讲睡前故事").isBedtimeSupervision())
        assertFalse(CommitmentTask(characterId = "r", goal = "明早监督你学习").isBedtimeSupervision())
        assertTrue(CommitmentTask(characterId = "r", goal = "每天23:30监督我睡觉").isBedtimeSupervision())
        assertTrue(CommitmentTask(characterId = "r", goal = "睡前确认她已经休息").isBedtimeSupervision())
    }

    @Test fun originalAlarmAndManualHangupCannotCountAsReview() {
        val task = CommitmentTask(characterId = "r", goal = "今晚监督我睡觉",
            dueAt = due, lastAlarmScheduledAt = due, attemptCount = 1)
        assertFalse(task.isBedtimeSupervisionReview())
        assertFalse(task.copy(attemptCount = 0, lastAlarmScheduledAt = due.plusSeconds(600))
            .isBedtimeSupervisionReview())
        assertTrue(task.copy(lastAlarmScheduledAt = due.plusSeconds(600)).isBedtimeSupervisionReview())
    }

    @Test fun oldTasksStayIdentifiableAfterPersistence() {
        val old = CommitmentTask(characterId = "r", goal = "每晚监督用户入睡",
            dueAt = due, repeat = "daily", attemptCount = 1, lastAlarmScheduledAt = due.plusSeconds(600))
        val recovered = decodeCommitmentTasks(encodeCommitmentTasks(listOf(old))).single()
        assertEquals(old.id, recovered.id)
        assertTrue(recovered.isBedtimeSupervisionReview())
    }
}
