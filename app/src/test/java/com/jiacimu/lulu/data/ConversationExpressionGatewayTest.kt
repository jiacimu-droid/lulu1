package com.jiacimu.lulu.data

import android.content.Context
import com.jiacimu.lulu.LuluRepositories
import com.jiacimu.lulu.ai.LuluAiServices
import com.jiacimu.lulu.ai.ModelConnection
import com.jiacimu.lulu.ai.ModelConversationMessage
import com.jiacimu.lulu.system.LuluDeviceToolBridge
import kotlinx.coroutines.runBlocking
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.json.JSONObject
import org.json.JSONArray
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import java.util.concurrent.TimeUnit

@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE, sdk = [28])
class ConversationExpressionGatewayTest {
    @Before fun before() { MemoryTestEnvironment.release() }
    @After fun after() { MemoryTestEnvironment.release() }

    @Test fun renderAndRepairKeepTheSameVoiceWithoutReceivingASecondPlannerContract() = runBlocking {
        val context = RuntimeEnvironment.getApplication() as Context
        LuluRepositories.initialize(context)
        SharedExperienceTimeline.initialize(context)
        LuluAiServices.initialize(context)
        val role = MigratedDomainStores.characters.create("表达通道回归角色", "说话直接，有冷幽默")
        CharacterLifeStore.setProfile(role.characterId, "speechHabits", "熟悉时说话干脆，会接冷笑话")
        LuluRepositories.worldBook.save(com.jiacimu.lulu.core.WorldBookEntry(
            "stable-global-rule", "共同规则", "全局表达约束甲", true, emptyMap()))
        LuluRepositories.worldBook.save(com.jiacimu.lulu.core.WorldBookEntry(
            "stable-role-rule", "本人规则", "仅本人表达约束乙", false, mapOf(role.characterId to true)))
        LuluRepositories.worldBook.save(com.jiacimu.lulu.core.WorldBookEntry(
            "other-role-rule", "别人的规则", "别人的私有规则丙", false, mapOf("another-role" to true)))
        val sources = listOf("聊天表达渲染", "聊天表达自然度修复", "工具结果·聊天自然表达修复", "普通角色决定")
        val server = MockWebServer()
        repeat(sources.size) { server.enqueue(MockResponse().setHeader("Content-Type", "application/json")
            .setBody("""{"choices":[{"message":{"content":"我在呢。"},"finish_reason":"stop"}]}""")) }
        server.start()
        try {
            val connection = ModelConnection(server.url("/").toString(), "test-only", "test")
            sources.forEach { source ->
                LuluAiServices.gateway.generate(role.characterId, "已决定接住这一句招呼", "输出本人的自然表达",
                    source, "test", connectionOverride = connection,
                    memoryRequest = UnifiedMemoryRequest(currentInput = "小宝宝", sceneContext = "私聊"))
                    .getOrThrow()
                val request = JSONObject(server.takeRequest(5, TimeUnit.SECONDS)!!.body.readUtf8())
                val system = request.getJSONArray("messages").getJSONObject(0).getString("content")
                assertTrue(system.contains("全局表达约束甲"))
                assertTrue(system.contains("仅本人表达约束乙"))
                assertFalse(system.contains("别人的私有规则丙"))
                if (CharacterExpressionGuide.isExpressionOnly(source)) {
                    assertTrue(system.contains("【同一人物的表达层】"))
                    assertTrue(system.contains("旁支理由和联想供你取舍"))
                    assertTrue(system.contains("熟悉时说话干脆，会接冷笑话"))
                    assertFalse(system.contains("【统一角色决策协议】"))
                    assertFalse(system.contains("【人物的基本原则"))
                } else {
                    assertTrue(system.contains("【统一角色决策协议】"))
                    assertFalse(system.contains("【同一人物的表达层】"))
                }
                assertTrue(system.contains("【含蓄与留白】"))
                assertTrue(system.contains("哄睡连续故事按实际需要充分表达"))
            }
            assertEquals(sources.size, server.requestCount)
        } finally { server.shutdown() }
    }

