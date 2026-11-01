package com.jiacimu.lulu.data

import org.junit.Assert.*
import org.junit.Test

class MemoryWorkingSetPolicyTest {
    @Test fun budgetCannotSeparateAnOldInterpretationFromItsTimeAndUncertainty() {
        val old = "【过去的主观判断｜不代表此刻事实】\n" +
            "曾经猜测她不希望被联系。\n" +
            "当时只有猜测，后来明确表示希望我主动；这个限定不能丢失。".repeat(40)
        val current = "【当前仍有效的约定】\n本人已接受跟进，结果尚未核实。"
        val rows = MemoryWorkingSetPolicy.auxiliaryCandidates(listOf("连续性" to "$old\n$current"))
        val result = MemoryWorkingSetPolicy.select(rows, 160)
        val delivered = result.sections.values.joinToString("\n\n")
        assertFalse(delivered.contains("曾经猜测"))
        assertFalse(delivered.contains("过去的主观判断"))
        assertTrue(delivered.contains(current))
        assertEquals(1, result.selected.size)
        assertEquals(1, result.omitted.size)
        assertTrue(result.usedCharacters <= result.budget)
    }

    @Test fun identicalWordsUnderDifferentAuthorityRemainDistinctCompleteBlocks() {
        val text = "【曾经的推测】\n她在休息\n" +
            "【对方本轮亲口说的话】\n她在休息"
        val rows = MemoryWorkingSetPolicy.auxiliaryCandidates(listOf("本人的上下文" to text))
        val result = MemoryWorkingSetPolicy.select(rows, 1000)
        assertEquals(2, result.selected.size)
        val delivered = result.sections.values.single()
        assertTrue(delivered.contains("【曾经的推测】\n她在休息"))
        assertTrue(delivered.contains("【对方本轮亲口说的话】\n她在休息"))
    }

    @Test fun conditionsAndNegationsStayWithTheStateAcrossBlankLinesAndCategoryLimits() {
        val state = "【本轮立场】\n愿意先等她洗澡。\n\n但没有取消跟进，后续时间由本人选择。"
        val rows = MemoryWorkingSetPolicy.auxiliaryCandidates(listOf("当前状态" to state))
        val complete = MemoryWorkingSetPolicy.select(rows, 1000)
        assertTrue(complete.sections.values.single().contains(state))
        val omitted = MemoryWorkingSetPolicy.select(rows, 1000,
            sectionLimits = mapOf("当前状态" to 25))
        assertTrue(omitted.sections.isEmpty())
        assertEquals(1, omitted.omitted.size)
        val plain = "无标题的完整状态\n除非新证据改变，否则保持原意。"
        assertEquals(plain, MemoryWorkingSetPolicy.auxiliaryCandidates(listOf("状态" to plain)).single().text)
    }

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
