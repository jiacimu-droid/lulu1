package com.jiacimu.lulu

import org.junit.Assert.*
import org.junit.Test

class PhoneSubtitleLayoutTest {
    @Test fun punctuationBeatsDoNotChangeSpokenText() {
        val source = "你刚才说得好好笑哈哈哈哈！不过等一下，我还有一个很长很长的问题。你先听我说完好不好？"
        val lines = PhoneSubtitleLayout.lines(source)
        assertEquals(source, lines.joinToString(""))
        assertEquals(4, lines.size)
        assertEquals("不过等一下，", lines[1])
    }

    @Test fun longUnpunctuatedSentenceIsNotCutArbitrarily() {
        val source = "我想和你好好说一会儿话因为刚才有好多好多很多很多感受现在忍不住一次性全部说给你听"
        assertEquals(listOf(source), PhoneSubtitleLayout.lines(source))
    }

    @Test fun terminalPunctuationKeepsItsClosingQuotes() {
        val source = "他说：“你等我一下！”然后就走了。"
        val lines = PhoneSubtitleLayout.lines(source)
        assertTrue(lines.first().endsWith("！”"))
        assertEquals(source, lines.joinToString(""))
    }

    @Test fun blankAndPreexistingLineBreaksAreSafe() {
        assertTrue(PhoneSubtitleLayout.lines(" ").isEmpty())
        assertEquals(listOf("第一句。", "第二句。"), PhoneSubtitleLayout.lines("第一句。\n第二句。"))
    }

    @Test fun captionsRetainSpeakerLabels() {
        val raw = "你好。今天我陪你聊会儿，等你困了再睡。"
        val rows = PhoneSubtitleLayout.captionRows(raw, "江渡")
        assertTrue(rows.size >= 2)
        assertTrue(rows.all { it.startsWith("江渡：") })
        assertEquals(PhoneSubtitleLayout.lines(raw), rows.map { it.removePrefix("江渡：") })
    }
}
