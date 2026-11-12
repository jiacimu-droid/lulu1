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
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import okhttp3.mockwebserver.Dispatcher
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest
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
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

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

    @Test fun sceneRhythmIsNotInjectedTwiceInTheActualExpressionRequest() = runBlocking {
        val context = RuntimeEnvironment.getApplication() as Context
        LuluRepositories.initialize(context)
        LuluAiServices.initialize(context)
        val role = MigratedDomainStores.characters.create("自然打字节拍", "有自己的说话习惯")
        val scene = CharacterExpressionGuide.channelNote(CharacterSpeechChannel.PRIVATE_CHAT)
        val server = MockWebServer()
        server.enqueue(MockResponse().setHeader("Content-Type", "application/json")
            .setBody("""{"choices":[{"message":{"content":"{\"action\":\"reply\",\"bubbles\":[{\"text\":\"收到\"},{\"text\":\"我看见了\"}]}"},"finish_reason":"stop"}]}"""))
        server.start()
        try {
            LuluAiServices.gateway.generate(role.characterId,
                facts = "用户说刚在做题，已经由人物决定接话",
                instruction = scene, source = "聊天表达渲染", title = "私聊表达节拍回归",
                connectionOverride = ModelConnection(server.url("/").toString(), "test-only", "test"),
                contextMode = CompanionContextMode.CharacterAndScenario).getOrThrow()
            val wire = JSONObject(server.takeRequest(5, TimeUnit.SECONDS)!!.body.readUtf8())
            val system = wire.getJSONArray("messages").getJSONObject(0).getString("content")
            assertEquals("Exactly one authored turn-taking contract in the actual model request",
                1, system.split(CharacterExpressionGuide.casualChatRhythm).size - 1)
            assertTrue(system.contains(CharacterExpressionGuide.livingLanguagePrinciples))
            assertTrue(system.contains("【本人的语言习惯｜随交流自然表现】"))
            assertFalse("No forbidden example should be quoted into the model system prompt",
                system.contains("不能写长篇大论"))
            assertEquals(1, server.requestCount)
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

    @Test fun legacyExpressionWriterNeverOverridesTheCharactersOwnSelectedModel() = runBlocking {
        val context = RuntimeEnvironment.getApplication() as Context
        LuluRepositories.initialize(context)
        SharedExperienceTimeline.initialize(context)
        LuluAiServices.initialize(context)
        val role = MigratedDomainStores.characters.create("独立表达测试人", "有自己的主见")
        val planner = MockWebServer()
        val writer = MockWebServer()
        fun response() = MockResponse().setBody("""{"choices":[{"message":{"content":"这是本人选定的话。"},"finish_reason":"stop"}]}""")
        repeat(4) { planner.enqueue(response()) }
        // A previously saved dedicated writer must no longer buy calls.
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
            generate("聊天表达渲染", CompanionContextMode.Isolated)
            scope.clear(scope.EXPRESSION)
            generate("聊天表达渲染")
            val actual = (0 until 4).map {
                JSONObject(planner.takeRequest(5, TimeUnit.SECONDS)!!.body.readUtf8())
            }
            assertTrue(actual.all { it.getString("model") == "planner-model" })
            val rendered = actual[1]
            val system = rendered.getJSONArray("messages").getJSONObject(0).getString("content")
            assertTrue(system.contains("独立表达测试人"))
            // Facts and the same character's identity must not be swapped
            // through an invisible legacy expression-model preference.
            assertTrue(rendered.getJSONArray("messages").toString()
                .contains("已决定的意思与真实结果"))
            assertFalse(system.contains("【统一角色决策协议】"))
            assertEquals(4, planner.requestCount)
            assertEquals("旧独立表达配置不再发生额外扣费请求", 0, writer.requestCount)
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
                    assertTrue(system.contains("承接本轮已选的交流意图、事实、立场与感受"))
                    assertTrue(system.contains("必要答案、条件、否定与不确定性完整保留"))
                    assertFalse(system.contains(CharacterExpressionGuide.personalityOrigin))
                    assertTrue(system.contains("熟悉时说话干脆，会接冷笑话"))
                    assertFalse(system.contains("【统一角色决策协议】"))
                    assertFalse(system.contains("【人物的基本原则"))
                } else {
                    assertTrue(system.contains("【统一角色决策协议】"))
                    assertFalse(system.contains("【本人已经作出选择，现在对她说话】"))
                }
                assertTrue(system.contains("【情绪潜台词：让感受进入说话方式】"))
                assertTrue(system.contains("认真答疑、完整创作和连续故事照实际需要展开"))
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
            assertTrue(expression.contains("所有气泡合起来是本轮回复"))
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

    @Test(timeout = 60_000) fun phoneIntentRecoveryKeepsTheCurrentPersonAndCompleteBoundaries() = runBlocking {
        val context = RuntimeEnvironment.getApplication() as Context
        LuluRepositories.initialize(context)
        SharedExperienceTimeline.initialize(context)
        LuluAiServices.initialize(context)
        LuluDeviceToolBridge.initialize(context)
        val jiang = MigratedDomainStores.characters.create("江渡电话样本", "江渡性格样本：先问清事实")
        val xing = MigratedDomainStores.characters.create("星禾电话样本", "星禾性格样本：直率又独立")
        CharacterIdentityStore.set(jiang.characterId, "江渡身份样本：独立插画师")
        CharacterIdentityStore.set(xing.characterId, "星禾身份样本：社区园艺师")
        val oldAgreement = "江渡旧约定：午后可以来电"
        val newAgreement = "江渡新约定：除非本人确认，否则不要再拨"
        val xingAgreement = "星禾约定：只在答应以后发文字"
        assertTrue(CharacterLifeStore.setRelationshipAgreement(jiang.characterId, "user", "江渡约定", oldAgreement))
        assertTrue(CharacterLifeStore.setRelationshipAgreement(xing.characterId, "user", "星禾约定", xingAgreement))

        val intent = "先按实际发生的事解释。".repeat(70) + "如果还没有明确同意，就不承诺再次来电。"
        val currentInput = "这次我想先把之前发生的经过都说明白。".repeat(50) + "但我没有授权你再次来电。"
        val server = MockWebServer()
        fun enqueue(content: String) {
            server.enqueue(MockResponse().setHeader("Content-Type", "application/json")
                .setBody(JSONObject().put("choices", JSONArray().put(JSONObject()
                    .put("message", JSONObject().put("content", content))
                    .put("finish_reason", "stop"))).toString()))
        }
        enqueue("""{"action":"reply","text":"我听到了。"}""")
        enqueue("""{"action":"reply","text":"嗯，我记得。"}""")
        enqueue(JSONObject().put("action", "reply").put("speechIntent", intent).toString())
        enqueue("好，我不会再打。")
        enqueue("独立任务")
        server.start()
        try {
            val config = LuluAiServices.connectionStore.saveConfiguration(null, "test",
                server.url("/").toString(), "test-only")
            val archive = LuluAiServices.connectionStore.addArchive(config.id, "test")
            suspend fun call(roleId: String, input: String) =
                LuluDeviceToolBridge.respond(roleId, "", input, "test", archiveId = archive.id,
                    sceneContext = "正在电话中", phoneCallId = "phone-persona-contract-$roleId").getOrThrow()
            fun requestBody(): String = checkNotNull(server.takeRequest(5, TimeUnit.SECONDS)).body.readUtf8()
            fun prompt(body: String) = JSONObject(body).getJSONArray("messages").getJSONObject(0).getString("content")

            assertEquals("我听到了。", call(jiang.characterId, "我说完了").text)
            val jiangPrompt = prompt(requestBody())
            assertTrue(jiangPrompt.contains("江渡性格样本"))
            assertTrue(jiangPrompt.contains("江渡身份样本"))
            assertFalse(jiangPrompt.contains("星禾身份样本"))
            assertTrue(jiangPrompt.contains(oldAgreement))
            assertFalse(jiangPrompt.contains(xingAgreement))

            assertEquals("嗯，我记得。", call(xing.characterId, "我说完了").text)
            val xingPrompt = prompt(requestBody())
            assertTrue(xingPrompt.contains("星禾性格样本"))
            assertTrue(xingPrompt.contains("星禾身份样本"))
            assertFalse(xingPrompt.contains("江渡身份样本"))
            assertTrue(xingPrompt.contains(xingAgreement))
            assertFalse(xingPrompt.contains("江渡性格样本"))
            assertFalse(xingPrompt.contains(oldAgreement))

            assertTrue(CharacterLifeStore.setRelationshipAgreement(jiang.characterId, "user", "江渡约定", newAgreement))
            assertEquals("好，我不会再打。", call(jiang.characterId, currentInput).text)
            val decision = prompt(requestBody())
            val rendering = JSONObject(requestBody()).getJSONArray("messages")
            val renderingSystem = rendering.getJSONObject(0).getString("content")
            val renderingFacts = rendering.getJSONObject(rendering.length() - 1).getString("content")
            for (system in listOf(decision, renderingSystem)) {
                assertTrue(system.contains("江渡性格样本"))
                assertTrue(system.contains("江渡身份样本"))
                assertFalse(system.contains("星禾身份样本"))
                assertTrue(system.contains(newAgreement))
                assertFalse(system.contains(oldAgreement))
                assertFalse(system.contains(xingAgreement))
            }
            assertTrue(renderingFacts.contains(intent))
            assertTrue(renderingFacts.contains(currentInput))
            assertFalse(renderingSystem.contains("【统一角色决策协议】"))
            assertFalse(renderingSystem.contains("JSON 中每个 bubbles 元素"))
            assertTrue(renderingSystem.contains("理解和行动取舍以本轮决策交接为准"))

            LuluAiServices.gateway.generate(jiang.characterId, "独立素材", "只处理素材",
                "独立工具任务", "test", contextMode = CompanionContextMode.Isolated,
                connectionOverride = ModelConnection(server.url("/").toString(), "test-only", "test")).getOrThrow()
            val isolated = prompt(requestBody())
            assertFalse(isolated.contains(newAgreement))
            assertFalse(isolated.contains("江渡性格样本"))
            assertFalse(isolated.contains("江渡身份样本"))
            assertFalse(isolated.contains(xingAgreement))
            assertEquals(5, server.requestCount)
        } finally { server.shutdown() }
    }

    @Test(timeout = 60_000) fun interruptedPhoneIntentRecoveryDoesNotStartOldSideActions() = runBlocking {
        val context = RuntimeEnvironment.getApplication() as Context
        LuluRepositories.initialize(context)
        SharedExperienceTimeline.initialize(context)
        LuluAiServices.initialize(context)
        LuluDeviceToolBridge.initialize(context)
        val role = MigratedDomainStores.characters.create("电话打断样本", "在意明确授权")
        val characterId = role.characterId
        val chat = MigratedDomainStores.chat.ensureConversation(characterId, "test")
        val oldTurn = MigratedDomainStores.chat.sendUserMessage(chat.id, "想听点音乐")
        val nextTurn = MigratedDomainStores.chat.sendUserMessage(chat.id, "我先把话说完")
        val callId = "phone-interrupted-intent"
        val releaseRendering = CountDownLatch(1)
        val requests = AtomicInteger()
        fun response(content: String) = MockResponse().setHeader("Content-Type", "application/json")
            .setBody(JSONObject().put("choices", JSONArray().put(JSONObject()
                .put("message", JSONObject().put("content", content))
                .put("finish_reason", "stop"))).toString())
        fun mediaReply() = JSONObject().put("action", "reply")
            .put("afterCallMedia", JSONObject().put("trigger", "call_end").put("delaySeconds", 30))
        val server = MockWebServer()
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse = when (requests.getAndIncrement()) {
                0 -> response(mediaReply().put("speechIntent", "先回答，再按自己选定的时间尝试播放").toString())
                1 -> if (releaseRendering.await(15, TimeUnit.SECONDS)) response("我听见了。")
                    else MockResponse().setResponseCode(500)
                2 -> response(mediaReply().put("text", "那就等通话结束。").toString())
                else -> MockResponse().setResponseCode(500)
            }
        }
        server.start()
        try {
            val config = LuluAiServices.connectionStore.saveConfiguration(null, "test",
                server.url("/").toString(), "test-only")
            val archive = LuluAiServices.connectionStore.addArchive(config.id, "test")
            fun hasMediaReceipt(turnId: String) = SharedExperienceTimeline.eventsByIds(
                characterId, listOf("phone-media-decision:$characterId:$turnId")
            ).any { it.source == "deferred-call-media" }
            val interrupted = async {
                LuluDeviceToolBridge.respond(characterId, "", oldTurn.content, "test",
                    archiveId = archive.id, sceneContext = "正在电话中", phoneCallId = callId,
                    userEvidenceIds = listOf(oldTurn.id))
            }
            try {
                withContext(Dispatchers.IO) {
                    repeat(2) { checkNotNull(server.takeRequest(10, TimeUnit.SECONDS)) }
                }
                // While the response is held, the old turn must not have attempted the media step.
                assertFalse(hasMediaReceipt(oldTurn.id))
                interrupted.cancel()
            } finally {
                releaseRendering.countDown()
                interrupted.cancelAndJoin()
            }
            assertTrue(interrupted.isCancelled)
            assertFalse(hasMediaReceipt(oldTurn.id))

            val next = LuluDeviceToolBridge.respond(characterId, "", nextTurn.content, "test",
                archiveId = archive.id, sceneContext = "正在电话中", phoneCallId = callId,
                userEvidenceIds = listOf(nextTurn.id)).getOrThrow()
            assertEquals("那就等通话结束。", next.text)
            // No music player is required: the genuine tool attempt still returns its own receipt.
            assertFalse(next.phoneMediaReceipt.isNullOrBlank())
            assertTrue(hasMediaReceipt(nextTurn.id))
            assertFalse(hasMediaReceipt(oldTurn.id))
            assertEquals(3, server.requestCount)
        } finally {
            releaseRendering.countDown()
            server.shutdown()
        }
    }


    @Test(timeout = 60_000) fun phoneIntentRecoveryDiscardsStaleDecisionAfterRelationshipEdit() = runBlocking {
        val context = RuntimeEnvironment.getApplication() as Context
        LuluRepositories.initialize(context)
        SharedExperienceTimeline.initialize(context)
        CharacterLifeStore.initialize(context)
        LuluAiServices.initialize(context)
        LuluDeviceToolBridge.initialize(context)
        val role = MigratedDomainStores.characters.create("电话编辑样本", "初始人物判断样本")
        val id = role.characterId
        val oldAgreement = "旧关系样本：可以主动提议音乐"
        val newAgreement = "新关系样本：只有我明确同意才安排音乐，否则先不安排"
        assertTrue(CharacterLifeStore.setRelationshipAgreement(id, "user", "约定", oldAgreement))
        val chat = MigratedDomainStores.chat.ensureConversation(id, "test")
        val oldTurn = MigratedDomainStores.chat.sendUserMessage(chat.id, "我想聊聊音乐")
        val callId = "phone-edited-intent"
        val releaseRendering = CountDownLatch(1)
        val requests = AtomicInteger()
        fun response(content: String) = MockResponse().setHeader("Content-Type", "application/json")
            .setBody(JSONObject().put("choices", JSONArray().put(JSONObject()
                .put("message", JSONObject().put("content", content))
                .put("finish_reason", "stop"))).toString())
        fun mediaReply() = JSONObject().put("action", "reply")
            .put("afterCallMedia", JSONObject().put("trigger", "call_end").put("delaySeconds", 30))
        val server = MockWebServer()
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse = when (requests.getAndIncrement()) {
                0 -> response(mediaReply().put("speechIntent", "接住她想聊的事")
                    .put("mediaUnderstanding", JSONObject().put("music", "stop")).toString())
                1 -> if (releaseRendering.await(15, TimeUnit.SECONDS)) response("这是旧轮补出的台词。")
                    else MockResponse().setResponseCode(500)
                2 -> response(mediaReply().put("text", "这次按你刚确认的安排。").toString())
                else -> MockResponse().setResponseCode(500)
            }
        }
        server.start()
        try {
            val config = LuluAiServices.connectionStore.saveConfiguration(null, "test",
                server.url("/").toString(), "test-only")
            val archive = LuluAiServices.connectionStore.addArchive(config.id, "test")
            fun hasMediaReceipt(turnId: String) = SharedExperienceTimeline.eventsByIds(
                id, listOf("phone-media-decision:$id:$turnId")
            ).any { it.source == "deferred-call-media" }
            val before = DeferredCallMediaAction.musicContext(id, callId)
            val pending = async {
                LuluDeviceToolBridge.respond(id, "", oldTurn.content, "test",
                    archiveId = archive.id, sceneContext = "正在电话中", phoneCallId = callId,
                    userEvidenceIds = listOf(oldTurn.id))
            }
            try {
                withContext(Dispatchers.IO) {
                    repeat(2) { checkNotNull(server.takeRequest(10, TimeUnit.SECONDS)) }
                }
                assertEquals(before, DeferredCallMediaAction.musicContext(id, callId))
                assertTrue(CharacterLifeStore.setRelationshipAgreement(id, "user", "约定", newAgreement))
                MigratedDomainStores.characters.update(role.copy(persona = "编辑后的人物判断样本"))
            } finally {
                releaseRendering.countDown()
            }
            val stale = pending.await()
            assertTrue(stale.isFailure)
            assertTrue(stale.exceptionOrNull()?.message.orEmpty().contains("设定或关系已更新"))
            assertEquals(before, DeferredCallMediaAction.musicContext(id, callId))
            assertFalse(hasMediaReceipt(oldTurn.id))

            val nextTurn = MigratedDomainStores.chat.sendUserMessage(chat.id, "这次我确认，可以安排")
            val next = LuluDeviceToolBridge.respond(id, "", nextTurn.content, "test",
                archiveId = archive.id, sceneContext = "正在电话中", phoneCallId = callId,
                userEvidenceIds = listOf(nextTurn.id)).getOrThrow()
            val nextRequest = JSONObject(checkNotNull(server.takeRequest(5, TimeUnit.SECONDS)).body.readUtf8())
            val system = nextRequest.getJSONArray("messages").getJSONObject(0).getString("content")
            assertTrue(system.contains(newAgreement))
            assertTrue(system.contains("编辑后的人物判断样本"))
            assertFalse(system.contains(oldAgreement))
            assertEquals("这次按你刚确认的安排。", next.text)
            assertTrue(hasMediaReceipt(nextTurn.id))
            assertFalse(hasMediaReceipt(oldTurn.id))
            assertEquals(3, server.requestCount)
        } finally {
            releaseRendering.countDown()
            server.shutdown()
        }
    }


    @Test(timeout = 60_000) fun firstPhoneDecisionRejectsOldDirectSpeechAndUnrenderedIntentAfterEdit() = runBlocking {
        val context = RuntimeEnvironment.getApplication() as Context
        LuluRepositories.initialize(context)
        SharedExperienceTimeline.initialize(context)
        CharacterLifeStore.initialize(context)
        LuluAiServices.initialize(context)
        LuluDeviceToolBridge.initialize(context)

        for (speechField in listOf("text", "speechIntent")) {
            val role = MigratedDomainStores.characters.create("首次电话决策样本-$speechField", "保留条件")
            val id = role.characterId
            val oldAgreement = "旧约定：可以主动安排音乐"
            val newAgreement = "新约定：只有我明确同意才安排音乐，否则先不安排"
            assertTrue(CharacterLifeStore.setRelationshipAgreement(id, "user", "约定", oldAgreement))
            val chat = MigratedDomainStores.chat.ensureConversation(id, "test")
            val oldTurn = MigratedDomainStores.chat.sendUserMessage(chat.id, "我想聊聊音乐")
            val callId = "phone-first-decision-edit-$id"
            val releaseDecision = CountDownLatch(1)
            val requests = AtomicInteger()
            fun response(content: String) = MockResponse().setHeader("Content-Type", "application/json")
                .setBody(JSONObject().put("choices", JSONArray().put(JSONObject()
                    .put("message", JSONObject().put("content", content))
                    .put("finish_reason", "stop"))).toString())
            val oldPlan = JSONObject().put("action", "reply").put(speechField, "旧轮决定的表达")
                .put("mediaUnderstanding", JSONObject().put("music", "stop"))
                .put("afterCallMedia", JSONObject().put("trigger", "call_end").put("delaySeconds", 30))
            val server = MockWebServer()
            server.dispatcher = object : Dispatcher() {
                override fun dispatch(request: RecordedRequest): MockResponse = when (requests.getAndIncrement()) {
                    0 -> if (releaseDecision.await(15, TimeUnit.SECONDS)) response(oldPlan.toString())
                        else MockResponse().setResponseCode(500)
                    1 -> response("""{"action":"reply","text":"我听清了，这次先不安排。"}""")
                    else -> MockResponse().setResponseCode(500)
                }
            }
            server.start()
            try {
                val config = LuluAiServices.connectionStore.saveConfiguration(null, "test",
                    server.url("/").toString(), "test-only")
                val archive = LuluAiServices.connectionStore.addArchive(config.id, "test")
                suspend fun call(turn: LuluChatMessage) = LuluDeviceToolBridge.respond(
                    id, "", turn.content, "test", archiveId = archive.id, sceneContext = "正在电话中",
                    phoneCallId = callId, userEvidenceIds = listOf(turn.id),
                )
                fun systemPrompt(request: RecordedRequest) = JSONObject(request.body.readUtf8())
                    .getJSONArray("messages").getJSONObject(0).getString("content")
                val before = DeferredCallMediaAction.musicContext(id, callId)
                val pending = async { call(oldTurn) }
                try {
                    val first = withContext(Dispatchers.IO) {
                        checkNotNull(server.takeRequest(10, TimeUnit.SECONDS))
                    }
                    assertTrue(systemPrompt(first).contains(oldAgreement))
                    assertTrue(CharacterLifeStore.setRelationshipAgreement(id, "user", "约定", newAgreement))
                } finally {
                    releaseDecision.countDown()
                }
                val stale = pending.await()
                assertTrue("Expected stale failure for $speechField", stale.isFailure)
                assertTrue(stale.exceptionOrNull()?.message.orEmpty().contains("设定或关系已更新"))
                assertEquals(before, DeferredCallMediaAction.musicContext(id, callId))
                assertTrue(SharedExperienceTimeline.eventsByIds(id,
                    listOf("phone-media-decision:$id:${oldTurn.id}")).isEmpty())
                assertEquals("No extra rendering or retry for $speechField", 1, server.requestCount)

                val nextTurn = MigratedDomainStores.chat.sendUserMessage(chat.id, "我这次还没确认")
                assertEquals("我听清了，这次先不安排。", call(nextTurn).getOrThrow().text)
                val next = systemPrompt(checkNotNull(server.takeRequest(5, TimeUnit.SECONDS)))
                assertTrue(next.contains(newAgreement))
                assertFalse(next.contains(oldAgreement))
                assertEquals(2, server.requestCount)
            } finally {
                releaseDecision.countDown()
                server.shutdown()
            }
        }
    }


    @Test(timeout = 60_000) fun malformedPhoneRecoveryRejectsEditsDuringSpeechOrMotionRepair() = runBlocking {
        val context = RuntimeEnvironment.getApplication() as Context
        LuluRepositories.initialize(context)
        SharedExperienceTimeline.initialize(context)
        CharacterLifeStore.initialize(context)
        LuluAiServices.initialize(context)
        LuluDeviceToolBridge.initialize(context)

        for (blockedRequest in listOf(2, 3)) {
            val role = MigratedDomainStores.characters.create("电话格式恢复样本-$blockedRequest", "保留条件")
            val id = role.characterId
            val callId = "malformed-phone-edit-$id"
            val oldAgreement = "旧约定：先按之前说好的称呼"
            val newAgreement = "新约定：除非我主动提起，否则不用旧称呼"
            assertTrue(CharacterLifeStore.setRelationshipAgreement(id, "user", "约定", oldAgreement))
            val relative = "models/test-recovery-$id/avatar.model3.json"
            val settings = java.io.File(com.jiacimu.lulu.Live2DAssets.base(context), relative)
            settings.parentFile?.mkdirs()
            settings.writeText("""{"FileReferences":{"Moc":"avatar.moc3","Textures":["skin.png"],"Motions":{"Gesture":[{"File":"wave.motion3.json"}]}}}""")
            val models = context.getSharedPreferences("lulu_live2d_models_v1", 0)
            models.edit().putString("model:$id", relative).commit()
            com.jiacimu.lulu.Live2DVisualActionBus.stageReady(id, callId)

            val release = CountDownLatch(1)
            val requests = AtomicInteger()
            val actions = AtomicInteger()
            val statuses = AtomicInteger()
            val spoken = "我听清楚了。"
            fun response(content: String) = MockResponse().setHeader("Content-Type", "application/json")
                .setBody(JSONObject().put("choices", JSONArray().put(JSONObject()
                    .put("message", JSONObject().put("content", content))
                    .put("finish_reason", "stop"))).toString())
            val server = MockWebServer()
            server.dispatcher = object : Dispatcher() {
                override fun dispatch(request: RecordedRequest): MockResponse {
                    val index = requests.incrementAndGet()
                    if (index == blockedRequest && !release.await(15, TimeUnit.SECONDS))
                        return MockResponse().setResponseCode(500)
                    return when (index) {
                        1 -> response("""{"action":""")
                        2 -> response(spoken)
                        3 -> response("""{"live2d":{"speech":[{"id":"s1","text":"$spoken","motion":{"group":"Gesture","index":0},"transition":"blend"}]}}""")
                        else -> MockResponse().setResponseCode(500)
                    }
                }
            }
            server.start()
            try {
                assertTrue(com.jiacimu.lulu.Live2DVisualActionBus.capabilityNote(context, id, callId)
                    .contains("Gesture"))
                val config = LuluAiServices.connectionStore.saveConfiguration(null, "test",
                    server.url("/").toString(), "test-only")
                val archive = LuluAiServices.connectionStore.addArchive(config.id, "test")
                val pending = async {
                    LuluDeviceToolBridge.respond(id, "", "我先把话说完", "test", archiveId = archive.id,
                        sceneContext = "正在电话中", phoneCallId = callId,
                        onLive2DAction = { actions.incrementAndGet() },
                        onLive2DStatus = { statuses.incrementAndGet() })
                }
                try {
                    withContext(Dispatchers.IO) {
                        repeat(blockedRequest) { checkNotNull(server.takeRequest(10, TimeUnit.SECONDS)) }
                    }
                    assertTrue(CharacterLifeStore.setRelationshipAgreement(id, "user", "约定", newAgreement))
                } finally {
                    release.countDown()
                }
                val stale = pending.await()
                assertTrue(stale.isFailure)
                assertTrue(stale.exceptionOrNull()?.message.orEmpty().contains("设定或关系已更新"))
                assertEquals(0, actions.get())
                assertEquals(0, statuses.get())
                assertEquals(blockedRequest, server.requestCount)
            } finally {
                release.countDown()
                server.shutdown()
                com.jiacimu.lulu.Live2DVisualActionBus.stageClosed(id, callId)
                models.edit().remove("model:$id").commit()
                settings.parentFile?.deleteRecursively()
            }
        }
    }

    @Test(timeout = 60_000) fun phoneStepsStopAfterRelationshipEditButKeepCompletedToolReceipt() =
        verifyInterruptedPhoneSteps(cancelTurn = false)

    @Test(timeout = 60_000) fun phoneStepsStopAfterCancellationButKeepCompletedToolReceipt() =
        verifyInterruptedPhoneSteps(cancelTurn = true)

    private fun verifyInterruptedPhoneSteps(cancelTurn: Boolean) = runBlocking {
        val context = RuntimeEnvironment.getApplication() as Context
        LuluRepositories.initialize(context)
        SharedExperienceTimeline.initialize(context)
        CharacterLifeStore.initialize(context)
        CharacterInnerLifeStore.initialize(context)
        LuluAiServices.initialize(context)
        LuluDeviceToolBridge.initialize(context)
        MomentsStore.initialize(context)

        val role = MigratedDomainStores.characters.create("通话多步样本", "尊重当下的相处约定")
        val id = role.characterId
        assertTrue(CharacterLifeStore.setRelationshipAgreement(id, "user", "约定", "沿用旧称呼"))
        val staleStatus = "等我把剩余动作也做完"
        val direction = JSONObject().put("tool", "consider_directions")
            .put("args", JSONObject().put("state", "先核对这个想法")
                .put("questions", JSONObject().put("continue", JSONObject().put("instructions", "继续吗？"))))
        val post = JSONObject().put("tool", "publish_moment")
            .put("args", JSONObject().put("text", "旧决定中尚未发出的动态"))
        val plan = JSONObject().put("action", "silent").put("reason", "我先自己想一想")
            .put("statusText", staleStatus).put("steps", JSONArray().put(direction).put(post))
        val nextSpeech = "我把刚才得到的参考和你现在说的分开看。"
        val decisionRequests = AtomicInteger()
        val release = CountDownLatch(1)
        val server = MockWebServer()
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse =
                when (request.path) {
                    "/v1/systemone" -> if (release.await(15, TimeUnit.SECONDS))
                        MockResponse().setHeader("Content-Type", "application/json").setBody(
                            """{"model":"jev-test","usage":{"input_tokens":10,"output_tokens":2},"answers":{"continue":{"type":"noul","noul":0.6}}}""")
                    else MockResponse().setResponseCode(500)
                    else -> MockResponse().setHeader("Content-Type", "application/json")
                        .setBody(JSONObject().put("choices", JSONArray().put(JSONObject()
                            .put("message", JSONObject().put("content",
                                if (decisionRequests.incrementAndGet() == 1) plan.toString()
                                else JSONObject().put("action", "reply").put("text", nextSpeech).toString()))
                            .put("finish_reason", "stop"))).toString())
                }
        }
        server.start()
        val scope = com.jiacimu.lulu.ai.ScopedModelSelections
        try {
            val config = LuluAiServices.connectionStore.saveConfiguration(null, "test",
                server.url("/").toString(), "test-only")
            val chat = LuluAiServices.connectionStore.addArchive(config.id, "test")
            val reasoning = LuluAiServices.connectionStore.addArchive(config.id, "jev-test")
            scope.select(com.jiacimu.lulu.ai.ScopedModelSelections.JEV, reasoning.id)
            val observation = "call-quiet-edit-during-tool"
            SharedExperienceTimeline.record(observation, id, "通话观察", "通话",
                "电话接通，暂时无人发言", source = "call-quiet", evidenceKind = EventEvidenceKind.Observation)
            var returned = false
            val pending = async {
                val response = LuluDeviceToolBridge.respond(id, "", "", "test", archiveId = chat.id,
                    sceneContext = "一对一电话仍然接通，用户没有新增发言",
                    silenceObservationId = observation)
                returned = true
                response
            }
            try {
                val toolRequest = withContext(Dispatchers.IO) {
                    checkNotNull(server.takeRequest(10, TimeUnit.SECONDS))
                    checkNotNull(server.takeRequest(10, TimeUnit.SECONDS))
                }
                assertEquals("/v1/systemone", toolRequest.path)
                if (cancelTurn) pending.cancel()
                else assertTrue(CharacterLifeStore.setRelationshipAgreement(
                    id, "user", "约定", "除非我先提起，否则请用新的称呼"))
            } finally {
                release.countDown()
            }
            if (cancelTurn) {
                pending.join()
                assertTrue(pending.isCancelled)
                assertFalse(returned)
            } else {
                val stale = pending.await()
                assertTrue(stale.exceptionOrNull()?.message.orEmpty().contains("旧决定未继续交付"))
            }
            val receipts = SharedExperienceTimeline.all(id)
                .filter { it.source == "tool-router" && it.evidenceKind == EventEvidenceKind.ToolResult }
            val execution = JSONObject(receipts.single().content)
            assertEquals("consider_directions", execution.getString("tool"))
            assertTrue(ToolExecutionStatus.completed(JSONObject(execution.getString("result"))))
            assertFalse(MomentsStore.posts.value.any { it.authorCharacterId == id })
            assertNotEquals(staleStatus, CompanionPresenceStore.current(id)?.statusText)
            val decisions = checkNotNull(CharacterInnerLifeStore.snapshot(id).optJSONArray("decisions"))
            val recorded = (0 until decisions.length()).map { decisions.getJSONObject(it) }
            val batch = recorded.single { it.optString("executionRequestId") == "reply-side-$observation" }
            assertEquals("reported", batch.getString("executionState"))
            assertFalse(batch.getBoolean("succeeded"))
            val outcome = batch.getString("outcome")
            assertTrue(outcome.contains("第2步及后续步骤未执行"))
            if (cancelTurn) assertTrue(outcome.contains("旧通话轮次已取消"))
            assertTrue(recorded.none { it.optString("id") == "$observation:silent" })
            assertEquals(2, server.requestCount)
            if (cancelTurn) {
                val receipt = receipts.single()
                assertEquals(receipt, com.jiacimu.lulu.CallExternalResultPolicy.pendingEvidence(context, id, receipt.id))
                assertNull(com.jiacimu.lulu.CallExternalResultPolicy.pendingEvidence(context, "another-role", receipt.id))
                val userText = "刚才你查到什么了？"
                val next = LuluDeviceToolBridge.respond(id, "", userText, "test", archiveId = chat.id,
                    sceneContext = "正在一对一电话中").getOrThrow()
                val nextRequest = withContext(Dispatchers.IO) {
                    checkNotNull(server.takeRequest(10, TimeUnit.SECONDS)).body.readUtf8()
                }
                assertTrue(nextRequest.contains(userText))
                assertTrue(nextRequest.contains("evidenceId=${receipt.id}"))
                assertTrue(nextRequest.contains("本人真实调用Jev取得模型参考"))
                assertTrue(nextRequest.contains("0.6"))
                assertEquals(nextSpeech, next.text)
                assertTrue(PerceptionStimulusLedger.hasSeen(context, id, receipt.id, receipt.revision))
                assertNull(com.jiacimu.lulu.CallExternalResultPolicy.pendingEvidence(context, id, receipt.id))
                assertEquals(3, server.requestCount)
                assertEquals(listOf(receipt), SharedExperienceTimeline.all(id)
                    .filter { it.source == "tool-router" && it.evidenceKind == EventEvidenceKind.ToolResult })
                assertFalse(MomentsStore.posts.value.any { it.authorCharacterId == id })
                SharedExperienceTimeline.deleteEvent(receipt.id)
                assertTrue(SharedExperienceTimeline.eventsByIds(id, listOf(receipt.id)).isEmpty())
                assertNull(com.jiacimu.lulu.CallExternalResultPolicy.pendingEvidence(context, id, receipt.id))
            }
        } finally {
            release.countDown()
            scope.clear(com.jiacimu.lulu.ai.ScopedModelSelections.JEV)
            server.shutdown()
        }
    }

    @Test(timeout = 60_000) fun phoneObservationRejectsStaleReceiptDecisionWithoutRepeatingItsTool() = runBlocking {
        val context = RuntimeEnvironment.getApplication() as Context
        LuluRepositories.initialize(context)
        SharedExperienceTimeline.initialize(context)
        CharacterLifeStore.initialize(context)
        LuluAiServices.initialize(context)
        LuluDeviceToolBridge.initialize(context)
        MomentsStore.initialize(context)

        val role = MigratedDomainStores.characters.create("电话回执观察样本", "记得自己的约定")
        val id = role.characterId
        val oldAgreement = "先沿用旧的称呼"
        val newAgreement = "除非我主动提起，否则不要使用旧称呼"
        assertTrue(CharacterLifeStore.setRelationshipAgreement(id, "user", "约定", oldAgreement))
        val originalPost = "这是原工具实际发布的动态"
        val receiptResult = JSONObject(ToolRouter.execute(context, id, "publish_moment",
            JSONObject().put("text", originalPost), requestId = "call-observation-source"))
        assertTrue(ToolExecutionStatus.completed(receiptResult))
        fun receipts() = SharedExperienceTimeline.all(id)
            .filter { it.source == "tool-router" && it.evidenceKind == EventEvidenceKind.ToolResult }
        fun posts() = MomentsStore.posts.value.filter { it.authorCharacterId == id }.map { it.content }
        val source = receipts().single()

        val release = CountDownLatch(1)
        val requests = AtomicInteger()
        val hangups = AtomicInteger()
        val staleStatus = "旧轮已经向用户说明"
        val decision = JSONObject().put("action", "reply").put("text", "我现在就和你说。")
            .put("statusText", staleStatus).put("endCall", true)
            .put("steps", JSONArray().put(JSONObject().put("tool", "publish_moment")
                .put("args", JSONObject().put("text", "旧决定准备发布的第二条动态"))))
        fun response(content: String) = MockResponse().setHeader("Content-Type", "application/json")
            .setBody(JSONObject().put("choices", JSONArray().put(JSONObject()
                .put("message", JSONObject().put("content", content))
                .put("finish_reason", "stop"))).toString())
        val server = MockWebServer()
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse = when (requests.incrementAndGet()) {
                1 -> if (release.await(15, TimeUnit.SECONDS)) response(decision.toString())
                    else MockResponse().setResponseCode(500)
                2 -> response("""{"action":"silent","reason":"按这次相处约定安静待一会儿"}""")
                3 -> response("""{"action":"reply","text":"我听到了。"}""")
                else -> MockResponse().setResponseCode(500)
            }
        }
        server.start()
        try {
            val config = LuluAiServices.connectionStore.saveConfiguration(null, "test",
                server.url("/").toString(), "test-only")
            val archive = LuluAiServices.connectionStore.addArchive(config.id, "test")
            val pending = async {
                LuluDeviceToolBridge.respond(id, "", "", "test", archiveId = archive.id,
                    sceneContext = "一对一电话仍然接通，用户没有新增发言",
                    silenceObservationId = source.id,
                    onCharacterHangup = { hangups.incrementAndGet() })
            }
            try {
                val request = withContext(Dispatchers.IO) {
                    checkNotNull(server.takeRequest(10, TimeUnit.SECONDS)).body.readUtf8()
                }
                assertTrue(request.contains(oldAgreement))
                assertTrue(request.contains("call-observation-source"))
                assertTrue(CharacterLifeStore.setRelationshipAgreement(id, "user", "约定", newAgreement))
            } finally {
                release.countDown()
            }
            val stale = pending.await()
            assertTrue(stale.exceptionOrNull()?.message.orEmpty().contains("旧决定未提交"))
            assertNotEquals(staleStatus, CompanionPresenceStore.current(id)?.statusText)
            assertEquals(0, hangups.get())
            assertEquals(1, server.requestCount)
            assertEquals(listOf(originalPost), posts())
            assertEquals(listOf(source), receipts())
            assertFalse(PerceptionStimulusLedger.hasSeen(context, id, source.id, source.revision))

            val next = LuluDeviceToolBridge.respond(id, "", "", "test", archiveId = archive.id,
                sceneContext = "一对一电话仍然接通，用户没有新增发言",
                silenceObservationId = source.id).getOrThrow()
            val nextRequest = withContext(Dispatchers.IO) {
                checkNotNull(server.takeRequest(10, TimeUnit.SECONDS)).body.readUtf8()
            }
            assertTrue(nextRequest.contains(newAgreement))
            assertTrue(nextRequest.contains("evidenceId=${source.id}"))
            assertFalse(nextRequest.contains("本轮用户原话发生时间"))
            assertTrue(PerceptionStimulusLedger.hasSeen(context, id, source.id, source.revision))
            assertNull(com.jiacimu.lulu.CallExternalResultPolicy.pendingEvidence(context, id, source.id))
            assertFalse(nextRequest.contains(oldAgreement))
            assertEquals(CharacterDecisionProtocol.SILENT, next.disposition)
            assertTrue(next.text.isBlank())
            assertEquals(listOf(source), receipts())
            assertEquals(2, server.requestCount)

            val ordinary = LuluDeviceToolBridge.respond(id, "", "我刚刚说话了", "test",
                archiveId = archive.id, sceneContext = "一对一电话仍然接通").getOrThrow()
            val ordinaryRequest = withContext(Dispatchers.IO) {
                checkNotNull(server.takeRequest(10, TimeUnit.SECONDS)).body.readUtf8()
            }
            assertTrue(ordinaryRequest.contains("我刚刚说话了"))
            assertFalse(ordinaryRequest.contains("- evidenceId=${source.id}"))
            assertEquals("我听到了。", ordinary.text)
            assertEquals(listOf(originalPost), posts())
            assertEquals(listOf(source), receipts())
            assertEquals(3, server.requestCount)
        } finally {
            release.countDown()
            server.shutdown()
        }
    }

    @Test(timeout = 60_000) fun ordinaryPhoneReplyRejectsEditsDuringSpeechOrMotionRepair() = runBlocking {
        val context = RuntimeEnvironment.getApplication() as Context
        LuluRepositories.initialize(context)
        SharedExperienceTimeline.initialize(context)
        CharacterLifeStore.initialize(context)
        LuluAiServices.initialize(context)
        LuluDeviceToolBridge.initialize(context)
        MomentsStore.initialize(context)

        for (repairSpeech in listOf(true, false)) {
            val role = MigratedDomainStores.characters.create("电话普通回复样本-$repairSpeech", "记得自己的约定")
            val id = role.characterId
            val callId = "ordinary-phone-reply-edit-$id"
            assertTrue(CharacterLifeStore.setRelationshipAgreement(id, "user", "约定", "沿用原来的称呼"))

            val relative = "models/test-ordinary-reply-$id/avatar.model3.json"
            val settings = java.io.File(com.jiacimu.lulu.Live2DAssets.base(context), relative)
            settings.parentFile?.mkdirs()
            settings.writeText("""{"FileReferences":{"Moc":"avatar.moc3","Textures":["skin.png"],"Motions":{"Gesture":[{"File":"wave.motion3.json"}]}}}""")
            val models = context.getSharedPreferences("lulu_live2d_models_v1", 0)
            models.edit().putString("model:$id", relative).commit()
            com.jiacimu.lulu.Live2DVisualActionBus.stageReady(id, callId)

            val post = "刚才的事留了一条动态"
            val spoken = "我还要去核对一下。"
            val staleStatus = "已经向用户交代"
            val userText = if (repairSpeech) "那你也没十点叫我啊" else "后来核对了吗？"
            val draft = if (repairSpeech) "憋了四个小时，就憋出这一句倒打一耙？" else spoken
            val decision = JSONObject().put("action", "reply").put("text", draft)
                .put("statusText", staleStatus).put("endCall", true)
                .put("steps", JSONArray().put(JSONObject().put("tool", "publish_moment")
                    .put("args", JSONObject().put("text", post))))
            val release = CountDownLatch(1)
            val requests = AtomicInteger()
            val actions = AtomicInteger()
            val statuses = AtomicInteger()
            val hangups = AtomicInteger()
            fun receipts() = SharedExperienceTimeline.all(id)
                .filter { it.source == "tool-router" && it.evidenceKind == EventEvidenceKind.ToolResult }
            fun posts() = MomentsStore.posts.value.filter { it.authorCharacterId == id }.map { it.content }
            fun response(content: String) = MockResponse().setHeader("Content-Type", "application/json")
                .setBody(JSONObject().put("choices", JSONArray().put(JSONObject()
                    .put("message", JSONObject().put("content", content))
                    .put("finish_reason", "stop"))).toString())
            val server = MockWebServer()
            server.dispatcher = object : Dispatcher() {
                override fun dispatch(request: RecordedRequest): MockResponse {
                    return when (requests.incrementAndGet()) {
                        1 -> response(decision.toString())
                        2 -> {
                            if (!release.await(15, TimeUnit.SECONDS)) return MockResponse().setResponseCode(500)
                            if (repairSpeech) response(spoken) else response(
                                """{"live2d":{"speech":[{"id":"s1","text":"$spoken","motion":{"group":"Gesture","index":0},"transition":"blend"}]}}""")
                        }
                        else -> MockResponse().setResponseCode(500)
                    }
                }
            }
            server.start()
            try {
                val config = LuluAiServices.connectionStore.saveConfiguration(null, "test",
                    server.url("/").toString(), "test-only")
                val archive = LuluAiServices.connectionStore.addArchive(config.id, "test")
                val pending = async {
                    LuluDeviceToolBridge.respond(id, "", userText, "test", archiveId = archive.id,
                        sceneContext = "正在电话中", phoneCallId = callId,
                        onCharacterHangup = { hangups.incrementAndGet() },
                        onLive2DAction = { actions.incrementAndGet() },
                        onLive2DStatus = { statuses.incrementAndGet() })
                }
                val receiptBefore = try {
                    val repairRequest = withContext(Dispatchers.IO) {
                        checkNotNull(server.takeRequest(10, TimeUnit.SECONDS))
                        checkNotNull(server.takeRequest(10, TimeUnit.SECONDS)).body.readUtf8()
                    }
                    assertTrue(repairRequest.contains(if (repairSpeech) draft else "spokenText"))
                    val receipt = receipts().single()
                    val result = JSONObject(receipt.content).getString("result")
                    assertTrue(ToolExecutionStatus.completed(JSONObject(result)))
                    assertEquals(listOf(post), posts())
                    assertTrue(CharacterLifeStore.setRelationshipAgreement(
                        id, "user", "约定", "除非我主动提起，否则别用旧称呼"))
                    receipt
                } finally {
                    release.countDown()
                }
                val stale = pending.await()
                assertTrue(stale.exceptionOrNull()?.message.orEmpty().contains("旧台词未交付"))
                assertNotEquals(staleStatus, CompanionPresenceStore.current(id)?.statusText)
                assertEquals(listOf(receiptBefore), receipts())
                assertEquals(listOf(post), posts())
                assertEquals(0, actions.get())
                assertEquals(0, statuses.get())
                assertEquals(0, hangups.get())
                assertEquals(2, server.requestCount)
            } finally {
                release.countDown()
                server.shutdown()
                com.jiacimu.lulu.Live2DVisualActionBus.stageClosed(id, callId)
                models.edit().remove("model:$id").commit()
                settings.parentFile?.deleteRecursively()
            }
        }
    }

    @Test(timeout = 60_000) fun phoneToolReceiptSurvivesEditsBeforeResultSpeechOrMotion() = runBlocking {
        val context = RuntimeEnvironment.getApplication() as Context
        LuluRepositories.initialize(context)
        SharedExperienceTimeline.initialize(context)
        CharacterLifeStore.initialize(context)
        LuluAiServices.initialize(context)
        LuluDeviceToolBridge.initialize(context)
        MomentsStore.initialize(context)

        for (blockedRequest in listOf(2, 3)) {
            val role = MigratedDomainStores.characters.create("电话回执样本-$blockedRequest", "记得自己的约定")
            val id = role.characterId
            val callId = "phone-tool-result-edit-$id"
            val oldAgreement = "先沿用原来的称呼"
            val newAgreement = "除非我主动提起，否则别用原来的称呼"
            assertTrue(CharacterLifeStore.setRelationshipAgreement(id, "user", "约定", oldAgreement))

            val relative = "models/test-tool-result-$id/avatar.model3.json"
            val settings = java.io.File(com.jiacimu.lulu.Live2DAssets.base(context), relative)
            settings.parentFile?.mkdirs()
            settings.writeText("""{"FileReferences":{"Moc":"avatar.moc3","Textures":["skin.png"],"Motions":{"Gesture":[{"File":"wave.motion3.json"}]}}}""")
            val models = context.getSharedPreferences("lulu_live2d_models_v1", 0)
            models.edit().putString("model:$id", relative).commit()
            com.jiacimu.lulu.Live2DVisualActionBus.stageReady(id, callId)

            val post = "这是实际发出的动态"
            val spoken = "已经发了，那我先挂了。"
            val release = CountDownLatch(1)
            val requests = AtomicInteger()
            val actions = AtomicInteger()
            val statuses = AtomicInteger()
            val hangups = AtomicInteger()
            fun receipts() = SharedExperienceTimeline.all(id)
                .filter { it.source == "tool-router" && it.evidenceKind == EventEvidenceKind.ToolResult }
            fun posts() = MomentsStore.posts.value.filter { it.authorCharacterId == id }.map { it.content }
            fun response(content: String) = MockResponse().setHeader("Content-Type", "application/json")
                .setBody(JSONObject().put("choices", JSONArray().put(JSONObject()
                    .put("message", JSONObject().put("content", content))
                    .put("finish_reason", "stop"))).toString())
            val server = MockWebServer()
            server.dispatcher = object : Dispatcher() {
                override fun dispatch(request: RecordedRequest): MockResponse {
                    val index = requests.incrementAndGet()
                    if (index == blockedRequest && !release.await(15, TimeUnit.SECONDS))
                        return MockResponse().setResponseCode(500)
                    return when (index) {
                        1 -> response("""{"action":"tool","tool":"publish_moment","args":{"text":"$post"},"reason":"想把刚才的事留一下"}""")
                        2 -> response("""{"action":"reply","text":"$spoken","endCall":true,"statusText":"已经向用户道别"}""")
                        3 -> response("""{"live2d":{"speech":[{"id":"s1","text":"$spoken","motion":{"group":"Gesture","index":0},"transition":"blend"}]}}""")
                        else -> MockResponse().setResponseCode(500)
                    }
                }
            }
            server.start()
            try {
                val config = LuluAiServices.connectionStore.saveConfiguration(null, "test",
                    server.url("/").toString(), "test-only")
                val archive = LuluAiServices.connectionStore.addArchive(config.id, "test")
                val pending = async {
                    LuluDeviceToolBridge.respond(id, "", "把这件事发个动态吧", "test",
                        archiveId = archive.id, sceneContext = "正在电话中", phoneCallId = callId,
                        onCharacterHangup = { hangups.incrementAndGet() },
                        onLive2DAction = { actions.incrementAndGet() },
                        onLive2DStatus = { statuses.incrementAndGet() })
                }
                val receiptBefore = try {
                    val resultRequest = withContext(Dispatchers.IO) {
                        checkNotNull(server.takeRequest(10, TimeUnit.SECONDS))
                        val receiptRequest = checkNotNull(server.takeRequest(10, TimeUnit.SECONDS))
                        if (blockedRequest == 3) checkNotNull(server.takeRequest(10, TimeUnit.SECONDS))
                        receiptRequest.body.readUtf8()
                    }
                    val receipt = receipts().single()
                    val execution = JSONObject(receipt.content)
                    assertEquals("publish_moment", execution.getString("tool"))
                    assertTrue(ToolExecutionStatus.completed(JSONObject(execution.getString("result"))))
                    assertTrue(resultRequest.contains(receipt.id))
                    assertEquals(listOf(post), posts())
                    assertTrue(CharacterLifeStore.setRelationshipAgreement(id, "user", "约定", newAgreement))
                    receipt
                } finally {
                    release.countDown()
                }
                val stale = pending.await()
                assertTrue(stale.isFailure)
                assertTrue(stale.exceptionOrNull()?.message.orEmpty().contains("工具结果已保留"))
                assertEquals(listOf(receiptBefore), receipts())
                assertEquals(listOf(post), posts())
                assertEquals(0, actions.get())
                assertEquals(0, statuses.get())
                assertEquals(0, hangups.get())
                assertEquals(blockedRequest, server.requestCount)
            } finally {
                release.countDown()
                server.shutdown()
                com.jiacimu.lulu.Live2DVisualActionBus.stageClosed(id, callId)
                models.edit().remove("model:$id").commit()
                settings.parentFile?.deleteRecursively()
            }
        }
    }

    @Test(timeout = 60_000) fun phoneAndPrivateChatBothRejectAnUnownedConcernInAScheduleStep() = runBlocking {
        val context = RuntimeEnvironment.getApplication() as Context
        LuluRepositories.initialize(context)
        SharedExperienceTimeline.initialize(context)
        LuluAiServices.initialize(context)
        LuluDeviceToolBridge.initialize(context)
        val role = MigratedDomainStores.characters.create("日程归属交接样本", "按自己确认的事实说话")
        val actor = role.characterId
        val chat = MigratedDomainStores.chat.ensureConversation(actor, "test")
        val heard = MigratedDomainStores.chat.sendUserMessage(chat.id, "后来有进展吗？")
        val typed = MigratedDomainStores.chat.sendUserMessage(chat.id, "能在这里也说一下吗？")
        val unknownConcern = "unowned-concern"
        val condition = "如果无法关联原心事，就不要说已经安排。"
        val step = JSONObject().put("tool", "plan_personal_activity")
            .put("concernId", unknownConcern)
            .put("args", JSONObject().put("title", "稍后核对进展").put("intent", "继续查清原来的问题")
                .put("dueAt", java.time.Instant.now().plusSeconds(3600).toString()))
        val decision = JSONObject().put("action", "reply").put("speechIntent", condition)
            .put("text", "我先核对一下。")
            .put("steps", JSONArray().put(step)).toString()
        fun response(content: String) = MockResponse().setHeader("Content-Type", "application/json")
            .setBody(JSONObject().put("choices", JSONArray().put(JSONObject()
                .put("message", JSONObject().put("content", content))
                .put("finish_reason", "stop"))).toString())
        val server = MockWebServer()
        server.enqueue(response(decision))
        server.enqueue(response(decision))
        server.enqueue(response("""{"action":"reply","bubbles":[{"text":"我先核对一下。"}]}"""))
        server.start()
        try {
            val config = LuluAiServices.connectionStore.saveConfiguration(null, "test",
                server.url("/").toString(), "test-only")
            val archive = LuluAiServices.connectionStore.addArchive(config.id, "test")
            val phone = LuluDeviceToolBridge.respond(actor, "", heard.content, "test",
                archiveId = archive.id, sceneContext = "正在电话中", phoneCallId = "concern-step-call",
                userEvidenceIds = listOf(heard.id)).getOrThrow()
            assertEquals("我先核对一下。", phone.text)

            val privateReply = LuluDeviceToolBridge.respond(actor, "", typed.content, "test",
                archiveId = archive.id, userEvidenceIds = listOf(typed.id)).getOrThrow()
            assertEquals("我先核对一下。", privateReply.text)
            val deferred = JSONArray(checkNotNull(privateReply.deferredActions))
            val privateRequest = "private-concern-step-${typed.id}"
            val report = OrderedCharacterActions.execute(context, actor, deferred,
                requestId = privateRequest, userEvidenceIds = setOf(typed.id))
            assertEquals(0, report.completedCount)
            assertTrue(RoleDayScheduleStore.plans(context, actor).isEmpty())

            repeat(2) { checkNotNull(server.takeRequest(5, TimeUnit.SECONDS)) }
            val renderingRequest = checkNotNull(server.takeRequest(5, TimeUnit.SECONDS))
            assertTrue(renderingRequest.body.readUtf8().contains(condition))
            val receipts = SharedExperienceTimeline.all(actor).filter { it.source == "tool-router" }
                .map { JSONObject(it.content) }.filter {
                    it.optString("requestId") == "reply-side-${heard.id}-0" ||
                        it.optString("requestId").startsWith(privateRequest)
                }
            assertEquals(2, receipts.size)
            for (receipt in receipts) {
                assertEquals(unknownConcern, receipt.optString("concernId"))
                val outcome = JSONObject(receipt.getString("result"))
                assertFalse(outcome.optBoolean("success"))
                assertTrue(outcome.toString(), outcome.toString().contains("关联心事必须是本人"))
            }
            assertEquals(3, server.requestCount)
        } finally {
            server.shutdown()
        }
    }

    @Test(timeout = 60_000) fun phoneScheduleReplayKeepsTheNewerPrivateChatOutcomeOnTheSameConcern() = runBlocking {
        val context = RuntimeEnvironment.getApplication() as Context
        LuluRepositories.initialize(context)
        SharedExperienceTimeline.initialize(context)
        CharacterInnerLifeStore.initialize(context)
        LuluAiServices.initialize(context)
        LuluDeviceToolBridge.initialize(context)
        val actor = MigratedDomainStores.characters.create("心事续接样本", "在意自己查到的事实").characterId
        val chat = MigratedDomainStores.chat.ensureConversation(actor, "test")
        val source = MigratedDomainStores.chat.sendUserMessage(chat.id, "我们可以之后再看看这份材料")
        val heard = MigratedDomainStores.chat.sendUserMessage(chat.id, "电话里先说一下你的安排")
        val typed = MigratedDomainStores.chat.sendUserMessage(chat.id, "再确认一下下一步")
        CharacterInnerLifeStore.recordCausalTransition(
            actor, source.id,
            appraisal = JSONObject().put("pendingConcerns", JSONArray().put(JSONObject()
                .put("status", "open").put("topicKey", "compare-material")
                .put("focus", "材料里的说法是否可靠").put("whyItMatters", "想弄清真实依据"))),
            innerLife = null, innerThoughtBasis = null, selectedAction = "reply",
            innerThought = "", reason = "",
        )
        fun concern() = CharacterInnerLifeStore.snapshot(actor).getJSONArray("openConcerns").getJSONObject(0)
        val concernId = concern().getString("id")
        val now = java.time.Instant.now()
        val intent = "条件还没核实，不能说已经查完。"
        fun decision(title: String, delay: Long): String {
            val step = JSONObject().put("tool", "plan_personal_activity").put("concernId", concernId)
                .put("args", JSONObject().put("title", title).put("intent", "继续核实原来的材料")
                    .put("dueAt", now.plusSeconds(delay).toString()))
            return JSONObject().put("action", "reply").put("speechIntent", intent)
                .put("text", "我还要确认一下。").put("steps", JSONArray().put(step)).toString()
        }
        val phoneDecision = decision("稍后复核原文", 3600)
        val chatDecision = decision("晚些对照第二份资料", 7200)
        fun response(content: String) = MockResponse().setHeader("Content-Type", "application/json")
            .setBody(JSONObject().put("choices", JSONArray().put(JSONObject()
                .put("message", JSONObject().put("content", content))
                .put("finish_reason", "stop"))).toString())
        val server = MockWebServer()
        server.enqueue(response(phoneDecision))
        server.enqueue(response(chatDecision))
        server.enqueue(response("""{"action":"reply","bubbles":[{"text":"我还要确认一下。"}]}"""))
        server.enqueue(response(phoneDecision))
        server.start()
        try {
            val config = LuluAiServices.connectionStore.saveConfiguration(null, "test",
                server.url("/").toString(), "test-only")
            val archive = LuluAiServices.connectionStore.addArchive(config.id, "test")
            suspend fun phone() = LuluDeviceToolBridge.respond(actor, "", heard.content, "test",
                archiveId = archive.id, sceneContext = "正在电话中", phoneCallId = "concern-replay-call",
                userEvidenceIds = listOf(heard.id)).getOrThrow()
            val phoneReceiptId = "tool-$actor-reply-side-${heard.id}-0"
            assertEquals("我还要确认一下。", phone().text)
            val firstPlan = RoleDayScheduleStore.plans(context, actor).single()
            assertEquals(concernId, firstPlan.concernId)
            assertEquals(phoneReceiptId, concern().getJSONObject("lastOutcome").getString("id"))

            val chatReply = LuluDeviceToolBridge.respond(actor, "", typed.content, "test",
                archiveId = archive.id, userEvidenceIds = listOf(typed.id)).getOrThrow()
            assertEquals("我还要确认一下。", chatReply.text)
            val requestId = "chat-concern-replay-${typed.id}"
            val deferred = JSONArray(checkNotNull(chatReply.deferredActions))
            val report = OrderedCharacterActions.execute(context, actor, deferred,
                requestId = requestId, userEvidenceIds = setOf(typed.id))
            assertEquals(report.notes.toString(), 1, report.completedCount)
            val chatReceiptId = "tool-$actor-$requestId:step:0"
            val latest = concern().getJSONObject("lastOutcome")
            assertEquals(chatReceiptId, latest.getString("id"))
            assertTrue(latest.getBoolean("success"))
            val plans = RoleDayScheduleStore.plans(context, actor)
            assertEquals(2, plans.size)
            assertTrue(plans.all { it.concernId == concernId })

            assertEquals("我还要确认一下。", phone().text)
            assertEquals(plans, RoleDayScheduleStore.plans(context, actor))
            assertEquals(latest.toString(), concern().getJSONObject("lastOutcome").toString())
            val receipts = SharedExperienceTimeline.eventsByIds(actor, listOf(phoneReceiptId, chatReceiptId))
            assertEquals(setOf(phoneReceiptId, chatReceiptId), receipts.map { it.id }.toSet())
            assertTrue(receipts.all {
                val payload = JSONObject(it.content)
                payload.optString("concernId") == concernId &&
                    ToolExecutionStatus.completed(JSONObject(payload.getString("result")))
            })
            assertEquals(4, server.requestCount)
        } finally {
            server.shutdown()
        }
    }

    @Test(timeout = 60_000) fun groupDeferredStepsKeepTheSpeakersEvidenceAndActualDependencyResults() = runBlocking {
        val context = RuntimeEnvironment.getApplication() as Context
        LuluRepositories.initialize(context)
        SharedExperienceTimeline.initialize(context)
        LuluAiServices.initialize(context)
        LuluDeviceToolBridge.initialize(context)
        MomentsStore.initialize(context)
        val actor = MigratedDomainStores.characters.create("群聊交接甲", "只说自己确认过的进展")
        val peer = MigratedDomainStores.characters.create("群聊交接乙", "先听别人说完")
        val group = MigratedDomainStores.chat.createGroupConversation(
            "条件步骤", listOf(actor.characterId, peer.characterId))
        val source = MigratedDomainStores.chat.sendUserMessage(group.id, "确认之前别说已经完成")
        val actorEvidenceId = "${source.id}:group:${actor.characterId}"
        val peerEvidenceId = "${source.id}:group:${peer.characterId}"
        val declined = JSONObject().put("tool", "favorite_user_message")
            .put("args", JSONObject().put("messageId", "missing-group-message"))
        val dependentText = "此前成功才允许发布"
        val independentText = "另一条独立记录"
        val dependent = JSONObject().put("tool", "publish_moment")
            .put("requiresPreviousSuccess", true).put("args", JSONObject().put("text", dependentText))
        val independent = JSONObject().put("tool", "publish_moment")
            .put("curiosity", JSONObject().put("question", "还想核对原资料的来源"))
            .put("args", JSONObject().put("text", independentText))
        val words = "还没有确认结果。"
        val director = JSONObject().put("beats", JSONArray().put(
            JSONObject().put("speakerId", actor.characterId)))
        val decision = JSONObject().put("action", "reply").put("text", words)
            .put("steps", JSONArray().put(declined).put(dependent).put(independent))
        fun response(body: JSONObject) = MockResponse().setHeader("Content-Type", "application/json")
            .setBody(JSONObject().put("choices", JSONArray().put(JSONObject()
                .put("message", JSONObject().put("content", body.toString()))
                .put("finish_reason", "stop"))).toString())
        val server = MockWebServer()
        server.enqueue(response(director))
        server.enqueue(response(decision))
        server.start()
        try {
            val config = LuluAiServices.connectionStore.saveConfiguration(null, "test",
                server.url("/").toString(), "test-only")
            val archive = LuluAiServices.connectionStore.addArchive(config.id, "test")
            LuluAiServices.connectionStore.selectArchive(archive.id, com.jiacimu.lulu.ai.ModelUsage.Chat)
            val errors = mutableListOf<String>()
            com.jiacimu.lulu.runGroupReplies(group.id, checkNotNull(group.groupChat), source.content,
                "", "test", archive.id, mapOf(actor.characterId to actor.displayName,
                    peer.characterId to peer.displayName), onError = { errors.add(it) })
            assertTrue(errors.toString(), errors.isEmpty())
            assertTrue(MigratedDomainStores.chat.messages(group.id).value.any {
                it.authorCharacterId == actor.characterId && it.content == words
            })
            val prefix = "group-${group.id}-${actor.characterId}-${source.id}:step:"
            fun receipts(roleId: String) = SharedExperienceTimeline.all(roleId)
                .filter { it.source == "tool-router" }
                .map { JSONObject(it.content) }
                .filter { it.optString("requestId").startsWith(prefix) }
                .associateBy { it.getString("requestId") }
            val actual = receipts(actor.characterId)
            assertEquals(setOf(prefix + "0", prefix + "2"), actual.keys)
            val failure = actual.getValue(prefix + "0")
            val success = actual.getValue(prefix + "2")
            assertFalse(JSONObject(failure.getString("result")).optBoolean("success"))
            assertTrue(ToolExecutionStatus.completed(JSONObject(success.getString("result"))))
            assertEquals(actorEvidenceId, failure.getString("causeEvidenceId"))
            assertEquals(actorEvidenceId, success.getString("causeEvidenceId"))
            assertEquals(actorEvidenceId, success.getJSONObject("curiosity").getString("sourceEvidenceId"))
            assertEquals(listOf(independentText), MomentsStore.posts.value
                .filter { it.authorCharacterId == actor.characterId }.map { it.content })
            assertTrue(SharedExperienceTimeline.eventsByIds(peer.characterId, listOf(peerEvidenceId)).isNotEmpty())
            assertTrue(receipts(peer.characterId).isEmpty())
            assertEquals(2, server.requestCount)
        } finally {
            server.shutdown()
        }
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
