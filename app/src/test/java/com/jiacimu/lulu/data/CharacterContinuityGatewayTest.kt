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

    @Test fun currentCueReachesBothFullAndCompactInnerLifeInActualGatewayRequests() = runBlocking {
        val context = RuntimeEnvironment.getApplication() as Context
        LuluRepositories.initialize(context)
        SharedExperienceTimeline.initialize(context)
        LuluAiServices.initialize(context)
        CharacterInnerLifeStore.javaClass.getDeclaredField("prefs").apply { isAccessible = true }
            .set(CharacterInnerLifeStore, null)
        CharacterInnerLifeStore.initialize(context)
        ProactivePerceptionPolicyStore.initialize(context)
        val role = "motive-cue-gateway"
        ProactivePerceptionPolicyStore.update(role) { it.copy(enabled = false) }
        val now = Instant.now()
        repeat(20) { index ->
            CharacterInnerLifeStore.observe(role, "cue-origin-$index", "一个真实的私人打算",
                JSONObject().put("motives", org.json.JSONArray().put(JSONObject()
                    .put("op", "start").put("aim", "收集第${index}种石头")
                    .put("why", "独立兴趣${index}"))), now = now)
        }
        val motives = CharacterInnerLifeStore.snapshot(role).getJSONArray("motives")
        assertEquals(20, motives.length())
        val selected = CharacterMotiveState.workingSet(motives, now).map { it.getString("id") }
        val hidden = CharacterMotiveState.active(motives).first { it.getString("id") !in selected }
        CharacterInnerLifeStore.observe(role, "cue-plan-revision", "为已有打算补上等待条件",
            JSONObject().put("motives", org.json.JSONArray().put(JSONObject()
                .put("op", "revise").put("id", hidden.getString("id"))
                .put("reason", "等待合适的机会").put("waitingFor", "海边灯塔重新亮起"))), now = now)
        val before = CharacterInnerLifeStore.snapshot(role).toString()
        val server = MockWebServer()
        repeat(2) { server.enqueue(MockResponse().setHeader("Content-Type", "application/json")
            .setBody("""{"choices":[{"message":{"content":"知道了"},"finish_reason":"stop"}]}""")) }
        server.start()
        try {
            val conn = ModelConnection(server.url("/").toString(), "test-only", "test")
            // Ordinary chat uses compact inner life; world discussion uses full.
            for (facts in listOf("海边灯塔重新亮起了", "数字世界里的海边灯塔重新亮起了")) {
                LuluAiServices.gateway.generate(role, facts, "自然回应", "私聊", "test",
                    connectionOverride = conn, contextMode = CompanionContextMode.Full,
                    memoryRequest = UnifiedMemoryRequest(currentInput = facts)).getOrThrow()
                val body = JSONObject(server.takeRequest(5, TimeUnit.SECONDS)!!.body.readUtf8())
                val prompt = body.getJSONArray("messages").getJSONObject(0).getString("content")
                assertTrue(prompt.contains("正在等待：海边灯塔重新亮起"))
                assertTrue(prompt.contains(hidden.getString("id")))
                assertEquals(facts.contains("数字世界"), prompt.contains("【角色持续内在生活"))
            }
            assertEquals(2, server.requestCount)
            assertEquals(before, CharacterInnerLifeStore.snapshot(role).toString())
        } finally { server.shutdown() }
    }

    @Test fun fullGatewayDeliversRecallDiscretionThroughTheActualWorkingSet() = runBlocking {
        val context = RuntimeEnvironment.getApplication() as Context
        LuluRepositories.initialize(context)
        SharedExperienceTimeline.initialize(context)
        LuluAiServices.initialize(context)
        val role = "recall-discretion-gateway"
        val at = Instant.now().minusSeconds(3_600)
        LuluRepositories.memory.save(com.jiacimu.lulu.core.MemoryEntry(
            "earlier-book", role, "那本书的旧印象是桥西的橘猫", com.jiacimu.lulu.core.MemoryKind.Fact,
            "手动", at, at, 7, false, true,
        ))
        val server = MockWebServer()
        server.enqueue(MockResponse().setHeader("Content-Type", "application/json")
            .setBody("""{"choices":[{"message":{"content":"知道了"},"finish_reason":"stop"}]}"""))
        server.start()
        try {
            LuluAiServices.gateway.generate(role, "想聊聊那本书和桥西的橘猫", "自然回应", "私聊", "test",
                connectionOverride = ModelConnection(server.url("/").toString(), "test-only", "test"),
                contextMode = CompanionContextMode.Full).getOrThrow()
            val body = JSONObject(server.takeRequest(5, TimeUnit.SECONDS)!!.body.readUtf8())
            val prompt = body.getJSONArray("messages").getJSONObject(0).getString("content")
            assertTrue(prompt.contains("那本书的旧印象是桥西的橘猫"))
            assertTrue(prompt.contains("【回忆的使用分寸】"))
            assertTrue(prompt.contains("只影响自己的理解"))
            assertTrue(prompt.contains("这次不提"))
            assertEquals(1, server.requestCount)
        } finally { server.shutdown() }
    }

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
