package com.jiacimu.lulu.data

import org.junit.Assert.*
import org.junit.Test

class TokenConsolePresentationTest {
    @Test fun readableGroupsRetainEveryOriginalBreakdownAndTotal() {
        val parts = listOf(
            TokenBreakdownItem("基础身份与通用规则", 1800, 1000),
            TokenBreakdownItem("角色人设", 2500, 1400),
            TokenBreakdownItem("成长与持续动机", 5400, 3000),
            TokenBreakdownItem("近期原始时间线（新原话优先）", 4000, 2200),
            TokenBreakdownItem("本轮背景依据 / 第9块（标题不记录）", 2500, 1400),
            TokenBreakdownItem("历史对话 #3 / user", 100, 56),
            TokenBreakdownItem("本轮完整输入 / user", 400, 223),
        )
        val grouped = TokenConsolePresentation.groups(parts)
        assertEquals(4, grouped.size)
        assertEquals(listOf("人物与固定规则", "长期记忆与状态",
            "近期历史与召回", "本轮感知与消息"), grouped.map { it.title })
        assertEquals(parts.sumOf { it.chars.toLong() }, grouped.sumOf { it.chars })
        assertEquals(parts.sumOf { it.estimatedTokens.toLong() }, grouped.sumOf { it.estimatedTokens })
        assertEquals(parts.toSet(), grouped.flatMap { it.items }.toSet())
    }

    @Test fun providerTotalIsReconciledWithoutClaimingExactCategoryTokenization() {
        val parts = listOf(
            TokenBreakdownItem("基础身份与通用规则", 28714, 15954),
            TokenBreakdownItem("核心记忆", 22643, 12579),
            TokenBreakdownItem("近期原始时间线", 10092, 5606),
            TokenBreakdownItem("本轮完整输入 / user", 17494, 9719),
        )
        val groups = TokenConsolePresentation.groups(parts)
        val totalChars = parts.sumOf { it.chars }.toLong()
        val rec = record("real", 40, 70869, totalChars.toInt())
            .copy(breakdown = parts)
        val split = TokenConsolePresentation.allocateReportedInput(rec, groups)!!
        assertEquals(4, split.size)
        assertEquals(70869L, split.sum())
        // The historical character formula is only 43,858 tokens. This is
        // not 27,011 hidden text tokens: per-section counts are unobserved.
        assertEquals(43858L, groups.sumOf { it.estimatedTokens })
        assertTrue(split[0] > groups[0].estimatedTokens)
        assertNull(TokenConsolePresentation.allocateReportedInput(rec.copy(imageCount = 1), groups))
        assertNull(TokenConsolePresentation.allocateReportedInput(rec.copy(coveredTextCharacters = 12), groups))
        assertNull(TokenConsolePresentation.allocateReportedInput(rec.copy(auditVersion = 0), groups))
    }

    private fun record(id: String, at: Long, tokens: Long, chars: Int,
        model: String = "same-model", provider: String = "same-provider",
        imageCount: Int = 0,
    ) = TokenConsoleRecord(
        id = id, source = ApiUsageSource.OTHER, title = "检测", model = model,
        createdAtMillis = at, reportedInputTokens = tokens,
        reportedOutputTokens = 5, effectiveInputTokens = tokens, effectiveOutputTokens = 5,
        cachedTokens = 0, breakdown = listOf(TokenBreakdownItem("输入", chars, chars / 2)),
        auditVersion = 1, textCharacters = chars, coveredTextCharacters = chars.toLong(),
        imageCount = imageCount, providerHost = provider, requestSource = "后台主动感知",
    )

    @Test fun sameProviderHistoricRatioOnlyUsesEarlierFullTextRecords() {
        val current = record("now", 1000, 37000, 55000)
        val earlier = listOf(record("a", 1, 3000, 4500), record("b", 2, 4000, 6000),
            record("c", 3, 5000, 7500))
        val out = TokenConsolePresentation.calibratedEstimate(current, earlier + current +
            record("other-provider", 4, 90000, 3000, provider = "different") +
            record("future", 3000, 90000, 3000))!!
        assertEquals(3, out.sampleCount)
        assertEquals(36667L, out.tokens)
        assertTrue(out.sameSource)
        assertNull(TokenConsolePresentation.calibratedEstimate(
            current, earlier.map { it.copy(imageCount = 1) } + current))
    }
}
