package com.jiacimu.lulu.data

import android.content.Context
import com.jiacimu.lulu.LuluRepositories
import com.jiacimu.lulu.ai.LuluAiServices
import com.jiacimu.lulu.core.MemoryTier
import kotlinx.coroutines.runBlocking
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import java.util.concurrent.TimeUnit

@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE, sdk = [28])
class ImmediateMemoryExtractionTest {
    @Test fun importantFirstMessageRetriesAndPersistsBeforeBatchThreshold() = runBlocking {
        val server = MockWebServer()
        val result = JSONArray().put(JSONObject().put("kind", "Fact").put("content", "用户要求以后不要用旧昵称称呼她")
            .put("sourceEventIds", JSONArray().put("important-first")).put("tier", "Core").put("strength", 10))
        val body = JSONObject().put("choices", JSONArray().put(
            JSONObject().put("message", JSONObject().put("content", result.toString())).put("finish_reason", "stop"))).toString()
        server.enqueue(MockResponse().setResponseCode(503).setBody("temporary failure"))
        server.enqueue(MockResponse().setHeader("Content-Type", "application/json").setBody(body))
        server.start()
        try {
            val context = RuntimeEnvironment.getApplication() as Context
            context.getSharedPreferences("lulu_advanced_settings", Context.MODE_PRIVATE).edit()
                .putString("memory_extract_url", server.url("/").toString())
                .putString("memory_extract_key", "test-only").putString("memory_extract_model", "test-memory").commit()
            LuluRepositories.initialize(context)
            SharedExperienceTimeline.initialize(context)
            LuluAiServices.initialize(context)
            SharedExperienceTimeline.record("important-first", "role", "私聊", "用户", "你记住，以后不要再用旧昵称叫我",
                triggerExtraction = false, evidenceKind = EventEvidenceKind.UserStatement)
            assertTrue(runCatching { LuluRepositories.memory.summarizeNow("role") }.isFailure)
            assertTrue(LuluRepositories.memory.snapshot("role").isEmpty())
            LuluRepositories.memory.summarizeNow("role")
            assertEquals(MemoryTier.Core, LuluRepositories.memory.snapshot("role").single().tier)
            assertEquals(2, server.requestCount)
            repeat(2) { assertEquals("/chat/completions", server.takeRequest(5, TimeUnit.SECONDS)?.path) }
            val restored = LocalMemoryRepository().apply { initialize(context) }
            restored.summarizeNow("role")
            assertEquals(2, server.requestCount) // Successful immediate checkpoint survives restart.
            assertEquals(MemoryTier.Core, restored.snapshot("role").single().tier)
        } finally { server.shutdown() }
    }
}