    @Test(timeout = 60_000) fun privateChatDeliversTheRewrittenTurnInsteadOfTheSixBubbleComfortDraft() = runBlocking {
        val context = RuntimeEnvironment.getApplication() as Context
        LuluRepositories.initialize(context)
        SharedExperienceTimeline.initialize(context)
        LuluAiServices.initialize(context)
        LuluDeviceToolBridge.initialize(context)
        com.jiacimu.lulu.study.PostgraduateExamStores.initialize(context)
        com.jiacimu.lulu.study.StarWishStores.initialize(context)
        val role = MigratedDomainStores.characters.create("整轮表达回归角色", "说话直接，有自己的主见")
        val rewritten = "你怕的也是以后的日子，对吧？"
        val chat = MigratedDomainStores.chat.ensureConversation(role.characterId, "test")
        MigratedDomainStores.chat.sendUserMessage(chat.id, "还得接着读。")
        MigratedDomainStores.chat.appendCharacterMessage(chat.id, "你在担心什么？", role.characterId)
        val current = MigratedDomainStores.chat.sendUserMessage(chat.id, "不读书就没钱，")
        val server = MockWebServer()
        fun response(content: String) = MockResponse().setHeader("Content-Type", "application/json")
            .setBody(JSONObject().put("choices", JSONArray().put(JSONObject().put("message",
                JSONObject().put("content", content)).put("finish_reason", "stop"))).toString())
        fun bubbles(texts: List<String>) = JSONObject().put("action", "reply").put("bubbles",
            JSONArray().apply { texts.forEach { put(JSONObject().put("text", it)) } }).toString()
        server.enqueue(response("""{"action":"reply","speechIntent":"确认她在担心以后的生计，不把她的担忧说成无理恐慌","dialogueMove":{"type":"reassure","contentIntent":"认真回应现实的担忧"}}"""))
        server.enqueue(response(bubbles(ConversationNaturalnessFixtures.screenshotComfort)))
        server.enqueue(response(bubbles(listOf(rewritten))))
        server.start()
        try {
            val config = LuluAiServices.connectionStore.saveConfiguration(null, "test",
                server.url("/").toString(), "test-only")
            LuluAiServices.connectionStore.addArchive(config.id, "test")
            val reply = LuluDeviceToolBridge.respond(role.characterId, "历史应通过真实消息发送", "不读书就没钱，", "test",
                userEvidenceIds = listOf(current.id))
                .getOrThrow()
            assertEquals(rewritten, reply.text)
            assertEquals(3, server.requestCount) // Planner, expression and the existing single repair.
            val requests = (1..3).map { JSONObject(server.takeRequest(5, TimeUnit.SECONDS)!!.body.readUtf8()) }
            val planner = requests[0].getJSONArray("messages").toString()
            val expression = requests[1].getJSONArray("messages").toString()
            val repair = requests[2].getJSONArray("messages").toString()
            assertTrue(planner.contains("所有气泡合计"))
            assertTrue(planner.contains("她在说现实困难时，担忧本身可以成立"))
            assertTrue(expression.contains("所有气泡合计"))
            assertTrue(repair.contains("安慰流程"))
            assertTrue(repair.contains("不负责一次把所有情绪劝好"))
            (1..2).forEach { index ->
                val messages = requests[index].getJSONArray("messages")
                assertEquals(4, messages.length())
                assertEquals("user", messages.getJSONObject(1).getString("role"))
                assertEquals("还得接着读。", messages.getJSONObject(1).getString("content"))
                assertEquals("assistant", messages.getJSONObject(2).getString("role"))
                assertEquals("你在担心什么？", messages.getJSONObject(2).getString("content"))
                assertEquals("不读书就没钱，", messages.getJSONObject(3).getString("content"))
                assertFalse(messages.getJSONObject(0).getString("content").contains("历史应通过真实消息发送"))
            }
        } finally { server.shutdown() }
    }

    @Test fun expressionConversationKeepsRolesAndCurrentInputForBothApiFormats() = runBlocking {
        val context = RuntimeEnvironment.getApplication() as Context
        LuluRepositories.initialize(context)
        SharedExperienceTimeline.initialize(context)
        LuluAiServices.initialize(context)
        val role = MigratedDomainStores.characters.create("消息协议回归角色", "有自己的判断")
        val server = MockWebServer()
        server.enqueue(MockResponse().setHeader("Content-Type", "application/json")
            .setBody("""{"choices":[{"message":{"content":"嗯。"},"finish_reason":"stop"}]}"""))
        server.enqueue(MockResponse().setHeader("Content-Type", "application/json")
            .setBody("""{"content":[{"type":"text","text":"嗯。"}],"stop_reason":"end_turn"}"""))
        server.start()
        try {
            val conversation = listOf(
                ModelConversationMessage(ModelConversationMessage.Role.USER, "旧的用户原话"),
                ModelConversationMessage(ModelConversationMessage.Role.ASSISTANT, "旧的角色原话"),
                ModelConversationMessage(ModelConversationMessage.Role.USER, "这一句才是现在说的"),
            )
            listOf(false, true).forEach { claude ->
                LuluAiServices.gateway.generate(role.characterId, "真实执行结果：搜索失败，尚未读到原文",
                    "以本人身份接话，保留真实结果", "聊天表达渲染", "test",
                    connectionOverride = ModelConnection(server.url(if (claude) "/claude/messages" else "/").toString(),
                        "test-only", if (claude) "claude-test" else "test"),
                    memoryRequest = UnifiedMemoryRequest(currentInput = conversation.last().content, sceneContext = "私聊"),
                    conversation = conversation).getOrThrow()
                val body = JSONObject(server.takeRequest(5, TimeUnit.SECONDS)!!.body.readUtf8())
                val messages = body.getJSONArray("messages")
                val offset = if (claude) 0 else 1
                assertEquals(3 + offset, messages.length())
                conversation.forEachIndexed { index, turn ->
                    assertEquals(turn.role.wire, messages.getJSONObject(index + offset).getString("role"))
                    assertEquals(turn.content, messages.getJSONObject(index + offset).getString("content"))
                }
                val system = if (claude) body.getString("system") else messages.getJSONObject(0).getString("content")
                assertTrue(system.contains("搜索失败，尚未读到原文"))
                assertTrue(system.contains("本轮背景材料"))
                assertTrue(system.contains("旧长篇的篇幅与措辞不构成本轮的文风要求"))
            }
            assertEquals(2, server.requestCount)
        } finally { server.shutdown() }
    }

