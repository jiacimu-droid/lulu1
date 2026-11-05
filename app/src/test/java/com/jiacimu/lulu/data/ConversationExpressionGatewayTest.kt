package com.jiacimu.lulu.data

import android.content.Context
import com.jiacimu.lulu.LuluRepositories
import com.jiacimu.lulu.ai.LuluAiServices
import com.jiacimu.lulu.ai.ModelConnection
import com.jiacimu.lulu.ai.CompanionContextMode
import com.jiacimu.lulu.core.WorldBookEntry
import com.jiacimu.lulu.core.WorldBookPromptPosition
import com.jiacimu.lulu.ai.ModelConversationMessage
import com.jiacimu.lulu.system.LuluDeviceToolBridge
import kotlinx.coroutines.flow.first
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
    @Test(timeout = 60_000) fun memoryAuditRecordsOnlySuccessfulActualGatewayInputUnderSameTurnTrace() = runBlocking {
        val context = RuntimeEnvironment.getApplication() as Context
        LuluRepositories.initialize(context)
        SharedExperienceTimeline.initialize(context)
        LuluAiServices.initialize(context)
        val role = MigratedDomainStores.characters.create("真实记忆审计测试", "有自己的判断")
        val server = MockWebServer()
        server.enqueue(MockResponse().setResponseCode(503).setBody("server busy"))
        server.enqueue(MockResponse().setHeader("Content-Type", "application/json")
            .setBody("""{"choices":[{"message":{"content":"我听见了。"},"finish_reason":"stop"}]}"""))
        server.start()
        try {
            LuluRepositories.performance.clearTimings()
            val connection = ModelConnection(server.url("/").toString(), "test-only", "test")
            val trace = "test-p08b-actual-injection"
            val req = UnifiedMemoryRequest(currentInput = "这句话只应在模型原始请求里",
                sceneContext = "私聊")
            val failed = LuluAiServices.gateway.generate(role.characterId,
                "真实背景", "按本人设定接话", "聊天工具规划", "test",
                connectionOverride = connection, memoryRequest = req,
                turnTraceId = trace)
            assertTrue(failed.isFailure)
            assertFalse(MemoryInspectionStore.snapshot(role.characterId)?.promptRecorded == true)
            val success = LuluAiServices.gateway.generate(role.characterId,
                "真实背景", "按本人设定接话", "聊天工具规划", "test",
                connectionOverride = connection, memoryRequest = req,
                turnTraceId = trace)
            assertTrue(success.exceptionOrNull()?.message.orEmpty(), success.isSuccess)
            assertTrue(MemoryInspectionStore.snapshot(role.characterId)?.promptRecorded == true)
            val actual = LuluRepositories.performance.timingRecords.first()
                .filter { it.stage == "P08b实际注入" && it.detail.contains("trace=$trace") }
            assertEquals(1, actual.size)
            assertTrue(actual.single().detail.contains("selected="))
            assertTrue(actual.single().detail.contains("injected="))
            assertFalse(actual.single().detail.contains("这句话只应在模型原始请求里"))
            assertEquals(2, server.requestCount)
        } finally { server.shutdown() }
    }

    @Test fun realLeanRendererReceivesOnlyThatCharactersActualListenerPreference() = runBlocking {
        val context = RuntimeEnvironment.getApplication() as Context
        LuluRepositories.initialize(context)
        SharedExperienceTimeline.initialize(context)
        LuluAiServices.initialize(context)
        CharacterDevelopmentStore.initialize(context)
        val known = MigratedDomainStores.characters.create("认识主人阅读偏好的角色", "有自己的语言节奏")
        val other = MigratedDomainStores.characters.create("尚不了解该习惯的角色", "保持个人表达")
        val proof = "reader-pace-" + java.util.UUID.randomUUID()
        SharedExperienceTimeline.record(proof, known.characterId, "私聊", "用户",
            "我更喜欢短一点的聊天气泡，看长气泡眼睛不舒服",
            triggerExtraction = false, source = "message",
            evidenceKind = EventEvidenceKind.UserStatement)
        assertTrue(CharacterDevelopmentStore.applyProposal(
            known.characterId, "user:reader-pacing", DevelopmentKind.UserPreference,
            "她亲口说手机上长气泡不舒服，喜欢读起来轻松的短消息",
            listOf(proof), emptyList(),
            CharacterRuntime.personaConstraintSnapshot(known.characterId)))
        val server = MockWebServer()
        repeat(2) { server.enqueue(MockResponse().setHeader("Content-Type", "application/json")
            .setBody("""{"choices":[{"message":{"content":"{\"action\":\"reply\",\"bubbles\":[{\"text\":\"嗯。\"}]}"},"finish_reason":"stop"}]}""")) }
        server.start()
        try {
            val connection = ModelConnection(server.url("/").toString(), "test-only", "test")
            for (role in listOf(known.characterId, other.characterId)) {
                LuluAiServices.gateway.generate(role,
                    facts = "本人已选定要回应的意思",
                    instruction = "只表达已经选中的真实意思",
                    source = "聊天表达渲染", title = "测试角色表达",
                    connectionOverride = connection, contextMode = CompanionContextMode.Full,
                    memoryRequest = UnifiedMemoryRequest(
                        currentInput = "我刚刚在读书", sceneContext = "私聊"))
                    .getOrThrow()
                val wire = JSONObject(server.takeRequest(5, TimeUnit.SECONDS)!!.body.readUtf8())
                    .getJSONArray("messages").toString()
                assertEquals(role == known.characterId,
                    wire.contains("长气泡不舒服"))
                assertFalse(wire.contains("四点了"))
            }
            assertEquals(2, server.requestCount)
        } finally { server.shutdown() }
    }

    @Test fun nativeDecisionsAndRenderingKeepRelationshipsSeparateWhileCrossWorldKeepsOnlyPersona() = runBlocking {
        val context = RuntimeEnvironment.getApplication() as Context
        LuluRepositories.initialize(context)
        SharedExperienceTimeline.initialize(context)
        LuluAiServices.initialize(context)
        MigratedDomainStores.initialize(context)
        val role = MigratedDomainStores.characters.create("独立人格", "冷静但主动负责")
        CharacterSocialRelationship.setAgreement(role.characterId, "user", "本世界专属约定",
            "原世界中我答应主动跟进的关系标记-837")
        val server = MockWebServer()
        repeat(3) { server.enqueue(MockResponse().setHeader("Content-Type", "application/json")
            .setBody("""{"choices":[{"message":{"content":"测试输出"},"finish_reason":"stop"}]}""")) }
        server.start()
        try {
            val connection = ModelConnection(server.url("/").toString(), "test-only", "test")
            val cases = listOf("聊天工具规划" to CompanionContextMode.Full,
                "聊天表达渲染" to CompanionContextMode.Full,
                "独立跨世界场景" to CompanionContextMode.PersonaAndScenario)
            for ((source, mode) in cases) {
                LuluAiServices.gateway.generate(role.characterId, "此刻的明确场景", "延续本人的选择",
                    source, "test", connectionOverride = connection, contextMode = mode,
                    memoryRequest = UnifiedMemoryRequest(currentInput = "你好", sceneContext = "私聊"))
                    .getOrThrow()
                val system = JSONObject(server.takeRequest(5, TimeUnit.SECONDS)!!.body.readUtf8())
                    .getJSONArray("messages").getJSONObject(0).getString("content")
                assertTrue(system.contains("冷静但主动负责"))
                assertEquals(mode != CompanionContextMode.PersonaAndScenario,
                    system.contains("原世界中我答应主动跟进的关系标记-837"))
                if (source == "聊天表达渲染") {
                    assertTrue(system.contains("理解和行动取舍以本轮决策交接为准"))
                    assertFalse(system.contains(CharacterExpressionGuide.personalityOrigin))
                }
            }
        } finally { server.shutdown() }
    }

    @Test fun dedicatedWriterChangesOnlyExistingExpressionRequestsAndCanBeCleared() = runBlocking {
        val context = RuntimeEnvironment.getApplication() as Context
        LuluRepositories.initialize(context)
        SharedExperienceTimeline.initialize(context)
        LuluAiServices.initialize(context)
        val role = MigratedDomainStores.characters.create("独立表达测试人", "有自己的主见")
        val planner = MockWebServer()
        val writer = MockWebServer()
        fun response() = MockResponse().setBody("""{"choices":[{"message":{"content":"这是本人选定的话。"},"finish_reason":"stop"}]}""")
        repeat(3) { planner.enqueue(response()) }
        writer.enqueue(response())
        planner.start(); writer.start()
        val scope = com.jiacimu.lulu.ai.ScopedModelSelections
        try {
            val store = LuluAiServices.connectionStore
            val config = store.saveConfiguration(null, "独立文字", writer.url("/v1").toString(), "writer-key")
            val archive = store.addArchive(config.id, "writer-model")
            scope.select(scope.EXPRESSION, archive.id)
            val main = ModelConnection(planner.url("/v1").toString(), "planner-key", "planner-model")
            suspend fun generate(source: String, mode: CompanionContextMode = CompanionContextMode.Full) =
                LuluAiServices.gateway.generate(role.characterId, "已决定的意思与真实结果", "只表达既定意思", source,
                    "test", connectionOverride = main, contextMode = mode).getOrThrow()
            generate("普通角色决定")
            generate("聊天表达渲染")
            val rendered = JSONObject(writer.takeRequest(5, TimeUnit.SECONDS)!!.body.readUtf8())
            assertEquals("writer-model", rendered.getString("model"))
            val system = rendered.getJSONArray("messages").getJSONObject(0).getString("content")
            assertTrue(system.contains("独立表达测试人"))
            // Non-dialogue facts are now delivered in the request's user
            // message, while identity remains in system. Assert actual
            // delivery instead of enforcing an obsolete prompt position.
            assertTrue(rendered.getJSONArray("messages").toString()
                .contains("已决定的意思与真实结果"))
            assertFalse(system.contains("【统一角色决策协议】"))
            generate("聊天表达渲染", CompanionContextMode.Isolated)
            scope.clear(scope.EXPRESSION)
            generate("聊天表达渲染")
            repeat(3) { assertEquals("planner-model", JSONObject(planner.takeRequest(5, TimeUnit.SECONDS)!!.body.readUtf8()).getString("model")) }
            assertEquals(3, planner.requestCount)
            assertEquals(1, writer.requestCount)
        } finally { scope.clear(scope.EXPRESSION); planner.shutdown(); writer.shutdown() }
    }
    @Before fun before() {
        MemoryTestEnvironment.release()
        val context = RuntimeEnvironment.getApplication() as Context
        if (runCatching { androidx.work.WorkManager.getInstance(context) }.isFailure)
            androidx.work.WorkManager.initialize(context, androidx.work.Configuration.Builder().build())
        CharacterLifeStore.initialize(context)
    }
    @After fun after() { MemoryTestEnvironment.release() }

    @Test fun bestFriendIdentitySurvivesPrivatePlannerAndLeanRendererWithoutSharingOtherRolesStyle() = runBlocking {
        val context = RuntimeEnvironment.getApplication() as Context
        LuluRepositories.initialize(context)
        SharedExperienceTimeline.initialize(context)
        LuluAiServices.initialize(context)
        val server = MockWebServer()
        repeat(3) {
            server.enqueue(MockResponse().setHeader("Content-Type", "application/json")
                .setBody("""{"choices":[{"message":{"content":"收到。"},"finish_reason":"stop"}]}"""))
        }
        server.start()
        try {
            val connection = ModelConnection(server.url("/").toString(), "test-only", "test")
            val history = listOf(
                ModelConversationMessage(ModelConversationMessage.Role.USER, "归蜜～"),
                ModelConversationMessage(ModelConversationMessage.Role.ASSISTANT, "你在说哪部小说吗？"),
                ModelConversationMessage(ModelConversationMessage.Role.USER, "不是啦，是在叫你呢"),
            )
            for (source in listOf("聊天工具规划", "聊天表达渲染")) {
                LuluAiServices.gateway.generate("lulu",
                    "这一轮由本人判断前后语境，保留当前关系",
                    "用本人身份延续这段熟人聊天", source, "test",
                    connectionOverride = connection,
                    contextMode = if (source == "聊天表达渲染")
                        CompanionContextMode.CharacterAndScenario else CompanionContextMode.Full,
                    memoryRequest = UnifiedMemoryRequest(
                        currentInput = history.last().content, sceneContext = "私聊"),
                    conversation = history).getOrThrow()
                val system = JSONObject(server.takeRequest(5, TimeUnit.SECONDS)!!.body.readUtf8())
                    .getJSONArray("messages").getJSONObject(0).getString("content")
                assertTrue(system.contains("最好的闺蜜"))
                assertTrue(system.contains("同音字"))
                // The old title-keyword overlay was deliberately removed:
                // read this role's CURRENT agreement once, with no imposed
                // assistant/romance exclusion that could contradict later edits.
                val actual = CharacterSocialRelationship.agreement("lulu", "user")!!
                assertTrue(actual.details.contains("平等"))
                assertTrue(system.contains(actual.title))
                assertTrue(system.contains(actual.details))
                assertFalse(system.contains("你和对方是平等朋友，不是她的助手、仆人、恋人"))
                assertFalse(system.contains("过去请别的角色做过某事"))
                assertTrue(system.contains(CharacterProfileSchema.xingHeProfile.getValue("expression")))
                assertTrue(system.contains("星禾"))
            }
            LuluAiServices.gateway.generate(CharacterLifeStore.BUNDLED_JIANG_DU_ID,
                "另一个人物的聊天", "保持自己的人格", "聊天表达渲染", "test",
                connectionOverride = connection,
                contextMode = CompanionContextMode.CharacterAndScenario,
                memoryRequest = UnifiedMemoryRequest(currentInput = "你好", sceneContext = "私聊"),
            ).getOrThrow()
            val anotherSystem = JSONObject(server.takeRequest(5, TimeUnit.SECONDS)!!.body.readUtf8())
                .getJSONArray("messages").getJSONObject(0).getString("content")
            assertFalse(anotherSystem.contains("平等朋友"))
            assertFalse(anotherSystem.contains("不代表她在要求哄睡"))
            assertFalse(anotherSystem.contains(CharacterProfileSchema.xingHeProfile.getValue("expression")))
            assertFalse(anotherSystem.contains(CharacterProfileSchema.xingHeFriendship))
            assertEquals(3, server.requestCount)
        } finally { server.shutdown() }
    }

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
                    assertTrue(system.contains("【本人已经作出选择，现在对她说话】"))
                    assertTrue(system.contains("正文只说本人这一刻真正愿意让对方听见的内容"))
                    assertFalse(system.contains(CharacterExpressionGuide.personalityOrigin))
                    assertTrue(system.contains("熟悉时说话干脆，会接冷笑话"))
                    assertFalse(system.contains("【统一角色决策协议】"))
                    assertFalse(system.contains("【人物的基本原则"))
                } else {
                    assertTrue(system.contains("【统一角色决策协议】"))
                    assertFalse(system.contains("【本人已经作出选择，现在对她说话】"))
                }
                assertTrue(system.contains("【情绪潜台词：让感受进入说话方式】"))
                assertTrue(system.contains("哄睡连续故事按实际需要充分表达"))
            }
            assertEquals(sources.size, server.requestCount)
        } finally { server.shutdown() }
    }

    @Test fun worldBookTopNormalAndBottomAreActuallyPlacedInOneSharedSystemRequest() = runBlocking {
        val context = RuntimeEnvironment.getApplication() as Context
        LuluRepositories.initialize(context)
        SharedExperienceTimeline.initialize(context)
        LuluAiServices.initialize(context)
        val role = MigratedDomainStores.characters.create("世界书位置检验", "严格保持自我身份")
        val other = MigratedDomainStores.characters.create("无关联人物", "不同设定")
        suspend fun store(id: String, position: WorldBookPromptPosition,
                          enabled: Boolean = true, overrides: Map<String, Boolean> = emptyMap()) {
            LuluRepositories.worldBook.save(WorldBookEntry(
                "worldbook-position-$id", "位置-$id", "内容-$id", enabled, overrides,
                promptPosition = position,
            ))
        }
        store("TOP1", WorldBookPromptPosition.TOP)
        store("TOP2", WorldBookPromptPosition.TOP)
        store("MID", WorldBookPromptPosition.NORMAL, false, mapOf(role.characterId to true))
        store("TAIL", WorldBookPromptPosition.BOTTOM)
        store("OTHER", WorldBookPromptPosition.BOTTOM, false, mapOf(other.characterId to true))
        val server = MockWebServer()
        repeat(2) {
            server.enqueue(MockResponse().setHeader("Content-Type", "application/json")
                .setBody("""{"choices":[{"message":{"content":"知道了。"},"finish_reason":"stop"}]}"""))
        }
        server.start()
        try {
            val connection = ModelConnection(server.url("/").toString(), "test-only", "test")
            val history = listOf(ModelConversationMessage(
                ModelConversationMessage.Role.USER, "主人这一轮的原话"))
            LuluAiServices.gateway.generate(
                role.characterId, "本轮背景确认", "继续和主人自然交流", "后台主动感知", "test",
                contextMode = CompanionContextMode.Full,
                memoryRequest = UnifiedMemoryRequest(
                    currentInput = "主人这一轮的原话", sceneContext = "私聊"),
                connectionOverride = connection, conversation = history,
            ).getOrThrow()
            val wire = JSONObject(server.takeRequest(5, TimeUnit.SECONDS)!!.body.readUtf8())
            val messages = wire.getJSONArray("messages")
            val system = messages.getJSONObject(0).getString("content")
            assertTrue(system.startsWith("全局世界书：\n- 位置-TOP1：内容-TOP1"))
            assertTrue(system.indexOf("内容-TOP1") < system.indexOf("内容-TOP2"))
            assertTrue(system.indexOf("内容-TOP2") < system.indexOf("你正在以"))
            assertTrue(system.indexOf("角色世界书：\n- 位置-MID") > system.indexOf("角色身份"))
            assertTrue(system.indexOf("内容-MID") < system.indexOf("本轮背景确认"))
            assertTrue(system.endsWith("- 位置-TAIL：内容-TAIL"))
            assertFalse(system.contains("内容-OTHER"))
            listOf("TOP1", "TOP2", "MID", "TAIL").forEach { id ->
                assertEquals(1, Regex("内容-$id").findAll(system).count())
            }
            assertEquals("user", messages.getJSONObject(messages.length() - 1).getString("role"))
            assertEquals("主人这一轮的原话",
                messages.getJSONObject(messages.length() - 1).getString("content"))

            // An isolated theater/tool task must never inherit character world books.
            LuluAiServices.gateway.generate(
                role.characterId, "剧情素材", "生成", "独立剧场", "test",
                contextMode = CompanionContextMode.Isolated, connectionOverride = connection,
            ).getOrThrow()
            val isolated = JSONObject(server.takeRequest(5, TimeUnit.SECONDS)!!.body.readUtf8())
                .getJSONArray("messages").getJSONObject(0).getString("content")
            assertFalse(isolated.contains("内容-TOP1"))
            assertFalse(isolated.contains("内容-TAIL"))
        } finally { server.shutdown() }
    }

    @Test(timeout = 60_000) fun privateChatPreservesModelAuthoredTurnWithoutProgramTriggeredRewrite() = runBlocking {
        val context = RuntimeEnvironment.getApplication() as Context
        LuluRepositories.initialize(context)
        SharedExperienceTimeline.initialize(context)
        LuluAiServices.initialize(context)
        LuluDeviceToolBridge.initialize(context)
        com.jiacimu.lulu.study.StarWishStores.initialize(context)
        val role = MigratedDomainStores.characters.create("整轮表达回归角色", "说话直接，有自己的主见")
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
        server.start()
        try {
            val config = LuluAiServices.connectionStore.saveConfiguration(null, "test",
                server.url("/").toString(), "test-only")
            LuluAiServices.connectionStore.addArchive(config.id, "test")
            val reply = LuluDeviceToolBridge.respond(role.characterId, "历史应通过真实消息发送", "不读书就没钱，", "test",
                userEvidenceIds = listOf(current.id))
                .getOrThrow()
            // This fixture used to trigger a lexical quality rewrite. Its style is
            // deliberately conspicuous: verify model ownership, not human quality.
            assertEquals(ConversationNaturalnessFixtures.screenshotComfort
                .joinToString(com.jiacimu.lulu.SemanticBubbleSeparator), reply.text)
            assertEquals(2, server.requestCount) // Planner and model-authored expression only.
            val audited = LuluRepositories.performance.consoleRecords.first()
                .filter { it.turnTraceId.isNotBlank() &&
                    it.requestSource in setOf("聊天工具规划", "聊天表达渲染") }
            val rendererAudit = audited.first { it.requestSource == "聊天表达渲染" }
            val plannerAudit = audited.first { it.requestSource == "聊天工具规划" &&
                it.turnTraceId == rendererAudit.turnTraceId }
            assertEquals(plannerAudit.turnTraceId, rendererAudit.turnTraceId)
            val trace = rendererAudit.turnTraceId
            assertEquals(trace, reply.diagnosticTurnTraceId)
            val stages = LuluRepositories.performance.timingRecords.first()
                .filter { it.detail.contains("trace=$trace") }
            assertTrue(stages.any { it.stage == "P08a角色规划" &&
                it.detail.contains("move=reassure") })
            assertTrue(stages.any { it.stage == "P08a表达结果" &&
                it.detail.contains("structured=true") })
            assertTrue(stages.none { it.detail.contains("不读书就没钱") })
            val requests = (1..2).map { JSONObject(server.takeRequest(5, TimeUnit.SECONDS)!!.body.readUtf8()) }
            val planner = requests[0].getJSONArray("messages").toString()
            val expression = requests[1].getJSONArray("messages").toString()
            assertTrue(planner.contains("她在说现实困难时，担忧本身可以成立"))
            assertTrue(expression.contains("所有气泡合起来才是本轮回复"))
            (1..1).forEach { index ->
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

    @Test(timeout = 60_000) fun missingPlannerMoveReachesWriterWithoutInventingAnswer() = runBlocking {
        val context = RuntimeEnvironment.getApplication() as Context
        LuluRepositories.initialize(context)
        SharedExperienceTimeline.initialize(context)
        LuluAiServices.initialize(context)
        LuluDeviceToolBridge.initialize(context)
        val role = MigratedDomainStores.characters.create("即兴接话回归", "性格机灵而克制")
        val chat = MigratedDomainStores.chat.ensureConversation(role.characterId, "test")
        val incoming = MigratedDomainStores.chat.sendUserMessage(chat.id, "你故意逗我吧")
        val server = MockWebServer()
        fun response(content: String) = MockResponse().setHeader("Content-Type", "application/json")
            .setBody(JSONObject().put("choices", JSONArray().put(JSONObject().put("message",
                JSONObject().put("content", content)).put("finish_reason", "stop"))).toString())
        // A valid choice of reply with speechIntent remains meaningful even if
        // an optional dialogueMove was omitted by the planner model.
        server.enqueue(response("""{"action":"reply","speechIntent":"被逗得笑了一下，顺口反问"}"""))
        server.enqueue(response("""{"action":"reply","bubbles":[{"text":"你才发现啊。"}]}"""))
        server.start()
        try {
            val config = LuluAiServices.connectionStore.saveConfiguration(null, "impulse-test",
                server.url("/").toString(), "test-only")
            LuluAiServices.connectionStore.addArchive(config.id, "test")
            val reply = LuluDeviceToolBridge.respond(role.characterId, "", incoming.content, "test",
                userEvidenceIds = listOf(incoming.id)).getOrThrow()
            assertEquals("你才发现啊。", reply.text)
            assertEquals(2, server.requestCount)
            server.takeRequest(5, TimeUnit.SECONDS)!!
            val rendering = JSONObject(server.takeRequest(5, TimeUnit.SECONDS)!!.body.readUtf8())
                .getJSONArray("messages").getJSONObject(0).getString("content")
            assertTrue(rendering.contains("被逗得笑了一下，顺口反问"))
            assertTrue(rendering.contains("本轮未提供明确的会话动作"))
            assertFalse(rendering.contains("已选择的会话动作】answer"))
            assertTrue(rendering.contains("性格机灵而克制"))
        } finally { server.shutdown() }
    }

    @Test(timeout = 60_000) fun privateChatExpressionReceivesFullChosenIntentIncludingTailBoundary() = runBlocking {
        val context = RuntimeEnvironment.getApplication() as Context
        LuluRepositories.initialize(context)
        SharedExperienceTimeline.initialize(context)
        LuluAiServices.initialize(context)
        LuluDeviceToolBridge.initialize(context)
        val role = MigratedDomainStores.characters.create("完整意图交接", "说话有分寸")
        val chat = MigratedDomainStores.chat.ensureConversation(role.characterId, "test")
        val current = MigratedDomainStores.chat.sendUserMessage(chat.id, "进展怎么样？")
        val tail = "但没有真正回执前不要说已经完成，也不要代替她确认。"
        val intent = "先回应当前进展。" + "这是独立判断的一部分。".repeat(100) + tail
        val server = MockWebServer()
        fun response(content: String) = MockResponse().setHeader("Content-Type", "application/json")
            .setBody(JSONObject().put("choices", JSONArray().put(JSONObject().put("message",
                JSONObject().put("content", content)).put("finish_reason", "stop"))).toString())
        server.enqueue(response(JSONObject().put("action", "reply").put("speechIntent", intent)
            .put("dialogueMove", JSONObject().put("type", "answer").put("contentIntent", "交代进展"))
            .toString()))
        server.enqueue(response("""{"action":"reply","bubbles":[{"text":"还没有实际完成，我再确认。"}]}"""))
        server.start()
        try {
            val config = LuluAiServices.connectionStore.saveConfiguration(null, "test",
                server.url("/").toString(), "test-only")
            LuluAiServices.connectionStore.addArchive(config.id, "test")
            LuluDeviceToolBridge.respond(role.characterId, "", current.content, "test",
                userEvidenceIds = listOf(current.id)).getOrThrow()
            server.takeRequest(5, TimeUnit.SECONDS)!!
            val request = JSONObject(server.takeRequest(5, TimeUnit.SECONDS)!!.body.readUtf8())
            assertTrue(request.getJSONArray("messages").toString().contains(tail))
            assertEquals(2, server.requestCount)
        } finally { server.shutdown() }
    }

    @Test(timeout = 60_000) fun phoneThoughtDoesNotExecuteFavoriteButExplicitChoiceDoesWithoutForcedSpeech() = runBlocking {
        val context = RuntimeEnvironment.getApplication() as Context
        LuluRepositories.initialize(context)
        SharedExperienceTimeline.initialize(context)
        LuluAiServices.initialize(context)
        LuluDeviceToolBridge.initialize(context)
        val role = MigratedDomainStores.characters.create("电话意愿与行动", "独立判断")
        val chat = MigratedDomainStores.chat.ensureConversation(role.characterId, "test")
        val heard = MigratedDomainStores.chat.sendUserMessage(chat.id, "你上次讲的笑话我还记得")
        val server = MockWebServer()
        fun response(content: String) = MockResponse().setHeader("Content-Type", "application/json")
            .setBody(JSONObject().put("choices", JSONArray().put(JSONObject().put("message",
                JSONObject().put("content", content)).put("finish_reason", "stop"))).toString())
        server.enqueue(response("""{"action":"reply","text":"我听见了。","innerThought":"我想收藏这句。"}"""))
        server.enqueue(response(JSONObject().put("action", "reply").put("text", "那句我很喜欢。")
            .put("phoneFavorite", JSONObject().put("messageId", heard.id)
                .put("reason", "她记得我上次的笑话，我想留下这句"))
            .toString()))
        server.start()
        try {
            val config = LuluAiServices.connectionStore.saveConfiguration(null, "test",
                server.url("/").toString(), "test-only")
            LuluAiServices.connectionStore.addArchive(config.id, "test")
            val first = LuluDeviceToolBridge.respond(role.characterId, "", heard.content, "test",
                sceneContext = "正在电话中", userEvidenceIds = listOf(heard.id),
                phoneCallId = "phone-choice-test").getOrThrow()
            assertEquals("我听见了。", first.text)
            assertFalse(LuluRepositories.lexicon.snapshot(role.characterId).any {
                it.id == CharacterMessageFavorites.entryId(role.characterId, heard.id) })
            val second = LuluDeviceToolBridge.respond(role.characterId, "", heard.content, "test",
                sceneContext = "正在电话中", userEvidenceIds = listOf(heard.id),
                phoneCallId = "phone-choice-test").getOrThrow()
            assertEquals("那句我很喜欢。", second.text)
            assertTrue(LuluRepositories.lexicon.snapshot(role.characterId).any {
                it.id == CharacterMessageFavorites.entryId(role.characterId, heard.id) })
            assertEquals(2, server.requestCount)
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
            assertTrue(system.contains("从本人的性格、所知、立场与在意之处"))
            assertTrue(system.contains("选择此刻真正想对这个人说什么"))
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
