package com.jiacimu.lulu

import org.junit.Assert.*
import org.junit.Test

class PhoneSubtitleProgressTest {
    @Test fun captionsRevealInShortBeatsWithoutChangingStoredText() {
        val spoken = "今天辛苦了。先慢慢闭上眼睛，我们一起放松肩膀。你不用着急入睡。"
        val lines = PhoneSubtitleLayout.lines(spoken)
        assertTrue(lines.size >= 3)
        assertEquals(spoken, lines.joinToString(""))
        assertTrue(lines.all { PhoneSubtitleProgress.pauseBeforeNextLine(it) in 420L..3_900L })
        assertEquals(420L, PhoneSubtitleProgress.pauseBeforeNextLine("嗯。"))
        assertEquals(3_900L, PhoneSubtitleProgress.pauseBeforeNextLine("慢慢来".repeat(25)))
    }
}