    @Test(timeout = 60_000) fun normalOnlineReplyUsesTheActualConversationInOneDecisionAndReallyDelivers() = runBlocking {
        val context = RuntimeEnvironment.getApplication() as Context
        listOf("prefs", "appContext").forEach { name ->
            CompanionOnlineStore.javaClass.getDeclaredField(name).apply { isAccessible = true }
                .set(CompanionOnlineStore, null)
        }
        LuluRepositories.initialize(context)
        SharedExperienceTimeline.initialize(context)
        MigratedDomainStores.initialize(context)
        CharacterIdentityStore.initialize(context)
        DigitalLifeProfileStore.initialize(context)
        CharacterLifeStore.initialize(context)
        CharacterDevelopmentStore.initialize(context)
        CompanionOnlineStore.initialize(context)
        LuluAiServices.initialize(context)
        com.jiacimu.lulu.study.PostgraduateExamStores.initialize(context)
        com.jiacimu.lulu.study.StarWishStores.initialize(context)
        val role = MigratedDomainStores.characters.create("日常在线回复入口回归", "有自己的判断，认真听人说话")
        val chat = MigratedDomainStores.chat.ensureConversation(role.characterId, "test")
        MigratedDomainStores.chat.sendUserMessage(chat.id, "还得接着读。")
        MigratedDomainStores.chat.appendCharacterMessage(chat.id, "你在担心什么？", role.characterId)
        CompanionOnlineStore.markSeen(role.characterId, CompanionOnlineStore.unreadChatSnapshot(role.characterId))
        val current = MigratedDomainStores.chat.sendUserMessage(chat.id, "不读书就没钱，")
        OnlineChatBatchStore.cancel(context, role.characterId)
        val batch = OnlineChatBatchStore.onUserBubble(context, role.characterId,
            atMillis = System.currentTimeMillis() - OnlineChatBatchStore.QUIET_MILLIS - 1_000L)
        val spoken = "你是在担心以后的生活，对吗？"
        val answer = JSONObject().put("choices", JSONArray().put(JSONObject().put("message",
            JSONObject().put("content", JSONObject().put("action", "message").put("text", spoken)
                .put("reason", "想确认她现在真正担心的事").toString())).put("finish_reason", "stop"))).toString()
        val server = MockWebServer()
        server.dispatcher = object : okhttp3.mockwebserver.Dispatcher() {
            override fun dispatch(request: okhttp3.mockwebserver.RecordedRequest) = MockResponse()
                .setHeader("Content-Type", "application/json").setBody(answer)
        }
        server.start()
        try {
            val config = LuluAiServices.connectionStore.saveConfiguration(null, "test", server.url("/").toString(), "test-only")
            LuluAiServices.connectionStore.addArchive(config.id, "test")
            assertEquals(1, ProactivePerceptionRuntime.runDueCycle(context, "私聊新消息", role.characterId,
                force = true, onlineRevision = batch.revision, requiresUnread = true))
            assertEquals(1, server.requestCount)
            val body = JSONObject(server.takeRequest(5, TimeUnit.SECONDS)!!.body.readUtf8())
            val messages = body.getJSONArray("messages")
            assertEquals(4, messages.length())
            assertEquals("还得接着读。", messages.getJSONObject(1).getString("content"))
            assertEquals("assistant", messages.getJSONObject(2).getString("role"))
            assertEquals("你在担心什么？", messages.getJSONObject(2).getString("content"))
            assertEquals("user", messages.getJSONObject(3).getString("role"))
            assertEquals(current.content, messages.getJSONObject(3).getString("content"))
            val system = messages.getJSONObject(0).getString("content")
            assertTrue(system.contains("此刻是在和对方继续一段私聊"))
            assertTrue(system.contains(JSONObject().put("messageId", current.id)
                .put("text", current.content).toString()))
            assertTrue(system.contains("担忧本身可以成立"))
            assertTrue(system.contains("无需总比她懂得更多"))
            assertTrue(MigratedDomainStores.chat.messages(chat.id).value.any {
                it.sender == LuluChatMessage.Sender.Character && it.content == spoken
            })
            assertFalse(current.id in CompanionOnlineStore.unreadChatSnapshot(role.characterId).messageIds)
        } finally {
            OnlineChatBatchStore.cancel(context, role.characterId)
            server.shutdown()
        }
    }
}
