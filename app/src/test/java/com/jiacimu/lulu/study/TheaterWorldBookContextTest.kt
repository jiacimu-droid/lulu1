package com.jiacimu.lulu.study

import com.jiacimu.lulu.core.WorldBookEntry
import org.junit.Assert.*
import org.junit.Test

class TheaterWorldBookContextTest {
    private fun entry(id: String, content: String, global: Boolean = false) =
        WorldBookEntry(id, "规则$id", content, global, emptyMap())

    @Test fun explicitStorySelectionReadsNonGlobalRulesAndExcludesUnselectedOnes() {
        val entries = listOf(entry("a", "没有魔法"), entry("b", "魔法世界", true))
        val context = TheaterWorldBookContext.capture(setOf("a"), entries)
        assertTrue(context.promptText().contains("没有魔法"))
        assertFalse(context.promptText().contains("魔法世界"))
        assertTrue(context.promptText().contains("旧章节规划冲突"))
        assertEquals("", TheaterWorldBookContext.capture(emptySet(), entries).promptText())
    }

    @Test fun editingOrDeletingSelectedContentRejectsOldResultsButOtherChangesDoNot() {
        val a = entry("a", "旧规则")
        val b = entry("b", "其他")
        val context = TheaterWorldBookContext.capture(setOf("a"), listOf(a, b))
        context.requireUnchanged(setOf("a"), listOf(b.copy(content = "另一本书修改"), a.copy(globalEnabled = true)))
        assertThrows(IllegalStateException::class.java) {
            context.requireUnchanged(setOf("a"), listOf(a.copy(content = "新规则"), b))
        }
        assertThrows(IllegalStateException::class.java) { context.requireUnchanged(setOf("a"), listOf(b)) }
        assertThrows(IllegalStateException::class.java) { context.requireUnchanged(setOf("b"), listOf(a, b)) }
    }
}
