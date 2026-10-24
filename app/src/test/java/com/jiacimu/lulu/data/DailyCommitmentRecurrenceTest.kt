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
