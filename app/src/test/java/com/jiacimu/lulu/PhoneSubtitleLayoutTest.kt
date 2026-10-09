package com.jiacimu.lulu

import org.junit.Assert.*
import org.junit.Test

class PhoneSubtitleLayoutTest {
    @Test fun subtitleLineBreaksDoNotChangeOriginalSpeechCharacters() {
        val source = "你刚才说得好好笑哈哈哈哈！不过等一下，我还有一个很长很长的问题。你先听我说完好不好？"
        val shown = PhoneSubtitleLayout.format(source)
        assertTrue(shown.contains("\n"))
        assertEquals(source, shown.replace("\n", ""))
        assertTrue(PhoneSubtitleLayout.lines(source).all { it.length <= 31 })
    }

    @Test fun longUnpunctuatedSpeechStillHasReadableLines() {
        val source = "我想和你好好说一会儿话因为刚才有好多好多很多很多感受现在忍不住一次性全部说给你听"
        val lines = PhoneSubtitleLayout.lines(source)
        assertTrue(lines.size > 1)
        assertEquals(source, lines.joinToString(""))
        assertTrue(lines.all { it.length <= 31 })
    }

    @Test fun blankAndPreexistingLineBreaksAreSafe() {
        assertTrue(PhoneSubtitleLayout.lines(" ").isEmpty())
        assertEquals(listOf("第一句。", "第二句。"),
            PhoneSubtitleLayout.lines("第一句。\n第二句。"))
    }
}
