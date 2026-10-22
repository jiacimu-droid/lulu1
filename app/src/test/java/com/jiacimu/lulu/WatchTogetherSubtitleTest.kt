package com.jiacimu.lulu

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class WatchTogetherSubtitleTest {
    @Test fun srtNeverLeaksFutureLines() {
        val cues = parseWatchSubtitles("""
            1
            00:00:01,000 --> 00:00:02,500
            第一段

            2
            00:00:08,000 --> 00:00:10,000
            凶手是谁
        """.trimIndent())
        assertEquals(2, cues.size)
        assertTrue(watchedSubtitleWindow(cues, 2_500).contains("第一段"))
        assertFalse(watchedSubtitleWindow(cues, 2_500).contains("凶手是谁"))
        assertFalse(watchedSubtitleWindow(cues, 9_000).contains("凶手是谁"))
        assertTrue(watchedSubtitleWindow(cues, 10_000).contains("凶手是谁"))
        // Rewind reads against the new playhead, not a stale monotonic cursor.
        assertFalse(watchedSubtitleWindow(cues, 3_000).contains("凶手是谁"))
    }

    @Test fun vttAndMalformedRanges() {
        val cues = parseWatchSubtitles("""
            WEBVTT

            00:01.000 --> 00:02.000
            <i>你好</i>

            00:02.000 --> 00:01.000
            倒置时间不会进入上下文
        """.trimIndent())
        assertEquals(1, cues.size)
        assertEquals("你好", cues[0].text)
        assertFalse(watchedSubtitleWindow(cues, 1_200).contains("你好"))
        assertTrue(watchedSubtitleWindow(cues, 2_000).contains("你好"))
    }
}
