package com.jiacimu.lulu.ai

import android.content.Context
import com.jiacimu.lulu.LuluRepositories
import kotlinx.coroutines.runBlocking
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import java.util.UUID

@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE, sdk = [28])
class ModelReasoningEffortTest {
    @Test fun reasoningSettingPersistsPerArchiveAndDoesNotChangeOthers() {
        val app = RuntimeEnvironment.getApplication() as Context
        val store = ModelConnectionStore.create(app)
        val config = store.saveConfiguration(null, "reasoning-test-" + UUID.randomUUID(),
            "https://example.com/v1", "private-test-key")
        val a = store.addArchive(config.id, "model-one")
        val b = store.addArchive(config.id, "model-two")
        assertNull(store.resolveConnection(a.id).reasoningEffort)
        store.updateArchiveReasoningEffort(a.id, "medium")
        val loaded = ModelConnectionStore.create(app)
        assertEquals("medium", loaded.resolveConnection(a.id).reasoningEffort)
        assertNull(loaded.resolveConnection(b.id).reasoningEffort)
        assertEquals("medium", loaded.reasoningEffortFor(loaded.resolveConnection(a.id)))
        loaded.updateArchiveReasoningEffort(a.id, null)
        assertNull(ModelConnectionStore.create(app).resolveConnection(a.id).reasoningEffort)
        assertThrows(IllegalArgumentException::class.java) {
            loaded.updateArchiveReasoningEffort(a.id, "xhigh")
        }
    }

    @Test fun selectedThinkingLevelReachesRealCompatibleRequestAndAutoOmitsIt() = runBlocking {
        val app = RuntimeEnvironment.getApplication() as Context
        LuluRepositories.initialize(app)
        MockWebServer().use { server ->
            repeat(2) {
                server.enqueue(MockResponse().setHeader("Content-Type", "application/json")
                    .setBody("""{"choices":[{"message":{"content":"ok"},"finish_reason":"stop"}]}"""))
            }
            server.start()
            val store = ModelConnectionStore.create(app)
            val cfg = store.saveConfiguration(null, "wire-thinking-" + UUID.randomUUID(),
                server.url("/v1").toString(), "wire-test-key")
            val archive = store.addArchive(cfg.id, "gemini-test")
            store.updateArchiveReasoningEffort(archive.id, "high")
            val gateway = CompanionModelGateway(store)
            val override = ModelConnection(server.url("/v1").toString(), "wire-test-key", "gemini-test")
            for (expected in listOf("high", "")) {
                assertEquals("ok", gateway.generate(
                    characterId = "reasoning-wire-test", facts = "", instruction = "短回复",
                    source = "思考等级回归", title = "测试", maxTokens = 500,
                    contextMode = CompanionContextMode.Isolated,
                    connectionOverride = override,
                ).getOrThrow().text)
                val body = JSONObject(server.takeRequest().body.readUtf8())
                assertEquals(60_000, body.getInt("max_tokens"))
                if (expected.isBlank()) assertFalse(body.has("reasoning_effort"))
                else assertEquals(expected, body.getString("reasoning_effort"))
                store.updateArchiveReasoningEffort(archive.id, null)
            }
            assertEquals(2, server.requestCount)
        }
    }
}
