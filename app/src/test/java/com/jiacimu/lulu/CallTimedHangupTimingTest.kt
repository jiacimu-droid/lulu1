package com.jiacimu.lulu

import org.junit.Assert.*
import org.junit.Test
import java.time.Duration
import java.time.ZoneId
import java.time.ZonedDateTime

class CallTimedHangupTimingTest {
    private val zone = ZoneId.of("Asia/Shanghai")

    @Test fun twoFivePlusHalfHourIsTwoThirtyFiveToday() {
        val userAt = ZonedDateTime.of(2026, 10, 24, 2, 5, 0, 0, zone).toInstant()
        val due = CallTimedHangupTiming.deadline(userAt, 30 * 60)!!
        assertEquals(24, due.atZone(zone).dayOfMonth)
        assertEquals(2, due.atZone(zone).hour)
        assertEquals(35, due.atZone(zone).minute)
        // A slower model response cannot move the originally requested deadline.
        assertEquals(29 * 60_000L,
            CallTimedHangupTiming.remainingMillis(due, userAt.plusSeconds(60)))
    }

    @Test fun crossesMidnightOnlyWhenActualArithmeticCrossesIt() {
        val userAt = ZonedDateTime.of(2026, 10, 24, 23, 50, 0, 0, zone).toInstant()
        val due = CallTimedHangupTiming.deadline(userAt, 30 * 60)!!
        assertEquals(25, due.atZone(zone).dayOfMonth)
        assertEquals(0, due.atZone(zone).hour)
        assertEquals(20, due.atZone(zone).minute)
    }

    @Test fun cancelledOutOfRangeAndPastDeadlinesNeverArm() {
        val userAt = ZonedDateTime.of(2026, 10, 24, 2, 5, 0, 0, zone).toInstant()
        assertNull(CallTimedHangupTiming.deadline(userAt, 0))
        assertNull(CallTimedHangupTiming.deadline(userAt, 86_401))
        val due = CallTimedHangupTiming.deadline(userAt, 1800)!!
        assertNull(CallTimedHangupTiming.remainingMillis(due, due))
        assertNull(CallTimedHangupTiming.remainingMillis(due, due.plusSeconds(1)))
    }
}
