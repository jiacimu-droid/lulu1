package com.jiacimu.lulu.data

import android.content.Context
import com.jiacimu.lulu.LuluRepositories
import com.jiacimu.lulu.ai.CompanionContextMode
import com.jiacimu.lulu.ai.LuluAiServices
import com.jiacimu.lulu.ai.ModelConnection
import com.jiacimu.lulu.core.MemoryEntry
import com.jiacimu.lulu.core.MemoryKind
import com.jiacimu.lulu.core.MemoryTier
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
import java.util.concurrent.TimeUnit

@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE, sdk = [28])
class RelationshipPreferenceGatewayTest {
    @Before fun before() { MemoryTestEnvironment.release() }
    @After fun after() { MemoryTestEnvironment.release() }

    @Test fun leanExpressionActuallyReceivesEnduringPreferencesAndCoreButIsolatedTasksDoNot() = runBlocking {
        val context = RuntimeEnvironment.getApplication() as Context
        LuluRepositories.initialize(context)
        SharedExperienceTimeline.initialize(context)
        LuluAiServices.initialize(context)
        SharedExperienceTimeline.record("kiss-contract", "gateway-role", "私聊", "用户",
            "以后每次抛飞吻的时候都要发mwah或者mua", triggerExtraction = false,
            evidenceKind = EventEvidenceKind.UserStatement)
        LuluRepositories.memory.upsert(MemoryEntry(id = "core-remember", characterId = "gateway-role",
            content = "用户郑重要求记住这项特殊相处约定", kind = MemoryKind.Fact, tier = MemoryTier.Core,
            source = "manual", occurredAt = null, createdAt = java.time.Instant.now(),
            strength = 10, pinned = false, canRecallProactively = true))
        val server = MockWebServer()
        repeat(2) { server.enqueue(MockResponse().setHeader("Content-Type", "application/json")
            .setBody("""{"choices":[{"message":{"content":"好"},"finish_reason":"stop"}]}""")) }
        server.start()
        try {
            val connection = ModelConnection(server.url("/").toString(), "test-only", "test")
            for (mode in listOf(CompanionContextMode.CharacterAndScenario, CompanionContextMode.Isolated)) {
                LuluAiServices.gateway.generate("gateway-role", "用户今天有点累", "自然回应",
                    "聊天表达渲染", "test", connectionOverride = connection, contextMode = mode).getOrThrow()
                val body = JSONObject(server.takeRequest(5, TimeUnit.SECONDS)!!.body.readUtf8())
                val prompt = body.getJSONArray("messages").getJSONObject(0).getString("content")
                if (mode == CompanionContextMode.CharacterAndScenario) {
                    assertTrue(prompt.contains("mwah或者mua"))
                    assertTrue(prompt.contains("特殊相处约定"))
                    assertTrue(prompt.contains("独立于普通召回"))
                } else {
                    assertFalse(prompt.contains("mwah"))
                    assertFalse(prompt.contains("特殊相处约定"))
                }
            }
            assertEquals(2, server.requestCount) // No additional model call for durable preferences.
        } finally { server.shutdown() }
    }
}
