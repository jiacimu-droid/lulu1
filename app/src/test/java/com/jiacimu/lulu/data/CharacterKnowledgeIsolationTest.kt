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

@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE, sdk = [28])
class CharacterKnowledgeIsolationTest {
    @Before fun before() { MemoryTestEnvironment.release() }
    @After fun after() { MemoryTestEnvironment.release() }

    @Test fun oneFriendsLearnedPreferenceAndThreeDayRoutineNeverBecomeAnothersKnowledge() {
        val context = RuntimeEnvironment.getApplication() as Context
        LuluRepositories.initialize(context)
        SharedExperienceTimeline.initialize(context)
        CharacterDevelopmentStore.initialize(context)
        val first = MigratedDomainStores.characters.create("朋友甲", "喜欢听她说自己的事")
        val second = MigratedDomainStores.characters.create("朋友乙", "有自己的生活")
        val firstId = first.characterId
        val secondId = second.characterId
        val evidence = "only-first-" + UUID.randomUUID()
        val privatePreference = "我喜欢只跟你聊天时讨论玻璃花房里的燕麦饼"
        val recent = Instant.now().minusSeconds(50)
        SharedExperienceTimeline.record(evidence, firstId, "私聊", "用户",
            privatePreference, recent, triggerExtraction = false,
            source = "message", evidenceKind = EventEvidenceKind.UserStatement)
        assertTrue(CharacterDevelopmentStore.applyProposal(
            firstId, "user:unique-preference", DevelopmentKind.UserPreference,
            "只有我知道她喜欢在这里聊玻璃花房里的燕麦饼",
            listOf(evidence), emptyList(), CharacterRuntime.personaConstraintSnapshot(firstId)))
        assertEquals(1, CharacterDevelopmentStore.active(firstId)
            .count { it.kind == DevelopmentKind.UserPreference })
        assertTrue(CharacterDevelopmentStore.active(secondId).isEmpty())
        assertTrue(SharedExperienceTimeline.eventsByIds(secondId, listOf(evidence)).isEmpty())
        assertTrue(CharacterRuntime.personalityRuntimeContext(firstId)
            .contains("玻璃花房里的燕麦饼"))
        assertFalse(CharacterRuntime.personalityRuntimeContext(secondId)
            .contains("玻璃花房里的燕麦饼"))

        repeat(3) { day ->
            SharedExperienceTimeline.record(
                "user-activity-$firstId-$day", firstId, "电话互动", "实际操作",
                "本次电话实际开启睡前语音模式",
                recent.minusSeconds((day + 1L) * 86_400L),
                triggerExtraction = false,
                sessionId = "first-only-call-$day",
                source = "user-activity:sleep-guidance:start",
                evidenceKind = EventEvidenceKind.Observation,
            )
        }
        assertTrue(UserRoutinePatterns.recent(firstId).any {
            it.activity == "sleep-guidance" && it.days == 3
        })
        assertTrue(UserRoutinePatterns.recent(secondId).isEmpty())
        assertFalse(CharacterDevelopmentStore.applyProposal(
            secondId, "user:stolen-habit", DevelopmentKind.UserHabit,
            "错把别人的电话活动当成了我的观察",
            (0..2).map { "user-activity-$firstId-$it" },
            emptyList(), CharacterRuntime.personaConstraintSnapshot(secondId)))

        SharedExperienceTimeline.deleteEvent(evidence)
        assertFalse(CharacterDevelopmentStore.active(firstId)
            .any { it.slot == "user:unique-preference" })
        assertFalse(CharacterRuntime.personalityRuntimeContext(firstId)
            .contains("玻璃花房里的燕麦饼"))
    }

    @Test fun fullChatGatewayDoesNotBroadcastAccountProfileOrAnotherRolesPreference() = runBlocking {
        val context = RuntimeEnvironment.getApplication() as Context
        LuluRepositories.initialize(context)
        SharedExperienceTimeline.initialize(context)
        CharacterDevelopmentStore.initialize(context)
        LuluAiServices.initialize(context)
        UserProfileContext.initialize(context)
        val owner = context.getSharedPreferences("lulu_user_profile", Context.MODE_PRIVATE)
        val priorAlias = owner.getString("preferred_name", null)
        val priorBio = owner.getString("bio", null)
        val a = MigratedDomainStores.characters.create("独立甲", "留意共同经历")
        val b = MigratedDomainStores.characters.create("独立乙", "只相信自己亲历")
        val marker = UUID.randomUUID().toString().replace("-", "")
        val privateBio = "profile-bio-only-$marker"
        val globalAddress = "account-alias-only-$marker"
        val preference = "only-character-A-knows-$marker"
        val eventId = "only-a-$marker"
        val server = MockWebServer()
        repeat(3) {
            server.enqueue(MockResponse().setHeader("Content-Type", "application/json")
                .setBody("""{"choices":[{"message":{"content":"嗯。"},"finish_reason":"stop"}]}"""))
        }
        server.start()
        try {
            owner.edit().putString("preferred_name", globalAddress)
                .putString("bio", privateBio).commit()
            // Account profile metadata must not become EVERY role's personal
            // memory merely because the UI stores it once.
            assertFalse(UserProfileContext.promptSection().contains(globalAddress))
            assertFalse(UserProfileContext.promptSection().contains(privateBio))
            SharedExperienceTimeline.record(eventId, a.characterId, "私聊",
                "用户", "我喜欢 $preference", Instant.now().minusSeconds(30),
                triggerExtraction = false, source = "message",
                evidenceKind = EventEvidenceKind.UserStatement)
            assertTrue(CharacterDevelopmentStore.applyProposal(a.characterId,
                "user:account-isolation", DevelopmentKind.UserPreference,
                "她告诉我偏好 $preference", listOf(eventId), emptyList(),
                CharacterRuntime.personaConstraintSnapshot(a.characterId)))
            val connection = ModelConnection(server.url("/").toString(),
                "test-only", "test-model")
            val requests = listOf(
                a.characterId to "聊天工具规划",
                b.characterId to "聊天工具规划",
                b.characterId to "聊天表达渲染",
            )
            requests.forEach { (role, source) ->
                LuluAiServices.gateway.generate(
                    role, "你最近在想什么", "用本人的知识和关系作答",
                    source, "test", connectionOverride = connection,
                    contextMode = if (source == "聊天表达渲染")
                        CompanionContextMode.CharacterAndScenario
                    else CompanionContextMode.Full,
                    memoryRequest = UnifiedMemoryRequest(
                        currentInput = "今天聊点什么", sceneContext = "私聊"),
                ).getOrThrow()
                val wire = server.takeRequest(10, TimeUnit.SECONDS)!!
                    .body.readUtf8()
                assertFalse(wire.contains(privateBio))
                assertFalse(wire.contains(globalAddress))
                if (role == a.characterId) {
                    assertTrue(wire.contains(preference))
                } else {
                    assertFalse(wire.contains(preference))
                    assertFalse(wire.contains(eventId))
                }
            }
            assertEquals(3, server.requestCount)
        } finally {
            val restore = owner.edit()
            if (priorAlias == null) restore.remove("preferred_name")
            else restore.putString("preferred_name", priorAlias)
            if (priorBio == null) restore.remove("bio")
            else restore.putString("bio", priorBio)
            restore.commit()
            server.shutdown()
        }
    }
}
