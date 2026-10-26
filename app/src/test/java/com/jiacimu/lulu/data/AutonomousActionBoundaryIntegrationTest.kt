package com.jiacimu.lulu.data

import android.content.Context
import kotlinx.coroutines.runBlocking
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import java.time.Instant
import java.util.UUID

@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE, sdk = [28])
class AutonomousActionBoundaryIntegrationTest {
    private val app get() = RuntimeEnvironment.getApplication() as Context

    @Before fun init() {
        if (runCatching { androidx.work.WorkManager.getInstance(app) }.isFailure)
            androidx.work.WorkManager.initialize(app, androidx.work.Configuration.Builder().build())
        initializeBackgroundRuntime(app)
    }

    private fun role(): String {
        val person = MigratedDomainStores.characters.create("行动边界-${UUID.randomUUID()}")
        DigitalLifeProfileStore.registerNewLife(person.characterId, person.displayName, "数字生命", Instant.now())
        return person.characterId
    }

    @Test fun explicitSpaceBlocksPrimaryAndSideContactWithoutErasingIndependentLife() = runBlocking {
        val actor = role()
        val now = Instant.now()
        CharacterLifeStore.consider(actor, JSONObject().put("aim", "想再聊聊")
            .put("motive", "自己还有话想说").put("nextStep", "找合适的时间联系"), now)
        CharacterLifeStore.observeUserGoalEvidence(actor, "space-${UUID.randomUUID()}", "先别联系我", now.plusSeconds(1))
        val id = "space-action-${UUID.randomUUID()}"
        val primary = JSONObject(ToolRouter.execute(app, actor, "send_private_message",
            JSONObject().put("text", "不应发送的主动联系"), requestId = id, autonomousLifeTurn = true))
        assertFalse(primary.optBoolean("success"))
        assertTrue(SharedExperienceTimeline.eventsByIds(actor,
            listOf("tool-rejected-$actor-$id")).isNotEmpty())
        val text = "自己的生活片段-${UUID.randomUUID()}"
        val report = OrderedCharacterActions.execute(app, actor, JSONArray()
            .put(JSONObject().put("tool", "publish_moment").put("requiresPreviousSuccess", true)
                .put("args", JSONObject().put("text", "依赖未完成联系，不应发布")))
            .put(JSONObject().put("tool", "publish_moment").put("requiresPreviousSuccess", false)
                .put("args", JSONObject().put("text", text)))
            .put(JSONObject().put("tool", "send_private_message").put("requiresPreviousSuccess", false)
                .put("args", JSONObject().put("text", "副步骤也不能绕过暂停联系"))),
            requestId = id, initialCompleted = ToolExecutionStatus.primaryAllowsDependentSteps(
                "send_private_message", primary), autonomousLifeTurn = true)
        assertEquals(1, report.completedCount)
        val posts = MomentsStore.posts.value.filter { it.authorCharacterId == actor }
        assertEquals(listOf(text), posts.map { it.content })
        assertTrue(SharedExperienceTimeline.eventsByIds(actor,
            listOf("tool-rejected-$actor-$id:step:2")).isNotEmpty())
    }

