package com.jiacimu.lulu.data

import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE, sdk = [28])
class PerformanceAuditPersistenceTest {
    @Test fun newAuditFieldsSurviveRepositoryReloadWithoutSavingPromptText() = runBlocking {
        val app = RuntimeEnvironment.getApplication()
        val first = LocalPerformanceRepository(app)
        first.clearConsole()
        first.recordGeneration(
            source = "聊天",
            title = "测试审计",
            model = "test",
            provider = "example.invalid",
            reportedInputTokens = 70000,
            reportedOutputTokens = 55,
            cachedTokens = 100,
            estimatedInputTokens = 40000,
            estimatedOutputTokens = 55,
            breakdown = listOf(TokenBreakdownItem("固定规则", 72000, 40000)),
            promptMillis = 10,
            modelMillis = 20,
            totalMillis = 30,
            auditVersion = 1,
            textCharacters = 72000,
            coveredTextCharacters = 72000,
            textUtf8Bytes = 90000,
            imageCount = 2,
            imageDataUrlBytes = 1234,
            requestBodyBytes = 93000,
            messageCount = 4,
            reducedRequest = false,
            contextMode = "Full",
            streamedRequest = true,
        )
        val reloaded = LocalPerformanceRepository(app)
        val saved = reloaded.consoleRecords.first().first()
        assertEquals(70000L, saved.reportedInputTokens)
        assertEquals(1, saved.auditVersion)
        assertEquals(72000L, saved.coveredTextCharacters)
        assertEquals(2, saved.imageCount)
        assertEquals(1234L, saved.imageDataUrlBytes)
        assertEquals(93000L, saved.requestBodyBytes)
        assertEquals(4, saved.messageCount)
        assertEquals("example.invalid", saved.providerHost)
        assertEquals("聊天", saved.requestSource)
        assertEquals("Full", saved.contextMode)
        assertTrue(saved.streamedRequest)
        assertEquals(1, saved.breakdown.size)
    }

    @Test fun conversationStagesPersistWithoutBeingCountedAsAnotherModelCall() = runBlocking {
        val app = RuntimeEnvironment.getApplication()
        val first = LocalPerformanceRepository(app)
        first.clearTimings()
        first.clearConsole()
        val countBefore = first.usageRecords.first().size
        first.recordTiming("角色执行队列", 120_000, "角色=test；批次=7")
        first.recordTiming("消息到首条回复入库", 248_000, "会话=test；用户消息=user-7；回复=reply-7")
        val reloaded = LocalPerformanceRepository(app)
        assertEquals(countBefore, reloaded.usageRecords.first().size)
        assertTrue(reloaded.consoleRecords.first().isEmpty())
        val timings = reloaded.timingRecords.first()
        assertEquals(248_000L, timings.first().durationMillis)
        assertEquals("消息到首条回复入库", timings.first().stage)
        assertEquals(120_000L, timings.last().durationMillis)
        assertEquals("角色=test；批次=7", timings.last().detail)
    }

    @Test fun oldStyleUsageDefaultsToUnauditedWithoutFakeCoverage() = runBlocking {
        val app = RuntimeEnvironment.getApplication()
        val repository = LocalPerformanceRepository(app)
        repository.clearConsole()
        repository.recordGeneration(
            source = "其他",
            title = "旧版用量",
            model = "test",
            provider = "",
            reportedInputTokens = 1000,
            reportedOutputTokens = 50,
            cachedTokens = 0,
            estimatedInputTokens = 500,
            estimatedOutputTokens = 50,
            breakdown = emptyList(),
            promptMillis = 1, modelMillis = 1, totalMillis = 2,
        )
        val saved = LocalPerformanceRepository(app).consoleRecords.first().first()
        assertEquals(0, saved.auditVersion)
        assertEquals(0, saved.textCharacters)
        assertEquals(0L, saved.requestBodyBytes)
    }
}
