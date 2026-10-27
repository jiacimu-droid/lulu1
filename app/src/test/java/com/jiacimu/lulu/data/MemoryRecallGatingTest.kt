package com.jiacimu.lulu.data

import com.jiacimu.lulu.core.MemoryEntry
import com.jiacimu.lulu.core.MemoryKind
import com.jiacimu.lulu.core.MemoryTier
import java.time.Instant
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class MemoryRecallGatingTest {

    private val now = Instant.parse("2026-10-28T12:00:00Z")

    private fun entry(id: String, content: String): MemoryEntry = MemoryEntry(
        id = id,
        characterId = "lulu",
        content = content,
        kind = MemoryKind.Fact,
        source = "test",
        occurredAt = now,
        createdAt = now,
        strength = 5,
        pinned = false,
        canRecallProactively = true,
        tier = MemoryTier.Stable,
        subject = "",
        slot = "",
    )

    @Test
    fun smallStoreSkipsGating() {
        val memories = (1..3).map { entry("s$it", "我每天睡觉") } +
            (1..2).map { entry("o$it", "无关内容$it") }
        val terms = memorySearchTerms("睡觉")
        assertEquals(terms, RelevantMemoryRecall.gateCommonTerms(terms, memories))
    }

    @Test
    fun commonTermIsGatedButRareTermKept() {
        val memories = (1..5).map { entry("s$it", "我每天睡觉") } +
            (1..15).map { entry("o$it", "无关内容$it") }
        val terms = memorySearchTerms("睡觉失眠")
        val gated = RelevantMemoryRecall.gateCommonTerms(terms, memories)
        // “睡觉”出现在 5/20 条记忆里（25%），没有区分度；“失眠”没有出现过，保留。
        assertFalse("睡觉" in gated)
        assertTrue("失眠" in gated)
    }

    @Test
    fun allGatedFallsBackToOriginalTerms() {
        val memories = (1..5).map { entry("s$it", "我每天睡觉") } +
            (1..15).map { entry("o$it", "无关内容$it") }
        val terms = memorySearchTerms("睡觉")
        assertEquals(terms, RelevantMemoryRecall.gateCommonTerms(terms, memories))
    }
}
