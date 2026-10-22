package com.jiacimu.lulu

import org.junit.Assert.*
import org.junit.Test

class WatchPlaybackEvidenceTest {
    @Test fun continuousSixSecondsProducesMultipleFrames() {
        val watched = WatchPlaybackEvidence.empty()
        watched.begin(0)
        for (second in 1..6) watched.advance(second * 1_000L, true)
        assertEquals(6_000L, watched.observedMs(6_000))
        val frames = watched.frameTimes(6_000)
        assertTrue(frames.size >= 4)
        assertTrue(frames.first() <= 1_000L)
        assertTrue(frames.last() <= 6_000L)
        assertEquals(frames.size, frames.distinct().size)
    }

    @Test fun forwardSeekDoesNotCreateFakeWatchedFootageOrSpoilers() {
        val watched = WatchPlaybackEvidence.empty()
        watched.begin(0)
        watched.advance(1_000, true)
        watched.advance(2_000, true)
        watched.begin(65_000)
        watched.advance(66_000, true)
        assertEquals(3_000L, watched.observedMs(66_000))
        assertTrue(watched.frameTimes(66_000).none { it in 2_100..64_900 })
        val subtitles = parseWatchSubtitles("""
            1
            00:00:00,000 --> 00:00:01,000
            开头

            2
            00:00:20,000 --> 00:00:21,000
            跳过的剧情
        """.trimIndent())
        assertFalse(watchedSubtitles(subtitles, watched, 66_000).contains("跳过的剧情"))
    }

    @Test fun rewindAndResumeRetainsEvidenceButGatesCurrentPlayhead() {
        val watched = WatchPlaybackEvidence.empty()
        watched.begin(0)
        watched.advance(1_000, true)
        watched.advance(2_000, true)
        watched.advance(3_000, true)
        watched.pause()
        val resumed = WatchPlaybackEvidence.restore(watched.encode())
        assertEquals(3_000L, resumed.observedMs(3_000))
        assertEquals(1_000L, resumed.observedMs(1_000))
        assertTrue(resumed.frameTimes(1_000).all { it <= 1_000L })
        resumed.begin(1_000)
        resumed.advance(2_000, true)
        assertTrue(resumed.frameTimes(2_000).all { it <= 2_000L })
    }

    @Test fun noPlaybackMeansNoImaginedObservation() {
        val watched = WatchPlaybackEvidence.empty()
        watched.begin(0)
        watched.pause()
        watched.advance(15_000, false)
        assertEquals("", watched.encode())
        assertTrue(watched.frameTimes(15_000).isEmpty())
    }
}
