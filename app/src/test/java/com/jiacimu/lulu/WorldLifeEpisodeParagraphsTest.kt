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
    @Test fun preservesStandaloneLegacyMindAndDialogueLabels() {
        val paragraphs = WorldLifeEpisodeParagraphs.parse(
            "【心声】\n他也不知道为什么突然想起那件事。\n\n【对白】\n“再试一次。”"
        )
        assertEquals(listOf(WorldLifeParagraphKind.THOUGHT, WorldLifeParagraphKind.SPEECH),
            paragraphs.map { it.kind })
        assertEquals("再试一次。", paragraphs.last().text.trim('“', '”'))
    }

    @Test fun longNarrationBreaksOnlyBetweenSentencesWithoutDroppingWords() {
        val original = "夜色慢慢落下来。".repeat(30)
        val pieces = WorldLifeEpisodeParagraphs.parse(original)
        assertTrue(pieces.size > 1)
        assertEquals(original, pieces.joinToString("") { it.text })
        assertTrue(pieces.all { it.kind == WorldLifeParagraphKind.NARRATION })
    }

    @Test fun namedDialogueSeparatesTheSpeakerFromTheirActualWords() {
        val scene = "江渡：“好呀，我们下次一起去游戏馆。”\\n星禾：“我先把这关打完！”"
            .replace("\\n", "\n")
        val parts = WorldLifeEpisodeParagraphs.parse(scene, setOf("江渡", "星禾"))
        assertEquals(2, parts.size)
        assertEquals(listOf("江渡", "星禾"), parts.map { it.speaker })
        assertTrue(parts.all { it.kind == WorldLifeParagraphKind.SPEECH })
        assertEquals("“我先把这关打完！”", parts.last().text)
    }

    @Test fun labelledDialogueCanShowSpeakerWithoutInventingNewSpeech() {
        val parts = WorldLifeEpisodeParagraphs.parse(
            "【对白】江渡：我先去看看。\\n\\n【叙述】他转身离开。".replace("\\n", "\n"))
        assertEquals("江渡", parts.first().speaker)
        assertEquals("我先去看看。", parts.first().text)
        assertEquals(WorldLifeParagraphKind.NARRATION, parts.last().kind)
        assertEquals("", parts.last().speaker)
    }

    @Test fun naturallyQuotedParagraphIsDialogueButInlineQuoteStaysNarration() {
        val paragraphs = WorldLifeEpisodeParagraphs.parse("他说：“好吧。”\n\n“要试试看吗？”")
        assertEquals(WorldLifeParagraphKind.NARRATION, paragraphs.first().kind)
        assertEquals(WorldLifeParagraphKind.SPEECH, paragraphs.last().kind)
    }

    @Test fun unknownAttributionRemainsWholeAndExplicitNarrationKeepsItsKind() {
        val text = "陌生者：“这只是原文里的引述。”"
        val unknown = WorldLifeEpisodeParagraphs.parse(text, setOf("江渡")).single()
        assertEquals(WorldLifeParagraphKind.NARRATION, unknown.kind)
        assertEquals(text, unknown.text)
        assertEquals("", unknown.speaker)
        val narration = WorldLifeEpisodeParagraphs.parse("【叙述】江渡：“好吧。”", setOf("江渡")).single()
        assertEquals(WorldLifeParagraphKind.NARRATION, narration.kind)
        assertEquals("江渡：“好吧。”", narration.text)
        assertEquals("", narration.speaker)
    }

    @Test fun customSpeakerNamesAreRecognizedWithoutNarrationWordBlacklists() {
        val custom = WorldLifeEpisodeParagraphs.parse("他说：“这是我的角色名。”", setOf("他说")).single()
        assertEquals(WorldLifeParagraphKind.SPEECH, custom.kind)
        assertEquals("他说", custom.speaker)
        assertEquals("“这是我的角色名。”", custom.text)
    }
}
