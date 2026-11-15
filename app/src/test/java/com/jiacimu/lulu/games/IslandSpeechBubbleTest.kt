package com.jiacimu.lulu.games

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** Text seen above the actual speaking actor; long lines never flood the map. */
class IslandSpeechBubbleTest {
    @Test fun realSpeechCreatesReadablePagesWithoutInventedText() {
        assertEquals(emptyList<String>(), islandSpeechBubbleLines("  "))
        assertEquals(listOf("去搭房子吧"), islandSpeechBubbleLines("  去搭房子吧  "))
        assertEquals(listOf("我们", "先造", "房子"), islandSpeechBubbleLines("我们先造房子", 2))
        val longSpeech = "先找几棵树采集木材，然后在营地旁选一个合适的地方搭房子，完工后一起去河边钓鱼吧"
        val page = islandSpeechBubbleLines(longSpeech, 9)
        assertEquals(3, page.size)
        assertTrue(page.all { it.length <= 9 })
        assertTrue(page.last().endsWith("…"))
        assertEquals(longSpeech.take(26) + "…", page.joinToString(""))
    }
}
