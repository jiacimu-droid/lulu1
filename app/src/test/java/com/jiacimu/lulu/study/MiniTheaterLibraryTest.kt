package com.jiacimu.lulu.study

import org.junit.Assert.*
import org.junit.Test

class MiniTheaterLibraryTest {
    @Test fun sourcePresetFiltersOnlyNamedSingleScenesAndKeepsOriginalPromptsVerbatim() {
        val raw = """{"prompts":[
            {"identifier":"first","name":"🔸超书信小剧场","content":"<small_theater>\n甲{{user}}、乙{{char}}\n不要修改原文</small_theater>","enabled":false},
            {"identifier":"second","name":"🌙小剧场规则","content":"通用规则"},
            {"identifier":"third","name":"新世界规则","content":"不是剧场"},
            {"identifier":"fourth","name":"🔸超论坛小剧场","content":"不少于35楼"}
        ]}"""
        val parsed = MiniTheaterLibrary.parse(raw)
        assertEquals(2, parsed.size)
        assertEquals(listOf("超书信小剧场", "超论坛小剧场"), parsed.map { it.title })
        assertEquals("<small_theater>\\n甲{{user}}、乙{{char}}\\n不要修改原文</small_theater>",
            parsed.first().prompt)
        assertTrue(parsed.all { it.status == "new" && it.text.isEmpty() })
        assertEquals(parsed.map { it.id }, MiniTheaterLibrary.parse(raw).map { it.id })
    }

    @Test fun convertedWorldBookSupportsBatchSelectionWithoutChangingPromptText() {
        val raw = """{"worldBooks":[
            {"title":"超时间对话小剧场","content":"原要求：每一轮深入回答，保留【场景】和{{user}}"},
            {"title":"超时间对话小剧场","content":"原要求：每一轮深入回答，保留【场景】和{{user}}"},
            {"title":"普通世界观","content":"不导入"},
            {"title":"超问答小剧场","content":""}
        ]}"""
        val entries = MiniTheaterLibrary.parse(raw)
        assertEquals(1, entries.size)
        assertEquals("超时间对话小剧场", entries.single().title)
        assertEquals("原要求：每一轮深入回答，保留【场景】和{{user}}", entries.single().prompt)
    }

    @Test fun originalNamesRemainSeparateWhenTheirPromptsDiffer() {
        val entries = MiniTheaterLibrary.parse("""{"prompts":[
            {"name":"A小剧场","content":"版本一"},
            {"name":"A小剧场","content":"版本二"}
        ]}""")
        assertEquals(2, entries.size)
        assertNotEquals(entries[0].id, entries[1].id)
    }
}
