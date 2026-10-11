package com.jiacimu.lulu

import org.junit.Assert.*
import org.junit.Test

class PhoneSubtitleLayoutTest {
    @Test fun punctuationBeatsDoNotChangeSpokenText() {
        val source = "你刚才说得好好笑哈哈哈哈！不过等一下，我还有一个很长很长的问题。你先听我说完好不好？"
        val lines = PhoneSubtitleLayout.lines(source)
        assertEquals(source, lines.joinToString(""))
        assertTrue(lines.size in 2..3)
        assertTrue(lines.none { it == "不过等一下，" })
        assertTrue(lines.all { it.length >= 12 })
    }

    @Test fun longUnpunctuatedSentenceIsNotCutArbitrarily() {
        val source = "我想和你好好说一会儿话因为刚才有好多好多很多很多感受现在忍不住一次性全部说给你听"
        assertEquals(listOf(source), PhoneSubtitleLayout.lines(source))
    }

    @Test fun terminalPunctuationKeepsItsClosingQuotes() {
        val source = "他说：“你等我一下！”然后就走了。"
        val lines = PhoneSubtitleLayout.lines(source)
        assertTrue(lines.joinToString("").contains("！”"))
        assertTrue(lines.none { it.endsWith("！") })
        assertEquals(source, lines.joinToString(""))
    }

    @Test fun blankAndPreexistingLineBreaksAreSafe() {
        assertTrue(PhoneSubtitleLayout.lines(" ").isEmpty())
        assertEquals(listOf("第一句。", "第二句。"), PhoneSubtitleLayout.lines("第一句。\n第二句。"))
    }

    @Test fun isolatedCommasAndSingleWordRepliesDoNotMakeTinyBubbles() {
        val raw = "宝宝，嗯，等我一下，我其实有好多好多话想慢慢告诉你。"
        val lines = PhoneSubtitleLayout.lines(raw)
        assertEquals(raw, lines.joinToString(""))
        assertTrue(lines.size <= 2)
        assertTrue(lines.none { it == "宝宝，" || it == "嗯，" })
        assertTrue(lines.all { it.isNotBlank() })
    }

    @Test fun maxCharactersReallyInfluencesNaturalClauseGrouping() {
        val raw = "今天我真的挺开心的，因为你愿意认真听我说。那我也想好好告诉你，我为什么这么在意这件事。"
        val compact = PhoneSubtitleLayout.lines(raw, 24)
        val relaxed = PhoneSubtitleLayout.lines(raw, 58)
        assertEquals(raw, compact.joinToString(""))
        assertEquals(raw, relaxed.joinToString(""))
        assertTrue(compact.size >= relaxed.size)
    }

    @Test fun captionsRetainSpeakerLabels() {
        val raw = "你好。今天我陪你聊会儿，等你困了再睡。"
        val rows = PhoneSubtitleLayout.captionRows(raw, "江渡")
        assertEquals(1, rows.size)
        assertTrue(rows.all { it.startsWith("江渡：") })
        assertEquals(PhoneSubtitleLayout.lines(raw), rows.map { it.removePrefix("江渡：") })
    }
}
