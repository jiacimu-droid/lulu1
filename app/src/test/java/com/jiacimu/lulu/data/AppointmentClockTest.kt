package com.jiacimu.lulu.data

import org.junit.Assert.*
import org.junit.Test
import java.time.Instant
import java.time.ZoneId

class AppointmentClockTest {
    @Test fun halfHoursChineseAndColonTimesAreExact() {
        for (text in listOf("9点半", "九点半", "09:30", "九点三十分")) {
            val clock = AppointmentClock.read(text)!!
            assertEquals(9, clock.hour)
            assertEquals(30, clock.minute)
        }
        assertEquals(45, AppointmentClock.read("十点三刻")!!.minute)
        assertEquals(23, AppointmentClock.read("二十三点十五分")!!.hour)
        assertNull(AppointmentClock.read("25点"))
    }
    @Test fun acceptedWakeCallForTomorrowNineThirtySchedulesNineThirty() {
        val draft = WakeCommitmentParser.parse("明早九点半给我打电话叫我起床", "好，明早我来叫你。",
            Instant.parse("2026-10-11T14:00:00Z"), ZoneId.of("Asia/Shanghai"))!!
        assertEquals(Instant.parse("2026-10-12T01:30:00Z"), draft.dueAt)
        assertEquals("start_call", draft.deliveryAction)
        assertNull(WakeCommitmentParser.parse("九点半叫我", "好",
            Instant.parse("2026-10-11T14:00:00Z"), ZoneId.of("Asia/Shanghai")))
    }
}
