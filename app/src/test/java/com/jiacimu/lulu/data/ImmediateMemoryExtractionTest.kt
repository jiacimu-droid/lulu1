package com.jiacimu.lulu.data

import android.content.Context
import com.jiacimu.lulu.LuluRepositories
import com.jiacimu.lulu.ai.LuluAiServices
import com.jiacimu.lulu.core.MemoryTier
import com.sun.net.httpserver.HttpServer
import kotlinx.coroutines.runBlocking
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import java.net.InetSocketAddress
import java.util.concurrent.atomic.AtomicInteger

@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE, sdk = [28])
class ImmediateMemoryExtractionTest {
    @Test fun importantFirstMessageRetriesAndPersistsBeforeBatchThreshold() = runBlocking {
        val calls = AtomicInteger()
        val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        server.createContext("/chat/completions") { exchange ->
            exchange.requestBody.use { it.readBytes() }
            val count = calls.incrementAndGet()
            val result = JSONArray().put(JSONObject().put("kind", "Fact").put("content", "用户要求以后不要用旧昵称称呼她")
                .put("sourceEventIds", JSONArray().put("important-first")).put("tier", "Core").put("strength", 10))
            val body = if (count == 1) "temporary failure" else JSONObject().put("choices", JSONArray().put(
                JSONObject().put("message", JSONObject().put("content", result.toString())).put("finish_reason", "stop"))).toString()
            val bytes = body.toByteArray(Charsets.UTF_8)
            exchange.responseHeaders.add("Content-Type", "application/json")
            exchange.sendResponseHeaders(if (count == 1) 503 else 200, bytes.size.toLong())
            exchange.responseBody.use { it.write(bytes) }
        }
        server.start()
        try {
            val context = RuntimeEnvironment.getApplication() as Context
            context.getSharedPreferences("lulu_advanced_settings", Context.MODE_PRIVATE).edit()
                .putString("memory_extract_url", "http://127.0.0.1:${server.address.port}")
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
            assertEquals(2, calls.get())
            val restored = LocalMemoryRepository().apply { initialize(context) }
            restored.summarizeNow("role")
            assertEquals(2, calls.get()) // Successful immediate checkpoint survives restart.
            assertEquals(MemoryTier.Core, restored.snapshot("role").single().tier)
        } finally { server.stop(0) }
    }
}
