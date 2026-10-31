package com.jiacimu.lulu.data

import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.time.Instant
import java.time.ZoneId
import java.time.ZonedDateTime

@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE, sdk = [28])
class DailyCommitmentRecurrenceTest {
    private val shanghai = ZoneId.of("Asia/Shanghai")
    private fun at(y: Int, m: Int, d: Int, hh: Int, mm: Int, zone: ZoneId = shanghai) =
        ZonedDateTime.of(y, m, d, hh, mm, 0, 0, zone).toInstant()

    @Test fun singleDailyTaskSurvivesCodecAndDoesNotBecomeManyTasks() {
        val before = CommitmentTask(characterId = "role", goal = "每天晚上十一点半来电监督睡觉",
            dueAt = at(2026, 10, 24, 23, 30), timezone = shanghai.id,
            deliveryAction = "start_call", repeat = "daily",
            status = CommitmentTaskStatus.Scheduled)
        val after = decodeCommitmentTasks(encodeCommitmentTasks(listOf(before)))
        assertEquals(1, after.size)
        assertEquals(before.id, after.single().id)
        assertEquals("daily", after.single().repeat)
        assertEquals(before.dueAt, after.single().dueAt)
    }

    @Test fun dailyBaseAnchorSurvivesCodecAndRollReturnsToUsualTime() {
        val before = CommitmentTask(characterId = "role", goal = "每天八点半叫醒",
            dueAt = at(2026, 10, 25, 9, 30), dailyBaseDueAt = at(2026, 10, 24, 8, 30),
            timezone = shanghai.id, deliveryAction = "start_call", repeat = "daily",
            status = CommitmentTaskStatus.Scheduled)
        val after = decodeCommitmentTasks(encodeCommitmentTasks(listOf(before))).single()
        assertEquals(at(2026, 10, 24, 8, 30), after.dailyBaseDueAt)
        assertEquals(at(2026, 10, 25, 9, 30), after.dueAt)
        // A task saved before this field decodes with a null anchor and
        // falls back to its dueAt, so old data keeps working.
        assertNull(decodeCommitmentTasks(
            encodeCommitmentTasks(listOf(before.copy(dailyBaseDueAt = null)))).single().dailyBaseDueAt)
        // Rolling from the anchor (not the one-off 9:30) returns to 8:30.
        assertEquals(at(2026, 10, 26, 8, 30),
            nextDailyCommitmentDue(after.dailyBaseDueAt!!, at(2026, 10, 25, 10, 0), shanghai))
    }

    @Test fun oldSavedTasksAreNotAccidentallyMadeDaily() {
        val before = CommitmentTask(characterId = "role", goal = "今晚电话提醒",
            dueAt = at(2026, 10, 24, 23, 30))
        val original = encodeCommitmentTasks(listOf(before)).replace("\"repeat\":\"once\",", "")
        val decoded = decodeCommitmentTasks(original)
        assertEquals("once", decoded.single().repeat)
    }

    @Test fun nextDayUsesLocalWallClockAndSkipsMissedDays() {
        val due = at(2026, 10, 24, 23, 30)
        val next = nextDailyCommitmentDue(due, due.plusSeconds(50), shanghai)
        assertEquals(at(2026, 10, 25, 23, 30), next)
        assertEquals(at(2026, 10, 29, 23, 30),
            nextDailyCommitmentDue(due, at(2026, 10, 29, 15, 0), shanghai))
        assertEquals(at(2026, 10, 30, 23, 30),
            nextDailyCommitmentDue(due, at(2026, 10, 29, 23, 31), shanghai))
    }

    @Test fun daylightSavingPreservesWallClock() {
        val zone = ZoneId.of("America/New_York")
        val due = at(2026, 10, 31, 23, 30, zone)
        val next = nextDailyCommitmentDue(due, due.plusSeconds(3600), zone)
        assertEquals(23, next.atZone(zone).hour)
        assertEquals(30, next.atZone(zone).minute)
        assertEquals(1, next.atZone(zone).dayOfMonth)
    }