    @Test(timeout = 60_000) fun successfulSidePostCannotTurnFailedPrimaryIntoSuccessfulGoalProgress() = runBlocking {
        val actor = role()
        val chat = MigratedDomainStores.chat.ensureConversation(actor, "主副动作回执隔离")
        MigratedDomainStores.chat.sendUserMessage(chat.id, "看看这件事情能不能做。")
        OnlineChatBatchStore.cancel(app, actor)
        val batch = OnlineChatBatchStore.onUserBubble(app, actor,
            atMillis = System.currentTimeMillis() - OnlineChatBatchStore.QUIET_MILLIS - 1_000L)
        val origin = "primary-source-${UUID.randomUUID()}"
        SharedExperienceTimeline.record(origin, actor, "真实观察", "执行器", "发现一个操作尚未核实",
            triggerExtraction = false, evidenceKind = EventEvidenceKind.Observation)
        CharacterInnerLifeStore.observe(actor, origin, "自己想核实操作", JSONObject()
            .put("motives", JSONArray().put(JSONObject().put("op", "start")
                .put("aim", "想核实这个操作能否执行").put("why", "自己还不知道"))))
        val motive = CharacterMotiveState.active(CharacterInnerLifeStore.snapshot(actor).optJSONArray("motives"))
            .single { it.optString("aim") == "想核实这个操作能否执行" }.optString("id")
        val text = "独立发出的生活动态-${UUID.randomUUID()}"
        val choice = JSONObject().put("action", "tool").put("tool", "nonexistent_primary_tool")
            .put("args", JSONObject()).put("reason", "想核实可否执行")
            .put("motiveId", motive).put("replyChoice", "silent")
            .put("steps", JSONArray().put(JSONObject().put("tool", "publish_moment")
                .put("requiresPreviousSuccess", false).put("args", JSONObject().put("text", text))))
        val server = okhttp3.mockwebserver.MockWebServer()
        server.dispatcher = object : okhttp3.mockwebserver.Dispatcher() {
            override fun dispatch(request: okhttp3.mockwebserver.RecordedRequest) =
                okhttp3.mockwebserver.MockResponse().setHeader("Content-Type", "application/json")
                    .setBody(JSONObject().put("choices", JSONArray().put(JSONObject().put("message",
                        JSONObject().put("content", choice.toString())).put("finish_reason", "stop"))).toString())
        }
        server.start()
        try {
            val store = com.jiacimu.lulu.ai.LuluAiServices.connectionStore
            val config = store.saveConfiguration(null, "primary-result-test", server.url("/").toString(), "test")
            store.addArchive(config.id, "test")
            ProactivePerceptionRuntime.runDueCycle(app, "私聊新消息", actor, force = true,
                onlineRevision = batch.revision, requiresUnread = true)
            assertTrue(MomentsStore.posts.value.any { it.authorCharacterId == actor && it.content == text })
            val state = CharacterMotiveState.active(CharacterInnerLifeStore.snapshot(actor).optJSONArray("motives"))
                .single { it.optString("id") == motive }
            val outcomes = state.optJSONArray("outcomes") ?: JSONArray()
            assertTrue(outcomes.length() > 0)
            assertFalse((0 until outcomes.length()).any { outcomes.getJSONObject(it).optBoolean("success") })
            assertEquals("needs_review", state.optString("lastAttemptOutcome"))
        } finally { server.shutdown() }
    }

    @Test fun unansweredCountDoesNotVetoAChosenActionAndReplayPreservesAlreadyDeliveredFact() = runBlocking {
        val actor = role()
        CharacterLifeStore.consider(actor, JSONObject().put("aim", "还想一起讨论")
            .put("motive", "自己有个话题").put("nextStep", "等回应后再谈"), Instant.now())
        val goalId = CharacterLifeStore.state(actor).getJSONObject("intention").getString("createdAt")
        val firstId = "first-${UUID.randomUUID()}"
        val firstArgs = JSONObject().put("text", "第一条实际发出的消息")
        val first = ToolRouter.execute(app, actor, "send_private_message", firstArgs,
            requestId = firstId, intentionId = goalId, autonomousLifeTurn = true)
        assertTrue(JSONObject(first).optBoolean("success"))
        val second = ToolRouter.execute(app, actor, "send_private_message",
            JSONObject().put("text", "第二条实际发出的消息"), requestId = "second-${UUID.randomUUID()}",
            intentionId = goalId, autonomousLifeTurn = true)
        assertTrue(JSONObject(second).optBoolean("success"))
        assertEquals(2, CharacterLifeStore.state(actor).getJSONObject("intention").getInt("unansweredContactAttempts"))
        val report = OrderedCharacterActions.execute(app, actor, JSONArray()
            .put(JSONObject().put("tool", "send_private_message")
                .put("args", JSONObject().put("text", "第三条本人选择的主动联系")))
            .put(JSONObject().put("tool", "publish_moment").put("requiresPreviousSuccess", false)
                .put("args", JSONObject().put("text", "独立的生活分享-${UUID.randomUUID()}"))),
            requestId = "follow-up-${UUID.randomUUID()}", intentionId = goalId, autonomousLifeTurn = true)
        assertEquals(2, report.completedCount)
        assertEquals(3, CharacterLifeStore.state(actor).getJSONObject("intention").getInt("unansweredContactAttempts"))
        val before = MigratedDomainStores.chat.conversations.value.filter { it.characterId == actor }
            .sumOf { MigratedDomainStores.chat.messages(it.id).value.size }
        assertEquals(first, ToolRouter.execute(app, actor, "send_private_message", firstArgs,
            requestId = firstId, intentionId = goalId, autonomousLifeTurn = true))
        assertEquals(before, MigratedDomainStores.chat.conversations.value.filter { it.characterId == actor }
            .sumOf { MigratedDomainStores.chat.messages(it.id).value.size })
        // A fresh user-driven turn uses the ordinary reply path; this pacing
        // rule is never a reason to swallow a newly received user message.
        assertNull(GoalFollowUpPolicy.contactBlockReason(CharacterLifeStore.state(actor)
            .getJSONObject("intention"), "send_private_message", false, goalId))
    }
}
