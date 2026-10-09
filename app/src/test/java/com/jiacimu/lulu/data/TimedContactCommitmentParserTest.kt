package com.jiacimu.lulu.data

import org.junit.Assert.*
import org.junit.Test
import java.time.Instant
import java.time.ZoneId

class TimedContactCommitmentParserTest {
    private val zone = ZoneId.of("Asia/Shanghai")
    private val now = Instant.parse("2026-10-10T06:00:00Z")

    @Test fun tomorrowMorningEightMessageIsAnActualClockAppointment() {
        val task = TimedContactCommitmentParser.parse("明天早上8点来找我一下", "好，到时候我找你", now, zone)
        assertNotNull(task)
        assertEquals("send_private_message", task!!.deliveryAction)
        assertEquals(Instant.parse("2026-10-11T00:00:00Z"), task.dueAt)
        assertFalse(task.needsClarification)
    }

    @Test fun tomorrowEightPhonePreservesThePhoneAction() {
        val task = TimedContactCommitmentParser.parse("明天8点给我打电话", "行，明天找你", now, zone)
        assertNotNull(task)
        assertEquals("start_call", task!!.deliveryAction)
        assertEquals(Instant.parse("2026-10-11T00:00:00Z"), task.dueAt)
    }

    @Test fun undatedEightOclockNeedsClarification() {
        val task = TimedContactCommitmentParser.parse("8点给我打电话", "好", now, zone)
        assertNotNull(task)
        assertTrue(task!!.needsClarification)
        assertNull(task.dueAt)
    }

    @Test fun questionOrRefusalIsNotACommitment() {
        assertNull(TimedContactCommitmentParser.parse("明天8点给我打电话", "不行", now, zone))
        assertNull(TimedContactCommitmentParser.parse("明天8点给我打电话", "要不要我打？", now, zone))
    }
}
