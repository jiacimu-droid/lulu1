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

    @Test fun ensembleDoesNotLeakFirstSpeakersPrivateVoiceOrMemoryToEveryMember() = runBlocking {
        val context = RuntimeEnvironment.getApplication() as Context
        LuluRepositories.initialize(context)
        SharedExperienceTimeline.initialize(context)
        CharacterLifeStore.initialize(context)
        LuluAiServices.initialize(context)
        val leader = "group-ensemble-leading-role"
        val uniqueHabit = "甲在私聊独有的魔法语气密码不该灌进群里所有人"
        val uniqueMemory = "甲只在私聊知道的秘密绝不能成为群里其他人的公共记忆"
        CharacterLifeStore.setProfile(leader, "speechHabits", uniqueHabit)
        LuluRepositories.memory.upsert(MemoryEntry(
            id = "ensemble-private-core", characterId = leader,
            content = uniqueMemory, kind = MemoryKind.Fact, tier = MemoryTier.Core,
            source = "manual", occurredAt = null, createdAt = java.time.Instant.now(),
            strength = 10, pinned = false, canRecallProactively = true,
        ))
        val server = MockWebServer()
        server.enqueue(MockResponse().setHeader("Content-Type", "application/json")
            .setBody("""{"choices":[{"message":{"content":"{\"turns\":[]}"},"finish_reason":"stop"}]}"""))
        server.start()
        try {
            val connection = ModelConnection(server.url("/").toString(), "test-only", "test")
            LuluAiServices.gateway.generate(
                leader, "群聊所有成员的独立资料由调用方逐角色提供",
                "请按成员各自ID组织发言",
                "群聊·全员自然讨论", "ensemble test",
                connectionOverride = connection,
                contextMode = CompanionContextMode.Ensemble,
            ).getOrThrow()
            val request = JSONObject(server.takeRequest(5, TimeUnit.SECONDS)!!.body.readUtf8())
            val prompt = request.getJSONArray("messages").getJSONObject(0).getString("content")
            assertTrue(prompt.contains("多角色共同互动"))
            assertTrue(prompt.contains("【以本人为表达的起点】"))
            assertFalse(prompt.contains(uniqueHabit))
            assertFalse(prompt.contains(uniqueMemory))
        } finally {
            server.shutdown()
        }
    }


    @Test fun currentAuthoredRelationshipReachesRealWorldModesWithoutKeywordRoleOverlay() = runBlocking {
        val context = RuntimeEnvironment.getApplication() as Context
        LuluRepositories.initialize(context)
        SharedExperienceTimeline.initialize(context)
        LuluAiServices.initialize(context)
        val actor = MigratedDomainStores.characters.create("关系入口测试", "独立、有自己的判断")
        val role = actor.characterId
        val details = "按双方实际约定相处。".repeat(100) + "末尾边界：监督只在双方另有明确约定时成立。"
        assertTrue(CharacterLifeStore.setRelationshipAgreement(role, "user", "闺蜜兼恋人", details))
        val server = MockWebServer()
        repeat(6) { server.enqueue(MockResponse().setHeader("Content-Type", "application/json")
            .setBody("""{"choices":[{"message":{"content":"好"},"finish_reason":"stop"}]}""")) }
        server.start()
        try {
            val connection = ModelConnection(server.url("/").toString(), "test-only", "test")
            val calls = listOf(
                CompanionContextMode.Full to "私聊",
                CompanionContextMode.Full to "聊天表达渲染",
                CompanionContextMode.CharacterAndScenario to "电话",
                CompanionContextMode.PersonaAndScenario to "独立性格场景",
                CompanionContextMode.Isolated to "独立工具任务",
            )
            calls.forEach { (mode, source) ->
                LuluAiServices.gateway.generate(role, "本次只自然接话", "按当前场景回应", source,
                    "test", connectionOverride = connection, contextMode = mode).getOrThrow()
                val prompt = JSONObject(server.takeRequest(5, TimeUnit.SECONDS)!!.body.readUtf8())
                    .getJSONArray("messages").getJSONObject(0).getString("content")
                val includesRelationship = mode == CompanionContextMode.Full ||
                    mode == CompanionContextMode.CharacterAndScenario
                assertEquals(includesRelationship, prompt.contains(details))
                assertEquals(includesRelationship, prompt.contains("闺蜜兼恋人"))
                assertFalse(prompt.contains("你和对方是平等朋友，不是她的助手、仆人、恋人"))
                assertFalse(prompt.contains("不要未经明确邀请就主动将普通话题转成"))
                if (includesRelationship) assertEquals(1, prompt.split(details).size - 1)
            }
            assertTrue(CharacterLifeStore.setRelationshipAgreement(role, "user",
                "普通朋友", "新约定：此前恋爱关系已经结束，保留各自生活。"))
            LuluAiServices.gateway.generate(role, "新的谈话", "按当前场景回应", "私聊",
                "test", connectionOverride = connection, contextMode = CompanionContextMode.Full).getOrThrow()
            val changed = JSONObject(server.takeRequest(5, TimeUnit.SECONDS)!!.body.readUtf8())
                .getJSONArray("messages").getJSONObject(0).getString("content")
            assertTrue(changed.contains("新约定：此前恋爱关系已经结束，保留各自生活。"))
            assertFalse(changed.contains(details))
            assertFalse(changed.contains("闺蜜兼恋人"))
            assertEquals(6, server.requestCount)
            assertEquals("普通朋友", CharacterSocialRelationship.agreement(role, "user")?.title)
        } finally { server.shutdown() }
    }

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
