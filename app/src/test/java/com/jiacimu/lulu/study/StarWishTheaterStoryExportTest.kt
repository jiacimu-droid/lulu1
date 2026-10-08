package com.jiacimu.lulu.study

import org.junit.Assert.*
import org.junit.Test

class StarWishTheaterStoryExportTest {
    @Test fun exportsSynopsisHighlightsHookAndAllChapterProseInOrder() {
        val bible = StarWishStoryBible(
            overview = "魔尊与主角的契约故事简介",
            highlights = "反向命令魔尊的高光场面",
            hook = "一次误念的血誓",
            hiddenLine = "内部秘密，不应当出现在小说导出中",
        )
        val chapters = listOf(
            StarWishTheaterChapter(theater = "魔尊", chapter = 2, title = "对峙", content = "第二章完整内容。", userInfluence = "内部创作指令"),
            StarWishTheaterChapter(theater = "魔尊", chapter = 1, title = "血誓", content = "第一章完整内容。", userInfluence = "不对外展示"),
        )
        val exported = StarWishTheaterStoryExport.render(
            title = "魔尊",
            chapters = chapters,
            storyGuide = "【世界前提】旧世界背景",
            bible = bible,
            seedIntro = "种子简介",
        )
        assertTrue(exported.contains("【故事简介】\n魔尊与主角的契约故事简介"))
        assertTrue(exported.contains("【核心看点】\n反向命令魔尊的高光场面"))
        assertTrue(exported.contains("【开篇钩子】\n一次误念的血誓"))
        assertTrue(exported.contains("小说正文（共2章）"))
        assertTrue(exported.indexOf("第一章完整内容。") < exported.indexOf("第二章完整内容。"))
        assertFalse(exported.contains("内部秘密"))
        assertFalse(exported.contains("内部创作指令"))
        assertFalse(exported.contains("不对外展示"))
    }

    @Test fun fallsBackToStoredGuideAndSupportsEmptyBooks() {
        val guide = "【故事核心】\n契约反转的故事。\n【核心看点】\n魔尊听从她的命令。\n【开篇钩子】\n他跪在殿前。\n【用户原始创作要求】\n私人的附加说明"
        val exported = StarWishTheaterStoryExport.render("新故事", emptyList(), guide, null)
        assertTrue(exported.contains("契约反转的故事。"))
        assertTrue(exported.contains("魔尊听从她的命令。"))
        assertTrue(exported.contains("他跪在殿前。"))
        assertTrue(exported.contains("（尚未生成章节）"))
        assertFalse(exported.contains("私人的附加说明"))
    }

    @Test fun manuallyEditedStorySynopsisOverridesOlderBible() {
        val exported = StarWishTheaterStoryExport.render(
            title = "夜雪",
            chapters = emptyList(),
            storyGuide = "【故事核心】新版的故事简介。",
            bible = StarWishStoryBible(overview = "旧的故事简介。"),
        )
        assertTrue(exported.contains("【故事简介】\n新版的故事简介。"))
        assertFalse(exported.contains("旧的故事简介。"))
    }

    @Test fun fileNameCannotContainInvalidPathCharacters() {
        val name = StarWishTheaterStoryExport.suggestedFileName("魔尊/归来:第一幕")
        assertEquals("魔尊_归来_第一幕_全部章节.txt", name)
    }
}
