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
        // Match production startup: the vector feature flag and connection
        // live in MemoryModelRuntime, not in the chat model gateway.
        MemoryModelRuntime.initialize(context)
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

    @Test fun embeddingRecallNeverBuysAnExtraSemanticVerificationChatRequest() = runBlocking {
        initialize()
        val context = RuntimeEnvironment.getApplication() as Context
        val prefs = context.getSharedPreferences("lulu_advanced_settings", Context.MODE_PRIVATE)
        val old = listOf("memory_vector_enabled", "memory_embedding_url",
            "memory_embedding_key", "memory_embedding_model").associateWith {
            if (it == "memory_vector_enabled") prefs.getBoolean(it, false).toString()
            else prefs.getString(it, null)
        }
        val server = MockWebServer()
        server.enqueue(MockResponse().setHeader("Content-Type", "application/json")
            .setBody("""{"data":[{"index":0,"embedding":[1.0,0.0]}]}"""))
        server.start()
        val entries = listOf(
            memory("night-stars", "她喜欢夜晚看星星"),
            memory("morning-tea", "她喜欢喝茶"),
            memory("garden-trip", "她去过公园"),
            memory("music-song", "她喜欢轻音乐"),
            memory("shopping", "她买了一件外套"),
        )
        try {
            // Populate the durable local vector index while vector retrieval
            // is still off, so this test measures ONLY this query's HTTP calls.
            prefs.edit().putBoolean("memory_vector_enabled", false).commit()
            entries.forEach { LuluRepositories.memory.save(it) }
            val connection = ModelConnection(server.url("/").toString(), "test-key", "embed-only")
            val values = listOf(
                floatArrayOf(1f, 0f), floatArrayOf(0f, 1f),
                floatArrayOf(-1f, 0f), floatArrayOf(0f, -1f), floatArrayOf(0.3f, 0.95f),
            )
            entries.forEachIndexed { i, item ->
                val key = MemoryEmbeddingIndex.key(connection, item)
                MemoryEmbeddingIndex.put(key, item.id, values[i])
                assertArrayEquals("Seeded vector must be readable from the active app's index",
                    values[i], MemoryEmbeddingIndex.get(key, item.id), 0.00001f)
            }
            assertTrue("All seeded memories must be available for retrieval",
                LuluRepositories.memory.snapshot("recall-integration").map { it.id }.containsAll(entries.map { it.id }))
            prefs.edit().putBoolean("memory_vector_enabled", true)
                .putString("memory_embedding_url", server.url("/").toString())
                .putString("memory_embedding_key", "test-key")
                .putString("memory_embedding_model", "embed-only").commit()
            assertTrue("Test must initialize the production memory runtime",
                MemoryModelRuntime.vectorEnabled())
            assertEquals("embed-only", MemoryModelRuntime.embeddingConnection()?.model)
            RelevantMemoryRecall.recallDetailed(
                "recall-integration", "想看星空", limit = 8, localOnly = false)
            assertEquals("one embedding POST; zero isolated LLM verifier POST", 1, server.requestCount)
            assertTrue(server.takeRequest(5, TimeUnit.SECONDS)!!.path!!.contains("/embeddings"))
        } finally {
            val reset = prefs.edit()
            for ((name, value) in old) {
                if (name == "memory_vector_enabled")
                    reset.putBoolean(name, value == "true")
                else if (value == null) reset.remove(name)
                else reset.putString(name, value)
            }
            reset.commit()
            entries.forEach { MemoryEmbeddingIndex.removeMemory(it.id) }
            server.shutdown()
        }
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
