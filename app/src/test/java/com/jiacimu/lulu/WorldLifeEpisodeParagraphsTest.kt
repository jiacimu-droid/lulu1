package com.jiacimu.lulu

import org.junit.Assert.*
import org.junit.Test

class WorldLifeEpisodeParagraphsTest {
    @Test fun acceptsLegacyProseWithoutTags() {
        val result = WorldLifeEpisodeParagraphs.parse("灯亮了。江渡没有立即坐下。\n\n他重新想了一遍。")
        assertEquals(2, result.size)
        assertEquals(WorldLifeParagraphKind.NARRATION, result.first().kind)
        assertEquals("他重新想了一遍。", result.last().text)
    }
    @Test fun rendersOptionalLabelsWithoutLosingWords() {
        val input = "【叙述】他打开灯。\n\n【心声】今晚总得做个决定。\n\n【对白】“好吧。”"
        val paragraphs = WorldLifeEpisodeParagraphs.parse(input)
        assertEquals(listOf(WorldLifeParagraphKind.NARRATION,
            WorldLifeParagraphKind.THOUGHT, WorldLifeParagraphKind.SPEECH),
            paragraphs.map { it.kind })
        assertEquals("“好吧。”", paragraphs.last().text)
    }
    @Test fun naturallyQuotedParagraphIsDialogueButInlineQuoteStaysNarration() {
        val paragraphs = WorldLifeEpisodeParagraphs.parse("他说：“好吧。”\n\n“要试试看吗？”")
        assertEquals(WorldLifeParagraphKind.NARRATION, paragraphs.first().kind)
        assertEquals(WorldLifeParagraphKind.SPEECH, paragraphs.last().kind)
    }
}
