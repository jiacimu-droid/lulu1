package com.jiacimu.lulu.data

import android.content.Context
import com.jiacimu.lulu.LuluRepositories
import com.jiacimu.lulu.ai.CompanionContextMode
import com.jiacimu.lulu.ai.LuluAiServices
import com.jiacimu.lulu.ai.ModelConnection
import com.jiacimu.lulu.core.MemoryEntry
import com.jiacimu.lulu.core.MemoryKind
import com.jiacimu.lulu.games.CampaignHost
import com.jiacimu.lulu.system.LuluDeviceToolBridge
import com.jiacimu.lulu.core.MemoryTier
import kotlinx.coroutines.runBlocking
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.json.JSONArray
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
    @Test(timeout = 60_000)
    fun editedLongRelationshipSurvivesFailedPhoneTurnAndReachesNextExpression() = runBlocking {
        val context = RuntimeEnvironment.getApplication() as Context
        LuluRepositories.initialize(context)
        SharedExperienceTimeline.initialize(context)
        // Match application startup before editing the durable character source.
        CharacterLifeStore.initialize(context)
        LuluAiServices.initialize(context)
        LuluDeviceToolBridge.initialize(context)
        val jiang = MigratedDomainStores.characters.create("江渡编辑样本", "江渡旧性格样本")
        val xing = MigratedDomainStores.characters.create("星禾编辑样本", "星禾独立性格样本")
        val role = jiang.characterId
        val oldTitle = "双方共同写下的关系说明".repeat(8) + "旧标题末尾"
        val oldAgreement = "这份约定需要按完整条件理解。".repeat(150) +
            "旧末尾边界：没有用户明确确认，不代为作出决定。"
        val newAgreement = "双方重新商量后的完整约定。".repeat(160) +
            "新末尾边界：只有用户明确同意才再次联系，否则不再拨号。"
        val peerAgreement = "星禾本人约定：平等朋友，各自保留判断。"
        CharacterIdentityStore.set(role, "江渡旧身份样本")
        CharacterIdentityStore.set(xing.characterId, "星禾独立身份样本")
        CharacterLifeStore.setProfile(role, "speechHabits", "江渡旧语言习惯样本")
        assertTrue(CharacterSocialRelationship.setAgreement(role, "user", oldTitle, oldAgreement))
        assertTrue(CharacterSocialRelationship.setAgreement(xing.characterId, "user", "星禾约定", peerAgreement))
        assertTrue(oldTitle.length > 70)
        assertTrue(oldAgreement.length > 1600)
        assertTrue(newAgreement.length > 1600)
        val saved = CharacterSocialRelationship.agreement(role, "user")!!
        assertEquals(oldTitle, saved.title)
        assertEquals(oldAgreement, saved.details)
        val preferences = context.getSharedPreferences("lulu_character_life", Context.MODE_PRIVATE)
        fun persistedAgreement(): String = JSONObject(checkNotNull(preferences.getString(role, null)))
            .getJSONObject("relationshipAgreements").getJSONObject("user").getString("details")
        assertEquals(oldAgreement, persistedAgreement())

        val server = MockWebServer()
        server.enqueue(MockResponse().setResponseCode(401)
            .setBody("""{"error":{"message":"test rejected request"}}"""))
        repeat(5) {
            val choice = JSONObject().put("message", JSONObject().put("content",
                JSONObject().put("action", "reply").put("text", "收到。").toString()))
                .put("finish_reason", "stop")
            server.enqueue(MockResponse().setHeader("Content-Type", "application/json")
                .setBody(JSONObject().put("choices", JSONArray().put(choice)).toString()))
        }
        server.start()
        try {
            val config = LuluAiServices.connectionStore.saveConfiguration(null, "test",
                server.url("/").toString(), "test-only")
            val archive = LuluAiServices.connectionStore.addArchive(config.id, "test")
            val connection = ModelConnection(server.url("/").toString(), "test-only", "test")
            suspend fun phone(id: String) = LuluDeviceToolBridge.respond(id, "", "按当前约定接话",
                "test", archiveId = archive.id, sceneContext = "正在电话中",
                phoneCallId = "relationship-edit-$id")
            fun prompt(): String = JSONObject(checkNotNull(server.takeRequest(5, TimeUnit.SECONDS))
                .body.readUtf8()).getJSONArray("messages").getJSONObject(0).getString("content")
            assertTrue(phone(role).isFailure)
            val failed = prompt()
            assertTrue(failed.contains(oldTitle))
            assertTrue(failed.contains(oldAgreement))

            MigratedDomainStores.characters.update(
                MigratedDomainStores.characters.get(role).copy(persona = "江渡新性格样本：有主见，按约定负责"))
            CharacterIdentityStore.set(role, "江渡新身份样本")
            CharacterLifeStore.setProfile(role, "speechHabits", "江渡新语言习惯样本")
            CharacterLifeStore.setProfile(role, "dialogueExamples", "用户：先听我说完。江渡：嗯，我听着。")
            assertTrue(CharacterSocialRelationship.setAgreement(role, "user", "江渡新约定", newAgreement))
            assertEquals(newAgreement, persistedAgreement())
            // Reload the real durable source. A failed request must not restore its old snapshot.
            CharacterLifeStore.javaClass.getDeclaredField("prefs").apply { isAccessible = true }
                .set(CharacterLifeStore, null)
            CharacterLifeStore.initialize(context)
            assertEquals(newAgreement, CharacterSocialRelationship.agreement(role, "user")?.details)
            fun assertCurrent(system: String) {
                assertTrue(system.contains("江渡新性格样本"))
                assertTrue(system.contains("江渡新身份样本"))
                assertTrue(system.contains("江渡新语言习惯样本"))
                assertTrue(system.contains("用户：先听我说完。江渡：嗯，我听着。"))
                assertTrue(system.contains(newAgreement))
                assertEquals(1, system.split(newAgreement).size - 1)
                assertFalse(system.contains(oldAgreement))
                assertFalse(system.contains(oldTitle))
                assertFalse(system.contains("江渡旧性格样本"))
                assertFalse(system.contains("江渡旧身份样本"))
                assertFalse(system.contains("江渡旧语言习惯样本"))
                assertFalse(system.contains(peerAgreement))
                assertFalse(system.contains("星禾独立性格样本"))
            }
            phone(role).getOrThrow()
            assertCurrent(prompt())
            suspend fun expression(mode: CompanionContextMode = CompanionContextMode.Full) =
                LuluAiServices.gateway.generate(role, "本人已经选定回应的意思", "表达本轮已选意图",
                    "聊天表达渲染", "test", connectionOverride = connection, contextMode = mode)
                    .getOrThrow()
            expression()
            assertCurrent(prompt())
            phone(xing.characterId).getOrThrow()
            val peer = prompt()
            assertTrue(peer.contains(peerAgreement))
            assertTrue(peer.contains("星禾独立性格样本"))
            assertFalse(peer.contains(newAgreement))
            assertFalse(peer.contains("江渡新性格样本"))
            expression(CompanionContextMode.Isolated)
            val isolated = prompt()
            assertFalse(isolated.contains(newAgreement))
            assertFalse(isolated.contains("江渡新性格样本"))
            assertFalse(isolated.contains("江渡新身份样本"))
            assertFalse(isolated.contains("江渡新语言习惯样本"))

            assertTrue(CharacterSocialRelationship.setAgreement(role, "user", "", ""))
            expression()
            val cleared = prompt()
            assertFalse(cleared.contains(newAgreement))
            assertFalse(cleared.contains(oldAgreement))
            assertTrue(cleared.contains("江渡新性格样本"))
            assertNull(CharacterSocialRelationship.agreement(role, "user"))
            assertEquals(6, server.requestCount)
        } finally { server.shutdown() }
    }

    @Test fun campaignHostUsesEachMembersEditedPersonaAndOnlyCampaignFacts() = runBlocking {
        val context = RuntimeEnvironment.getApplication() as Context
        LuluRepositories.initialize(context)
        SharedExperienceTimeline.initialize(context)
        CharacterLifeStore.initialize(context)
        LuluAiServices.initialize(context)
        val jiang = MigratedDomainStores.characters.create("江渡跑团样本", "江渡旧版取舍")
        val xing = MigratedDomainStores.characters.create("星禾跑团样本", "星禾独立取舍")
        val jiangId = jiang.characterId
        val xingId = xing.characterId
        val jiangIdentity = "江渡原世界专属职业样本"
        val xingIdentity = "星禾原世界专属职业样本"
        val jiangAgreement = "江渡原世界约定样本"
        val xingAgreement = "星禾原世界约定样本"
        CharacterIdentityStore.set(jiangId, jiangIdentity)
        CharacterIdentityStore.set(xingId, xingIdentity)
        assertTrue(CharacterSocialRelationship.setAgreement(jiangId, "user", "原世界", jiangAgreement))
        assertTrue(CharacterSocialRelationship.setAgreement(xingId, "user", "原世界", xingAgreement))
        CharacterLifeStore.setProfile(jiangId, "speechHabits", "江渡旧版语气")
        val xingSpeech = "星禾说话直率，有疑问先确认"
        CharacterLifeStore.setProfile(xingId, "speechHabits", xingSpeech)

        val server = MockWebServer()
        repeat(3) {
            server.enqueue(MockResponse().setHeader("Content-Type", "application/json")
                .setBody("""{"choices":[{"message":{"content":"测试返回"},"finish_reason":"stop"}]}"""))
        }
        server.start()
        try {
            val config = LuluAiServices.connectionStore.saveConfiguration(null, "test",
                server.url("/").toString(), "test-only")
            LuluAiServices.connectionStore.addArchive(config.id, "test")
            val roster = listOf(xingId, jiangId)
            suspend fun request(host: CampaignHost, source: String, facts: String): String {
                host.generate(facts, "根据同行角色各自设定主持本战役", source, "测试", 500).getOrThrow()
                val body = JSONObject(checkNotNull(server.takeRequest(5, TimeUnit.SECONDS)).body.readUtf8())
                val messages = body.getJSONArray("messages")
                val system = messages.getJSONObject(0).getString("content")
                assertFalse(system.contains("你正在以"))
                val payload = messages.getJSONObject(messages.length() - 1).getString("content")
                assertTrue(payload.contains(facts))
                for (privateFact in listOf(jiangIdentity, xingIdentity, jiangAgreement, xingAgreement)) {
                    assertFalse(messages.toString().contains(privateFact))
                }
                return payload
            }

            val before = request(CampaignHost(roster), "游戏裁定", "本战役旧轮输入")
            assertTrue(before.substringAfter("角色ID：$jiangId").contains("江渡旧版语气"))

            val revised = "江渡新版取舍：只有当事人明确同意才分头行动，否则先留在原地。"
            val revisedSpeech = "江渡新版语气：话短，但把条件说完整"
            MigratedDomainStores.characters.update(jiang.copy(persona = revised))
            CharacterLifeStore.setProfile(jiangId, "speechHabits", revisedSpeech)

            val turn = CampaignHost(roster)
            val ruling = request(turn, "游戏裁定", "本战役新轮输入")
            val narration = request(turn, "游戏", "本战役新轮输入，已裁定无需检定")
            for (payload in listOf(ruling, narration)) {
                val xingBlock = payload.substringAfter("角色ID：$xingId").substringBefore("角色ID：$jiangId")
                val jiangBlock = payload.substringAfter("角色ID：$jiangId")
                assertTrue(xingBlock.contains("星禾独立取舍"))
                assertTrue(xingBlock.contains(xingSpeech))
                assertFalse(xingBlock.contains(revisedSpeech))
                assertTrue(jiangBlock.contains(revised))
                assertTrue(jiangBlock.contains(revisedSpeech))
                assertFalse(jiangBlock.contains(xingSpeech))
                assertFalse(payload.contains("江渡旧版"))
            }
            assertEquals(3, server.requestCount)
        } finally { server.shutdown() }
    }

    @Test fun crossWorldKeepsEditedTemperamentWithoutOriginalWorldDialogueScenes() = runBlocking {
        val context = RuntimeEnvironment.getApplication() as Context
        LuluRepositories.initialize(context)
        SharedExperienceTimeline.initialize(context)
        CharacterLifeStore.initialize(context)
        LuluAiServices.initialize(context)
        val jiang = MigratedDomainStores.characters.create("江渡投影样本", "江渡旧版性格")
        val xing = MigratedDomainStores.characters.create("星禾投影样本", "星禾会直说自己的顾虑")
        val jiangId = jiang.characterId
        val xingId = xing.characterId
        val identity = "江渡原世界身份专用标记"
        val agreement = "江渡原世界具体约定专用标记"
        val dialogue = "原世界客厅里的恋人对话专用标记"
        val xingDialogue = "星禾原世界闺蜜对话专用标记"
        CharacterIdentityStore.set(jiangId, identity)
        assertTrue(CharacterSocialRelationship.setAgreement(jiangId, "user", "原世界约定", agreement))
        CharacterLifeStore.setProfile(jiangId, "dialogueExamples", dialogue)
        CharacterLifeStore.setProfile(jiangId, "speechHabits", "江渡旧版表达")
        CharacterLifeStore.setProfile(xingId, "dialogueExamples", xingDialogue)
        CharacterLifeStore.setProfile(xingId, "speechHabits", "星禾表达直截了当")

        val server = MockWebServer()
        repeat(5) {
            server.enqueue(MockResponse().setHeader("Content-Type", "application/json")
                .setBody("""{"choices":[{"message":{"content":"测试返回"},"finish_reason":"stop"}]}"""))
        }
        server.start()
        try {
            val connection = ModelConnection(server.url("/").toString(), "test-only", "test")
            suspend fun system(role: String, mode: CompanionContextMode, source: String): String {
                LuluAiServices.gateway.generate(role, "本轮场景中双方刚见面", "按场景和人设回应",
                    source, "测试", connectionOverride = connection, contextMode = mode).getOrThrow()
                val body = JSONObject(checkNotNull(server.takeRequest(5, TimeUnit.SECONDS)).body.readUtf8())
                return body.getJSONArray("messages").getJSONObject(0).getString("content")
            }

            val before = system(jiangId, CompanionContextMode.PersonaAndScenario, "末世求生V4正文")
            assertTrue(before.contains("江渡旧版性格"))
            assertFalse(before.contains(dialogue))

            val revised = "江渡新版性格：先确认当事人的意愿。".repeat(90) +
                "最终条件：只有当事人明确同意才分头行动，否则不代为决定。"
            val revisedSpeech = "江渡新版表达：简短，但不省略否定条件"
            val revisedDialogue = "江渡编辑后的原世界场景专用标记"
            MigratedDomainStores.characters.update(jiang.copy(persona = revised))
            CharacterLifeStore.setProfile(jiangId, "speechHabits", revisedSpeech)
            CharacterLifeStore.setProfile(jiangId, "dialogueExamples", revisedDialogue)

            val xingPrompt = system(xingId, CompanionContextMode.PersonaAndScenario, "末世求生V4正文")
            assertTrue(xingPrompt.contains("星禾会直说自己的顾虑"))
            assertTrue(xingPrompt.contains("星禾表达直截了当"))
            assertFalse(xingPrompt.contains(xingDialogue))
            assertFalse(xingPrompt.contains(revisedSpeech))

            val after = system(jiangId, CompanionContextMode.PersonaAndScenario, "末世求生V4正文")
            assertTrue(after.contains(revised))
            assertTrue(after.contains(revisedSpeech))
            for (excluded in listOf(identity, agreement, dialogue, revisedDialogue, xingDialogue,
                "江渡旧版性格", "江渡旧版表达", "星禾表达直截了当")) {
                assertFalse(after.contains(excluded))
            }

            for (mode in listOf(CompanionContextMode.Full, CompanionContextMode.CharacterAndScenario)) {
                val native = system(jiangId, mode, "聊天表达渲染")
                assertTrue(native.contains(revised))
                assertTrue(native.contains(revisedSpeech))
                assertTrue(native.contains(identity))
                assertTrue(native.contains(agreement))
                assertTrue(native.contains(revisedDialogue))
                assertFalse(native.contains(dialogue))
                assertFalse(native.contains(xingDialogue))
            }
            assertEquals(5, server.requestCount)
        } finally { server.shutdown() }
    }

    @Test fun isolatedContextsDoNotAttachOrConsumeProfileAvatarFromPhoneSource() = runBlocking {
        val context = RuntimeEnvironment.getApplication() as Context
        LuluRepositories.initialize(context)
        SharedExperienceTimeline.initialize(context)
        CharacterLifeStore.initialize(context)
        LuluAiServices.initialize(context)
        val jiang = MigratedDomainStores.characters.create("江渡头像样本", "江渡稳定个性标记")
        val role = jiang.characterId
        val identity = "江渡原世界专用身份标记"
        val agreement = "江渡原世界专用关系标记"
        CharacterIdentityStore.set(role, identity)
        assertTrue(CharacterSocialRelationship.setAgreement(role, "user", "本世界约定", agreement))

        val profile = context.getSharedPreferences("lulu_user_profile", Context.MODE_PRIVATE)
        val avatar = java.io.File.createTempFile("test-avatar-", ".png", context.cacheDir)
        val png = "iVBORw0KGgoAAAANSUhEUgAAAAIAAAACCAIAAAD91JpzAAAAFklEQVR4nGPUKN7HwMDAxMDAwMDAAAAPwAFd5W5fSAAAAABJRU5ErkJggg=="
        avatar.writeBytes(android.util.Base64.decode(png, android.util.Base64.DEFAULT))
        val revision = "test-avatar-boundary"
        val uri = android.net.Uri.fromFile(avatar).toString()
        val explicitImage = "data:image/png;base64,$png"
        val server = MockWebServer()
        repeat(4) {
            val reply = JSONObject().put("avatarObservation", "测试图片观察")
            val choice = JSONObject().put("message", JSONObject().put("content", reply.toString()))
            server.enqueue(MockResponse().setHeader("Content-Type", "application/json")
                .setBody(JSONObject().put("choices", JSONArray().put(choice)).toString()))
        }
        server.start()
        try {
            profile.edit().putString("avatar_uri", uri).putString("avatar_vision_revision_v2", revision).commit()
            val prepared = checkNotNull(UserAvatarVision.prepare(context, role, 1))
            val connection = ModelConnection(server.url("/").toString(), "test-only", "test")
            val evidenceId = "user-profile-avatar-vision-$revision-$role"
            suspend fun request(mode: CompanionContextMode): Pair<String, List<String>> {
                LuluAiServices.gateway.generate(role, "场景专用素材", "按场景回应", "电话回复", "测试",
                    contextMode = mode, connectionOverride = connection,
                    imageDataUrls = listOf(explicitImage)).getOrThrow()
                val body = JSONObject(checkNotNull(server.takeRequest(5, TimeUnit.SECONDS)).body.readUtf8())
                val messages = body.getJSONArray("messages")
                val system = messages.getJSONObject(0).getString("content")
                val content = messages.getJSONObject(messages.length() - 1).getJSONArray("content")
                val images = (0 until content.length()).mapNotNull { index ->
                    content.getJSONObject(index).optJSONObject("image_url")?.getString("url")
                }
                return system to images
            }
            for (mode in listOf(CompanionContextMode.Isolated, CompanionContextMode.PersonaAndScenario,
                CompanionContextMode.Ensemble)) {
                val (system, images) = request(mode)
                assertEquals(listOf(explicitImage), images)
                assertFalse(system.contains("avatarObservation"))
                assertFalse(system.contains(identity))
                assertFalse(system.contains(agreement))
                assertEquals(mode == CompanionContextMode.PersonaAndScenario, system.contains(jiang.persona))
                assertTrue(SharedExperienceTimeline.eventsByIds(role, listOf(evidenceId)).isEmpty())
                assertNull(profile.getString("avatar_vision_seen_v2:$role", null))
            }

            val (phone, phoneImages) = request(CompanionContextMode.CharacterAndScenario)
            assertEquals(listOf(explicitImage, prepared.dataUrl), phoneImages)
            assertTrue(phone.contains("avatarObservation"))
            assertTrue(phone.contains(identity))
            assertTrue(phone.contains(agreement))
            assertEquals(revision, profile.getString("avatar_vision_seen_v2:$role", null))
            assertEquals(1, SharedExperienceTimeline.eventsByIds(role, listOf(evidenceId)).size)
            assertEquals(4, server.requestCount)
        } finally {
            profile.edit().remove("avatar_uri").remove("avatar_vision_revision_v2")
                .remove("avatar_vision_seen_v2:$role").commit()
            avatar.delete()
            server.shutdown()
        }
    }

}
