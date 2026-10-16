package com.jiacimu.lulu.data

import org.junit.Assert.*
import org.junit.Test

class MemoryWorkingSetPolicyTest {
    @Test fun latestCorrectionWinsUnderPressureAndTheEntireRenderedViewFits() {
        val old = MemoryPromptCandidate("old", "往事", "旧计划".repeat(50), 20)
        val correction = MemoryPromptCandidate("new", "原话", "计划已经更正，明天上午十点出发", 100)
        val result = MemoryWorkingSetPolicy.select(listOf(old, correction), 100, protectedCharacters = 25)
        assertEquals(listOf("new"), result.selected)
        assertTrue(result.omitted.containsKey("old"))
        assertEquals(result.usedCharacters, result.sections.values.joinToString("\n\n").length)
        assertTrue(result.usedCharacters + result.protectedCharacters <= result.budget)
    }

    @Test fun originalEvidenceReplacesItsSummaryButKeepsTheCharactersSubjectiveConcern() {
        val rows = listOf(
            MemoryPromptCandidate("raw", "原话", "用户：周末有考试", 100, sources = setOf("event"), originalEvidence = true),
            MemoryPromptCandidate("summary", "往事", "用户周末考试", 50, sources = setOf("event"), derivedSummary = true),
            MemoryPromptCandidate("concern", "挂心", "我担心她又熬夜复习，想晚点问问她", 40, sources = setOf("event")),
        )
        val result = MemoryWorkingSetPolicy.select(rows, 1_000)
        assertEquals(listOf("raw", "concern"), result.selected)
        assertTrue(result.omitted["summary"]!!.contains("原文"))
    }

    @Test fun oppositePreferencesAreNotTextualDuplicatesAndOneFactCannotCoverAnother() {
        val rows = listOf(
            MemoryPromptCandidate("like", "偏好", "用户喜欢咖啡", 100, sources = setOf("same-event"), derivedSummary = true),
            MemoryPromptCandidate("dislike", "偏好", "用户不喜欢咖啡", 90, sources = setOf("same-event"), derivedSummary = true),
        )
        assertEquals(2, MemoryWorkingSetPolicy.select(rows, 1_000).selected.size)
    }

    @Test fun largeArchiveRemainsBoundedAndProtectedOverflowIsExplicit() {
        val rows = (1..2_000).map { MemoryPromptCandidate("record:$it", "往事", "$it：" + "经历".repeat(100), 10) }
        val bounded = MemoryWorkingSetPolicy.select(rows, 2_000, 200)
        assertTrue(bounded.usedCharacters + bounded.protectedCharacters <= 2_000)
        assertTrue(bounded.omitted.size > 1_900)
        val overflow = MemoryWorkingSetPolicy.select(rows, 100, 300)
        assertTrue(overflow.selected.isEmpty())
        assertEquals(300, overflow.protectedCharacters)
    }

    @Test fun categoryQuotaCannotConsumeOtherCategoriesAndExactDuplicatesAreReported() {
        val rows = listOf(
            MemoryPromptCandidate("huge", "日记", "一".repeat(300), 100),
            MemoryPromptCandidate("small", "往事", "确切事实", 50),
            MemoryPromptCandidate("copy", "收藏", "确切事实", 40),
        )
        val result = MemoryWorkingSetPolicy.select(rows, 2_000, sectionLimits = mapOf("日记" to 100))
        assertEquals(listOf("small"), result.selected)
        assertTrue(result.omitted.containsKey("huge"))
        assertTrue(result.omitted.containsKey("copy"))
    }
}
