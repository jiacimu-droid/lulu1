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
import java.util.UUID
import java.util.concurrent.TimeUnit

/** Actual gateway requests: one person, one life story, different scene adapters. */
@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE, sdk = [28])
class CharacterOwnedSceneGatewayTest {
    @Before fun before() { MemoryTestEnvironment.release() }
    @After fun after() { MemoryTestEnvironment.release() }

    @Test fun genuineSceneDecisionsCarryOneCharactersPriorPhoneAndWorldEvidence() = runBlocking {
        val context = RuntimeEnvironment.getApplication() as Context
        LuluRepositories.initialize(context)
        SharedExperienceTimeline.initialize(context)
        LuluAiServices.initialize(context)
        val role = MigratedDomainStores.characters.create("跨场景人物", "独立且有主见").characterId
        val anchor = "电话后亲手记下的银色星图" + UUID.randomUUID().toString().take(8)
        SharedExperienceTimeline.record(
            eventId = "scene-voice-${UUID.randomUUID()}", characterId = role,
            channel = "电话互动", speaker = "角色",
            content = "我在电话里确实与主人讨论过$anchor。",
            occurredAt = Instant.now().minusSeconds(120), triggerExtraction = false,
            source = "message", evidenceKind = EventEvidenceKind.CharacterStatement)
        val other = MigratedDomainStores.characters.create("旁观角色", "独立的旁观者").characterId
        val secret = "另一位角色的私有密码形容词" + UUID.randomUUID().toString().take(8)
        SharedExperienceTimeline.record(
            eventId = "scene-other-${UUID.randomUUID()}", characterId = other,
            channel = "私聊", speaker = "角色", content = secret,
            triggerExtraction = false, source = "message",
            evidenceKind = EventEvidenceKind.CharacterStatement)
        val sourceScenes = listOf(
            "聊天工具规划", "电话回复", "朋友圈独立感知",
            "数字世界见面", "游戏 · 方块小岛自主同行",
            "游戏 · 迷雾摸金自主同行",
        )
        val server = MockWebServer()
        repeat(sourceScenes.size) {
            server.enqueue(MockResponse().setHeader("Content-Type", "application/json")
                .setBody("""{"choices":[{"message":{"content":"{\"action\":\"silent\"}"},"finish_reason":"stop"}]}"""))
        }
        server.start()
        try {
            val conn = ModelConnection(server.url("/").toString(), "test-only", "test")
            sourceScenes.forEach { source ->
                LuluAiServices.gateway.generate(
                    characterId = role, facts = "当前场景=$source；本人想起$anchor",
                    instruction = "按本人真实经历选择是否行动，不得借用别人的私人记录",
                    source = source, title = "跨场景连续性回归",
                    connectionOverride = conn, contextMode = CompanionContextMode.Full,
                    memoryRequest = UnifiedMemoryRequest(
                        currentInput = anchor, sceneContext = source,
                        taskIntent = "回想真实经历并选择下一步",
                    ),
                ).getOrThrow()
                val body = JSONObject(server.takeRequest(5, TimeUnit.SECONDS)!!.body.readUtf8())
                val prompt = body.getJSONArray("messages").getJSONObject(0).getString("content")
                assertTrue("$source should recall its own phone event", prompt.contains(anchor))
                assertFalse("$source must never see another person's private life", prompt.contains(secret))
                assertTrue(prompt.contains("统一角色决策协议"))
            }
            assertEquals(sourceScenes.size, server.requestCount)
        } finally { server.shutdown() }
    }

    @Test fun residentFullDecisionRecallsOwnEventsInsteadOfFallingBackToPersonaOnly() = runBlocking {
        val context = RuntimeEnvironment.getApplication() as Context
        LuluRepositories.initialize(context)
        SharedExperienceTimeline.initialize(context)
        LuluAiServices.initialize(context)
        val id = "resident:gateway-${UUID.randomUUID()}"
        MigratedDomainStores.characters.update(CharacterSettings(
            characterId = id, displayName = "原住民本人", persona = "喜欢植物，不爱热闹"))
        DigitalLifeProfileStore.registerNewLife(id, "原住民本人", "数字世界原住民")
        assertTrue(ResidentDailyPolicy.setActivityEnabled(context, true, id))
        val anchor = "自己在院子里见过的黄铜风铃" + UUID.randomUUID().toString().take(8)
        SharedExperienceTimeline.record(
            eventId = "native-seen-${UUID.randomUUID()}", characterId = id,
            channel = "数字世界见面", speaker = "居民本人",
            content = "我确实在庭院里看到了$anchor。",
            triggerExtraction = false, source = "meeting",
            evidenceKind = EventEvidenceKind.CharacterStatement)
        val server = MockWebServer()
        server.enqueue(MockResponse().setHeader("Content-Type", "application/json")
            .setBody("""{"choices":[{"message":{"content":"{\"choice\":\"wait\"}"},"finish_reason":"stop"}]}"""))
        server.start()
        try {
            LuluAiServices.gateway.generate(
                characterId = id, facts = "我今天又想起$anchor。",
                instruction = "按已有经历选择自己的行动",
                source = "原住民个人日程到点", title = "真实居民自主决定",
                connectionOverride = ModelConnection(server.url("/").toString(), "test-only", "test"),
                contextMode = CompanionContextMode.Full,
                memoryRequest = UnifiedMemoryRequest(
                    currentInput = anchor, sceneContext = "本人数字世界的日程",
                ),
            ).getOrThrow()
            val body = JSONObject(server.takeRequest(5, TimeUnit.SECONDS)!!.body.readUtf8())
            val prompt = body.getJSONArray("messages").getJSONObject(0).getString("content")
            assertTrue("The lived fact must be in the actor-owned full context", prompt.contains(anchor))
            assertTrue(prompt.contains("喜欢植物，不爱热闹"))
            assertTrue(prompt.contains("统一角色决策协议"))
            assertEquals(1, server.requestCount)
        } finally { server.shutdown() }
    }
}
