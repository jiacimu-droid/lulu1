package com.jiacimu.lulu.data

import android.content.Context
import com.jiacimu.lulu.LuluRepositories
import com.jiacimu.lulu.ai.LuluAiServices
import com.jiacimu.lulu.ai.ModelConnection
import com.jiacimu.lulu.core.MemoryEntry
import com.jiacimu.lulu.core.MemoryKind
import java.time.Instant
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.runBlocking
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE, sdk = [28])
class MemoryRecallIntegrationTest {
    @Before fun before() { MemoryTestEnvironment.release() }
    @After fun after() { MemoryTestEnvironment.release() }
    private fun initialize() {
        val context = RuntimeEnvironment.getApplication() as Context
        LuluRepositories.initialize(context)
        SharedExperienceTimeline.initialize(context)
        LuluAiServices.initialize(context)
    }
    private fun memory(id: String, text: String) = MemoryEntry(id, "recall-integration", text,
        MemoryKind.Fact, "manual", null, Instant.now(), 5, false, true)

    @Test fun smallUnSummarizedLifeEventsCanBeRecalledByAShortSpecificCue() = runBlocking {
        initialize()
        SharedExperienceTimeline.record("orange-cat", "recall-integration", "私聊", "用户",
            "今天路上遇到一只橘猫", triggerExtraction = false, sessionId = "cat-chat",
            evidenceKind = EventEvidenceKind.UserStatement)
        assertTrue(LuluRepositories.memory.snapshot("recall-integration").isEmpty())
        val assembled = UnifiedMemoryOrchestrator.assemble("recall-integration",
            UnifiedMemoryRequest(currentInput = "橘猫"), localOnly = true)
        assertTrue(assembled.sourceEvidence.contains("橘猫"))
        SharedExperienceTimeline.deleteEvent("orange-cat")
        assertTrue(UnifiedMemoryOrchestrator.assemble("recall-integration",
            UnifiedMemoryRequest(currentInput = "橘猫"), localOnly = true).sourceEvents.isEmpty())
    }

    @Test fun ordinaryMemoryBudgetIsHonoredAndTheTraceExplainsTheOmission() = runBlocking {
        initialize()
        LuluRepositories.memory.save(memory("coffee", "用户喜欢喝咖啡"))
        val assembled = UnifiedMemoryOrchestrator.assemble("recall-integration",
            UnifiedMemoryRequest(currentInput = "咖啡"), memoryCharacterBudget = 0,
            evidenceCharacterBudget = 0, localOnly = true)
        assertTrue(assembled.memories.isEmpty())
        assembled.recordActualPrompt(PromptMemoryRenderer.renderWithTrace(assembled.memories, 0))
        assertEquals("摘要字数预算", MemoryInspectionStore.snapshot("recall-integration")!!.omitted["coffee"])
        assertTrue(MemoryInspectionStore.snapshot("recall-integration")!!.injected.isEmpty())
    }

    @Test fun selectedActionRetrievesItsMethodIntoTheActualLeanRequestWithNoExtraModelCall() = runBlocking {
        initialize()
        LuluRepositories.memory.save(memory("humor-method", "她不喜欢讲笑话时铺垫太长，短一点才适合她"))
        val server = MockWebServer()
        server.enqueue(MockResponse().setHeader("Content-Type", "application/json")
            .setBody("""{"choices":[{"message":{"content":"好"},"finish_reason":"stop"}]}"""))
        server.start()
        try {
            LuluAiServices.gateway.generate("recall-integration", "用户说有点累", "自然表达已选定的意图",
                "聊天表达渲染", "test", connectionOverride = ModelConnection(server.url("/").toString(), "test-only", "test"),
                memoryRequest = UnifiedMemoryRequest(currentInput = "有点累", actionContext = "讲笑话逗她开心")).getOrThrow()
            val body = JSONObject(server.takeRequest(5, TimeUnit.SECONDS)!!.body.readUtf8())
            assertTrue(body.getJSONArray("messages").getJSONObject(0).getString("content").contains("铺垫太长"))
            assertEquals(1, server.requestCount)
            val trace = MemoryInspectionStore.snapshot("recall-integration")!!
            assertTrue(trace.promptRecorded)
            assertTrue("memory:humor-method" in trace.injected)
        } finally { server.shutdown() }
    }
}
