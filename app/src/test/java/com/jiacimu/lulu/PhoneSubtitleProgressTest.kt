package com.jiacimu.lulu

import org.junit.Assert.*
import org.junit.Test

class PhoneSubtitleProgressTest {
    @Test fun captionsRevealInShortBeatsWithoutChangingStoredText() {
        val spoken = "今天辛苦了。先慢慢闭上眼睛，我们一起放松肩膀。你不用着急入睡。"
        val lines = PhoneSubtitleLayout.lines(spoken)
        assertTrue(lines.size in 1..3)
        assertEquals(spoken, lines.joinToString(""))
        assertTrue(lines.all { PhoneSubtitleProgress.pauseBeforeNextLine(it) in 500L..12_000L })
        assertEquals(540L, PhoneSubtitleProgress.pauseBeforeNextLine("嗯。"))
        assertEquals(12_000L, PhoneSubtitleProgress.pauseBeforeNextLine("慢慢来".repeat(25)))
    }

    @Test fun fiveSentencePhoneTurnDoesNotRevealFifthWithSecond() {
        val spoken = "我来了。先坐下。今天怎么样？想喝点水吗？我在这里陪你。"
        val live = PhoneSubtitleLayout.liveLines(spoken)
        assertEquals(5, live.size)
        assertEquals(spoken, live.joinToString(""))
        assertEquals("我来了。", live[0])
        assertEquals("先坐下。", live[1])
        assertEquals("我在这里陪你。", live[4])
        // A full-turn chat record may still use longer reading bubbles.
        assertEquals(spoken, PhoneSubtitleLayout.lines(spoken).joinToString(""))
        assertTrue(live.take(4).sumOf { PhoneSubtitleProgress.pauseBeforeNextLine(it) } > 4_000L)
    }

    @Test fun livePhoneCaptionsRespectQuotedSentenceBoundary() {
        val spoken = "他说：“晚安。”我笑了。"
        val lines = PhoneSubtitleLayout.liveLines(spoken)
        assertEquals(listOf("他说：“晚安。”", "我笑了。"), lines)
        assertEquals(spoken, lines.joinToString(""))
    }
}
