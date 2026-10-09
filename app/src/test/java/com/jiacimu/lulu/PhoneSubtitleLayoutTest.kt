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
    @Test fun everyCaptionBeatIncludesTheSpeakerWithoutChangingSpokenText() {
        val raw = "你好。今天我陪你聊会儿，等你困了再睡。"
        val rows = PhoneSubtitleLayout.captionRows(raw, "江渡")
        assertTrue(rows.size >= 2)
        assertTrue(rows.all { it.startsWith("江渡：") })
        assertEquals(PhoneSubtitleLayout.lines(raw),
            rows.map { it.removePrefix("江渡：") })
        assertTrue(PhoneSubtitleLayout.captionRows(raw, "你").all { it.startsWith("你：") })
    }

}
