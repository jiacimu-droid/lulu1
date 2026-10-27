package com.jiacimu.lulu

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Instant

/**
 * Call time facts (batch 19): pure rendering only. Store lookups in
 * forCall() are deliberately untested here; they degrade to "no facts"
 * on any failure instead of producing a guessed line.
 */
class CallTimeFactsTest {

    // 2026-10-27 02:00 and 09:00 Shanghai (UTC+8).
    private val twoAm = Instant.parse("2026-10-26T18:00:00Z")
    private val nineAm = Instant.parse("2026-10-27T01:00:00Z")
    private val halfPastElevenPm = Instant.parse("2026-10-26T15:30:00Z")
    private val halfPastEightAm = Instant.parse("2026-10-27T00:30:00Z")

    @Test
    fun wakeMorningRendersGapAndRoutine() {
        val text = CallTimeFacts.render(
            now = nineAm,
            lastUserAt = twoAm,
            scheduledDueAt = halfPastEightAm,
            dailyCallClocks = listOf(halfPastElevenPm, halfPastEightAm),
        )
        assertTrue(text.contains("现在是上海时间10月27日 9:00（早上）"))
        assertTrue(text.contains("用户上次说话是10月27日 2:00，距今约7小时"))
        assertTrue(text.contains("这次来电约定的时间是10月27日 8:30"))
        assertTrue(text.contains("用户每天固定有角色来电约定：8:30（早上）、23:30（晚上）"))
    }

    @Test
    fun unknownFactsAreOmittedNeverGuessed() {
        val text = CallTimeFacts.render(
            now = nineAm,
            lastUserAt = null,
            scheduledDueAt = null,
            dailyCallClocks = emptyList(),
        )
        assertEquals("现在是上海时间10月27日 9:00（早上）。", text)
        assertFalse(text.contains("上次说话"))
        assertFalse(text.contains("约定"))
    }

    @Test
    fun shortGapRendersMinutes() {
        val text = CallTimeFacts.render(
            now = nineAm,
            lastUserAt = Instant.parse("2026-10-27T00:20:00Z"),
            scheduledDueAt = null,
            dailyCallClocks = emptyList(),
        )
        assertTrue(text.contains("距今约40分钟"))
    }

    @Test
    fun dayPartsCoverTheClock() {
        assertEquals("深夜", CallTimeFacts.dayPart(2))
        assertEquals("早上", CallTimeFacts.dayPart(8))
        assertEquals("上午", CallTimeFacts.dayPart(10))
        assertEquals("中午", CallTimeFacts.dayPart(12))
        assertEquals("下午", CallTimeFacts.dayPart(15))
        assertEquals("晚上", CallTimeFacts.dayPart(21))
        assertEquals("深夜", CallTimeFacts.dayPart(23))
    }
}
