package com.jiacimu.lulu.data

import android.content.Context
import com.jiacimu.lulu.LuluRepositories
import com.jiacimu.lulu.ai.CompanionContextMode
import com.jiacimu.lulu.ai.LuluAiServices
import com.jiacimu.lulu.ai.ModelConnection
import kotlinx.coroutines.runBlocking
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import java.time.Instant
import java.util.concurrent.TimeUnit

@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE, sdk = [28])
class CharacterContinuityGatewayTest {
    @Before fun before() { MemoryTestEnvironment.release() }
    @After fun after() { MemoryTestEnvironment.release() }

    @Test fun fullCharacterScenesShareHistoricalAnchorButReducedAndIsolatedModesDoNot() = runBlocking {
        val context = RuntimeEnvironment.getApplication() as Context
        LuluRepositories.initialize(context)
        SharedExperienceTimeline.initialize(context)
        LuluAiServices.initialize(context)
        val role = "continuity-all-real-world-surfaces"
        SharedExperienceTimeline.record(
            eventId = "earlier-chat-actual-words", characterId = role, channel = "私聊",
            speaker = "角色", content = "那本书读到一半，我还记得那一章。",
            occurredAt = Instant.now().minusSeconds(7_200), triggerExtraction = false,
            evidenceKind = EventEvidenceKind.CharacterStatement,
        )
        val server = MockWebServer()
        repeat(3) {
            server.enqueue(MockResponse().setHeader("Content-Type", "application/json")
                .setBody("""{"choices":[{"message":{"content":"知道了"},"finish_reason":"stop"}]}"""))
        }
        server.start()
        try {
            val conn = ModelConnection(server.url("/").toString(), "test-only", "test")
            val modes = listOf(CompanionContextMode.Full,
                CompanionContextMode.CharacterAndScenario, CompanionContextMode.Isolated)
            modes.forEach { mode ->
                LuluAiServices.gateway.generate(role, "这次是一个新场景", "自然回应",
                    "统一连续性回归测试", "test", connectionOverride = conn,
                    contextMode = mode).getOrThrow()
                val json = JSONObject(server.takeRequest(5, TimeUnit.SECONDS)!!.body.readUtf8())
                val prompt = json.getJSONArray("messages").getJSONObject(0).getString("content")
                if (mode == CompanionContextMode.Full) {
                    assertTrue(prompt.contains("人物连续性锚点"))
                    assertTrue(prompt.contains("那本书读到一半"))
                } else {
                    assertFalse(prompt.contains("人物连续性锚点"))
                    assertFalse(prompt.contains("那本书读到一半"))
                }
            }
            assertEquals(3, server.requestCount)
        } finally { server.shutdown() }
    }
}
