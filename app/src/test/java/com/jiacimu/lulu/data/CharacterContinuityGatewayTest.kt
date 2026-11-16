package com.jiacimu.lulu.data

import android.content.Context
import com.jiacimu.lulu.LuluRepositories
import com.jiacimu.lulu.ai.CompanionContextMode
import com.jiacimu.lulu.ai.LuluAiServices
import com.jiacimu.lulu.ai.ModelConnection
import com.jiacimu.lulu.ai.ModelConversationMessage
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
            // Owner chat and self-directed life both preserve the same full
            // private psychology; only the world inventory is scene-dependent.
            for (facts in listOf("海边灯塔重新亮起了", "数字世界里的海边灯塔重新亮起了")) {
                LuluAiServices.gateway.generate(role, facts, "自然回应", "私聊", "test",
                    connectionOverride = conn, contextMode = CompanionContextMode.Full,
                    memoryRequest = UnifiedMemoryRequest(currentInput = facts)).getOrThrow()
                val body = JSONObject(server.takeRequest(5, TimeUnit.SECONDS)!!.body.readUtf8())
                val prompt = body.getJSONArray("messages").getJSONObject(0).getString("content")
                assertTrue(prompt.contains("正在等待：海边灯塔重新亮起"))
                assertTrue(prompt.contains(hidden.getString("id")))
                assertTrue("The character must not forget its inner life while chatting",
                    prompt.contains("【角色持续内在生活"))
            }
            assertEquals(2, server.requestCount)
            assertEquals(before, CharacterInnerLifeStore.snapshot(role).toString())
        } finally { server.shutdown() }
    }

    @Test fun livePrivateMessageAndIndependentLifeUseComparableFullOwnedMemory() = runBlocking {
        val context = RuntimeEnvironment.getApplication() as Context
        LuluRepositories.initialize(context)
        SharedExperienceTimeline.initialize(context)
        LuluAiServices.initialize(context)
        CharacterInnerLifeStore.javaClass.getDeclaredField("prefs").apply { isAccessible = true }
            .set(CharacterInnerLifeStore, null)
        CharacterInnerLifeStore.initialize(context)
        val role = MigratedDomainStores.characters.create("跨入口心事保持").characterId
        val anchor = "海风铃诗集" + System.nanoTime().toString().takeLast(5)
        val eventId = "full-life-" + java.util.UUID.randomUUID()
        // A newly created digital person must not be given a fake memory
        // from before its recorded creation time.
        val at = Instant.now()
        SharedExperienceTimeline.record(
            eventId = eventId, characterId = role, channel = "普通私聊",
            speaker = "用户", content = "我和你真正聊过$anchor，还一起选了下一本要看的书",
            occurredAt = at, triggerExtraction = false,
            evidenceKind = EventEvidenceKind.UserStatement, source = "message",
        )
        CharacterInnerLifeStore.observe(role, eventId, "我们聊过$anchor，想接着阅读",
            JSONObject().put("motives", org.json.JSONArray().put(JSONObject()
                .put("op", "start").put("aim", "想把$anchor读完")
                .put("why", "自己真的对书里的故事感兴趣"))), now = at)
        val server = MockWebServer()
        repeat(2) {
            server.enqueue(MockResponse().setHeader("Content-Type", "application/json")
                .setBody("""{"choices":[{"message":{"content":"{\"action\":\"silent\"}"},"finish_reason":"stop"}]}"""))
        }
        server.start()
        try {
            val connection = ModelConnection(server.url("/").toString(), "test-only", "test")
            val requested = UnifiedMemoryRequest(
                currentInput = anchor,
                sceneContext = "同一个人物的生活正在继续",
                taskIntent = "延续真实阅读兴趣，自己选择此刻做什么",
            )
            val prompts = mutableListOf<String>()
            for (withOwner in listOf(false, true)) {
                LuluAiServices.gateway.generate(
                    characterId = role, facts = "此刻有一份真实的本人生活时间线",
                    instruction = "按真实关系与本人心愿选择是否说话或行动",
                    source = "后台主动感知", title = if (withOwner) "新消息" else "生活回顾",
                    contextMode = CompanionContextMode.Full, connectionOverride = connection,
                    memoryRequest = requested, maxTokens = 350,
                    conversation = if (withOwner) listOf(
                        ModelConversationMessage(ModelConversationMessage.Role.USER,
                            "我们刚才聊的那本书，你还想接着看吗？")
                    ) else emptyList(),
                ).getOrThrow()
                val wire = JSONObject(server.takeRequest(5, TimeUnit.SECONDS)!!.body.readUtf8())
                val system = wire.getJSONArray("messages").getJSONObject(0).getString("content")
                prompts += system
                assertTrue("Both entrypoints must recall their OWN lived event", system.contains(anchor))
                assertTrue("Both entrypoints must retain ongoing personal desire",
                    system.contains("想把$anchor读完"))
                assertTrue("One person should retain the full rich inner life in every context",
                    system.contains("【角色持续内在生活"))
            }
            // This is the SAME gateway with an equivalent bounded scene;
            // full production turns can still add legitimately different tools
            // and concrete context, so this checks the common foundation only.
            val difference = kotlin.math.abs(prompts[0].length - prompts[1].length)
            assertTrue("A new user message must not remove half of the actor's memory",
                difference <= 5_000)
            assertEquals("Neither path may buy a second model call just to recall", 2, server.requestCount)
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