    @Test fun pendingReviewSurvivesMidnightAndCodecWithoutRollingToTomorrow() {
        val now = at(2026, 11, 1, 0, 39)
        val nightly = CommitmentTask(characterId = "role", goal = "每天23:30监督睡觉",
            createdAt = at(2026, 10, 30, 20, 0),
            dueAt = at(2026, 10, 31, 23, 30),
            nextCheckAt = at(2026, 11, 1, 0, 45),
            linkedAlarmId = "review-1", followUpKind = "verification",
            status = CommitmentTaskStatus.WaitingForFeedback,
            repeat = "daily", lastAlarmReceivedAt = at(2026, 10, 31, 23, 30))
        assertTrue(nightly.hasPendingOutcomeReview(now))
        assertFalse(nightly.hasPendingOutcomeReview(at(2026, 11, 1, 0, 46)))
        val saved = decodeCommitmentTasks(encodeCommitmentTasks(listOf(nightly))).single()
        assertTrue(saved.hasPendingOutcomeReview(now))
        assertTrue(saved.visibleOnScheduleDate(
            java.time.LocalDate.of(2026, 11, 1), shanghai, java.time.LocalDate.of(2026, 11, 1)))
    }

    @Test fun calendarKeepsDailyPastAttemptAndOverduePromiseVisible() {
        val today = java.time.LocalDate.of(2026, 11, 1)
        val yesterday = today.minusDays(1)
        val rolled = CommitmentTask(characterId = "role", goal = "每天23:30监督睡觉",
            createdAt = at(2026, 10, 29, 16, 0),
            dueAt = at(2026, 11, 1, 23, 30), repeat = "daily",
            status = CommitmentTaskStatus.Scheduled,
            lastAlarmScheduledAt = at(2026, 10, 31, 23, 30))
        assertTrue(rolled.visibleOnScheduleDate(yesterday, shanghai, today))
        assertTrue(rolled.visibleOnScheduleDate(today, shanghai, today))
        assertFalse(rolled.visibleOnScheduleDate(today.minusDays(5), shanghai, today))
        val outstanding = rolled.copy(repeat = "once",
            dueAt = at(2026, 10, 31, 23, 30), status = CommitmentTaskStatus.WaitingForFeedback)
        assertTrue(outstanding.visibleOnScheduleDate(today, shanghai, today))
        assertFalse(outstanding.visibleOnScheduleDate(today.plusDays(1), shanghai, today))
    }

    @Test fun bedtimeSupervisionIsNotAnAlarmToWakeTheUser() {
        assertFalse(CommitmentTask(characterId = "role",
            goal = "每天晚上十一点半打电话叫我睡觉").isWakeResponsibility())
        assertFalse(CommitmentTask(characterId = "role",
            goal = "每天23:30监督用户入睡").isWakeResponsibility())
        assertTrue(CommitmentTask(characterId = "role",
            goal = "明天上午九点叫醒我起床").isWakeResponsibility())
    }

    @Test fun paraphrasedOneTimePromisesShareOneClockButNotAnotherDate() {
        val due = at(2026, 10, 24, 10, 30)
        val old = CommitmentTask(characterId = "role",
            goal = "十点半喊她学习", dueAt = due,
            timezone = shanghai.id, deliveryAction = "send_private_message",
            status = CommitmentTaskStatus.Scheduled)
        val same = CommitmentTaskDraft(action = "create",
            goal = "10:30督促开始今天的学习", dueAt = due, repeat = "once")
        assertTrue(sameTimedReminderTask(old, same))
        assertFalse("A separate tomorrow reminder must remain possible",
            sameTimedReminderTask(old, same.copy(dueAt = due.plusSeconds(86400))))
        assertFalse(sameTimedReminderTask(old, same.copy(goal = "提醒早点休息")))
    }

    @Test fun sameClockAndPurposeMustMatchToDeduplicate() {
        val a = at(2026, 10, 24, 23, 30)
        assertTrue(sameDailyClockAndPurpose("每晚监督她睡觉", a,
            "每天23点30来电哄睡", a))
        assertFalse(sameDailyClockAndPurpose("每晚监督她睡觉", a,
            "明天23点30让居民读书", a))
        assertFalse(sameDailyClockAndPurpose("每晚监督她睡觉", a,
            "每天23点45来电哄睡", a.plusSeconds(900)))
    }
}

