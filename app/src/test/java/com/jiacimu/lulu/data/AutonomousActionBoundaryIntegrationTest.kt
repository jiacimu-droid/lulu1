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
        val stranger = role()
        ResidentSocialMailbox.initialize(app)
        assertFalse(ResidentSocialMailbox.canContact(actor, stranger))
        // The tool is registered and the target really exists. Its executor
        // must still reject a private message before these people have met.
        val choice = JSONObject().put("action", "tool").put("tool", "send_character_message")
            .put("args", JSONObject().put("targetCharacterId", stranger).put("text", "你好"))
            .put("reason", "想核实可否执行")
            .put("motiveId", motive).put("replyChoice", "silent")
            .put("steps", JSONArray().put(JSONObject().put("tool", "publish_moment")
                .put("requiresPreviousSuccess", false).put("args", JSONObject().put("text", text))))
        val modelInputs = java.util.concurrent.CopyOnWriteArrayList<String>()
        val server = okhttp3.mockwebserver.MockWebServer()
        server.dispatcher = object : okhttp3.mockwebserver.Dispatcher() {
            override fun dispatch(request: okhttp3.mockwebserver.RecordedRequest): okhttp3.mockwebserver.MockResponse {
                modelInputs.add(request.body.readUtf8())
                val decision = if (modelInputs.size == 1) choice else JSONObject()
                    .put("action", "silent").put("reason", "等待真实失败结果的后续核实")
                return okhttp3.mockwebserver.MockResponse().setHeader("Content-Type", "application/json")
                    .setBody(JSONObject().put("choices", JSONArray().put(JSONObject().put("message",
                        JSONObject().put("content", decision.toString())).put("finish_reason", "stop"))).toString())
            }
        }
        server.start()
        try {
            val store = com.jiacimu.lulu.ai.LuluAiServices.connectionStore
            val config = store.saveConfiguration(null, "primary-result-test", server.url("/").toString(), "test")
            val archive = store.addArchive(config.id, "test")
            // Importing an archive is storage-only. The test must explicitly
            // assign its mock server to this character's real chat route.
            com.jiacimu.lulu.ai.ScopedModelSelections.initialize(app)
            com.jiacimu.lulu.ai.ScopedModelSelections.selectCharacterChatArchive(
                actor, archive.id, store.library.value)
            ProactivePerceptionRuntime.runDueCycle(app, "私聊新消息", actor, force = true,
                onlineRevision = batch.revision, requiresUnread = true)
            assertTrue(MomentsStore.posts.value.any { it.authorCharacterId == actor && it.content == text })
            val state = CharacterMotiveState.active(CharacterInnerLifeStore.snapshot(actor).optJSONArray("motives"))
                .single { it.optString("id") == motive }
            val outcomes = state.optJSONArray("outcomes") ?: JSONArray()
            assertTrue(outcomes.length() > 0)
            assertFalse((0 until outcomes.length()).any { outcomes.getJSONObject(it).optBoolean("success") })
            assertEquals("needs_review", state.optString("lastAttemptOutcome"))
            // The rejected primary tool needs its own durable receipt; a
            // successful side post is not evidence that the contact happened.
            val primaryReceipts = SharedExperienceTimeline.all(actor)
                .filter { it.channel == "实际工具执行" }
                .mapNotNull { runCatching { JSONObject(it.content) }.getOrNull() }
                .filter { it.optString("tool") == "send_character_message" }
            assertTrue("被拒绝的主工具必须留下独立执行回执", primaryReceipts.isNotEmpty())
            assertTrue("拒绝回执不能伪装成成功", primaryReceipts.all {
                !JSONObject(it.getString("result")).optBoolean("success")
            })
            val rejectionEvidence = primaryReceipts.mapNotNull { receipt ->
                val result = JSONObject(receipt.getString("result"))
                (result.optString("error").ifBlank { result.optString("summary") })
                    .takeIf { it.isNotBlank() }
            }
            assertTrue("拒绝回执必须包含可追溯的失败原因", rejectionEvidence.isNotEmpty())
            // A successful independent side action must not erase the rejected
            // primary action when the same character decides again.
            MigratedDomainStores.chat.sendUserMessage(chat.id, "刚才那次联系到底成功了吗？")
            val next = OnlineChatBatchStore.onUserBubble(app, actor,
                atMillis = System.currentTimeMillis() - OnlineChatBatchStore.QUIET_MILLIS - 1_000L)
            ProactivePerceptionRuntime.runDueCycle(app, "私聊新消息", actor, force = true,
                onlineRevision = next.revision, requiresUnread = true)
            assertEquals("两次有效批次各一次模型决定", 2, modelInputs.size)
            assertTrue("下一轮仍应知道原目标", modelInputs[1].contains("想核实这个操作能否执行"))
            assertTrue("下一轮应保留失败主目标的稳定来源ID", modelInputs[1].contains(motive))
            assertTrue("下一轮应明确保留主动作未成功状态，而非只看见副动态",
                modelInputs[1].contains("needs_review"))
            assertTrue("下一轮模型必须看到被拒主工具的名称，而非只有目标状态",
                modelInputs[1].contains("send_character_message"))
            assertTrue("下一轮模型必须看到独立拒绝回执的来源通道",
                modelInputs[1].contains("实际工具执行"))
            assertTrue("下一轮模型应收到拒绝的具体原因，而非仅有工具名称和状态",
                rejectionEvidence.any { modelInputs[1].contains(it) })
            // A silent follow-up must not replay the rejected primary action or
            // manufacture a second failure receipt for the same decision.
            val receiptsAfterFollowUp = SharedExperienceTimeline.all(actor)
                .filter { it.channel == "实际工具执行" }
                .mapNotNull { runCatching { JSONObject(it.content) }.getOrNull() }
                .filter { it.optString("tool") == "send_character_message" }
            assertEquals("静默后续不得重复执行已拒绝的主工具",
                primaryReceipts.map { it.toString() }, receiptsAfterFollowUp.map { it.toString() })
            assertTrue("下一轮应保留成功的独立动态", modelInputs[1].contains(text))
            assertEquals("沉默决定不能重放独立动态", 1,
                MomentsStore.posts.value.count { it.authorCharacterId == actor && it.content == text })
            assertEquals("副动作成功不能改写主动作失败", "needs_review",
                CharacterMotiveState.active(CharacterInnerLifeStore.snapshot(actor).optJSONArray("motives"))
                    .single { it.optString("id") == motive }.optString("lastAttemptOutcome"))
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
    @Test(timeout = 60_000)
    fun invalidPersonalPlanAfterModelRepairKeepsFailureReceiptAndIndependentPost() = runBlocking {
        val actor = role()
        val chat = MigratedDomainStores.chat.ensureConversation(actor, "个人日程补正失败回执")
        MigratedDomainStores.chat.sendUserMessage(chat.id, "你可以自己安排明天的活动。")
        OnlineChatBatchStore.cancel(app, actor)
        val batch = OnlineChatBatchStore.onUserBubble(app, actor,
            atMillis = System.currentTimeMillis() - OnlineChatBatchStore.QUIET_MILLIS - 1_000L)
        val post = "这是自己真实选择的另一项独立活动-${UUID.randomUUID()}"
        val invalidSelection = JSONObject().put("action", "tool")
            .put("tool", "plan_personal_activity")
            .put("args", JSONObject().put("title", "明天出去走一走")
                .put("intent", "想自己找个时间看看风景"))
            .put("reason", "想为自己安排一件事")
            .put("replyChoice", "silent")
            .put("onlineDecision", JSONObject().put("choice", "continue")
                .put("reason", "我想把这项安排落实"))
            .put("steps", JSONArray().put(JSONObject()
                .put("tool", "publish_moment")
                .put("requiresPreviousSuccess", false)
                .put("args", JSONObject().put("text", post))))
        // The test model also fails its single field-correction attempt.
        // The exact original choice must reach ToolRouter as a FAILED receipt,
        // without losing the independent, explicitly selected social post.
        val server = okhttp3.mockwebserver.MockWebServer()
        server.dispatcher = object : okhttp3.mockwebserver.Dispatcher() {
            override fun dispatch(request: okhttp3.mockwebserver.RecordedRequest) =
                okhttp3.mockwebserver.MockResponse()
                    .setHeader("Content-Type", "application/json")
                    .setBody(JSONObject().put("choices", JSONArray().put(JSONObject()
                        .put("message", JSONObject().put("content", invalidSelection.toString()))
                        .put("finish_reason", "stop"))).toString())
        }
        server.start()
        try {
            val store = com.jiacimu.lulu.ai.LuluAiServices.connectionStore
            val config = store.saveConfiguration(null, "missing-dueAt-${UUID.randomUUID()}",
                server.url("/").toString(), "test")
            val archive = store.addArchive(config.id, "test")
            // Importing an archive is storage-only. The test must explicitly
            // assign its mock server to this character's real chat route.
            com.jiacimu.lulu.ai.ScopedModelSelections.initialize(app)
            com.jiacimu.lulu.ai.ScopedModelSelections.selectCharacterChatArchive(
                actor, archive.id, store.library.value)
            ProactivePerceptionRuntime.runDueCycle(app, "私聊新消息", actor, force = true,
                onlineRevision = batch.revision, requiresUnread = true)
            assertTrue("独立的朋友圈动作不能被未补齐的日程吞掉",
                MomentsStore.posts.value.any { it.authorCharacterId == actor && it.content == post })
            assertTrue("没有本人选定的真实时间就不得落库",
                RoleDayScheduleStore.plans(app, actor).isEmpty())
            val receipts = SharedExperienceTimeline.all(actor)
                .filter { it.channel == "实际工具执行" }
                .mapNotNull { runCatching { JSONObject(it.content) }.getOrNull() }
                .filter { it.optString("tool") == "plan_personal_activity" }
            assertTrue("日程失败必须有持久执行回执", receipts.isNotEmpty())
            assertTrue("日程缺时间不能算成功", receipts.all {
                val result = JSONObject(it.getString("result"))
                !result.optBoolean("success") &&
                    (result.optString("summary") + result.optString("error")).contains("dueAt")
            })
        } finally {
            server.shutdown()
        }
    }

    @Test(timeout = 60_000)
    fun modelCorrectsMissingPersonalScheduleTimeBeforeAnyScheduleIsSaved() = runBlocking {
        val actor = role()
        val chat = MigratedDomainStores.chat.ensureConversation(actor, "本人日程实际补正")
        MigratedDomainStores.chat.sendUserMessage(chat.id, "你自己安排一件以后要做的事吧。")
        OnlineChatBatchStore.cancel(app, actor)
        val batch = OnlineChatBatchStore.onUserBubble(app, actor,
            atMillis = System.currentTimeMillis() - OnlineChatBatchStore.QUIET_MILLIS - 1_000L)
        val chosenTitle = "明天下午读一会儿书"
        val chosenIntent = "自己想看看最近的新书"
        val dueAt = java.time.OffsetDateTime.now().plusHours(4)
            .format(java.time.format.DateTimeFormatter.ISO_OFFSET_DATE_TIME)
        val original = JSONObject().put("action", "tool")
            .put("tool", "plan_personal_activity")
            .put("args", JSONObject().put("title", chosenTitle).put("intent", chosenIntent))
            .put("reason", "想在将来留个时间继续读")
            .put("replyChoice", "silent")
            .put("onlineDecision", JSONObject().put("choice", "continue")
                .put("reason", "我想把原本选的活动安排下来"))
        val repaired = JSONObject().put("action", "tool")
            .put("tool", "plan_personal_activity")
            .put("args", JSONObject().put("dueAt", dueAt)
                .put("title", "不得替换原本的标题")
                .put("intent", "不得替换原本的动机"))
        val requests = java.util.concurrent.atomic.AtomicInteger(0)
        val server = okhttp3.mockwebserver.MockWebServer()
        server.dispatcher = object : okhttp3.mockwebserver.Dispatcher() {
            override fun dispatch(request: okhttp3.mockwebserver.RecordedRequest): okhttp3.mockwebserver.MockResponse {
                val selected = if (requests.getAndIncrement() == 0) original else repaired
                return okhttp3.mockwebserver.MockResponse()
                    .setHeader("Content-Type", "application/json")
                    .setBody(JSONObject().put("choices", JSONArray().put(JSONObject()
                        .put("message", JSONObject().put("content", selected.toString()))
                        .put("finish_reason", "stop"))).toString())
            }
        }
        server.start()
        try {
            val store = com.jiacimu.lulu.ai.LuluAiServices.connectionStore
            val config = store.saveConfiguration(null, "repair-dueAt-${UUID.randomUUID()}",
                server.url("/").toString(), "test")
            val archive = store.addArchive(config.id, "test")
            // Importing an archive is storage-only. The test must explicitly
            // assign its mock server to this character's real chat route.
            com.jiacimu.lulu.ai.ScopedModelSelections.initialize(app)
            com.jiacimu.lulu.ai.ScopedModelSelections.selectCharacterChatArchive(
                actor, archive.id, store.library.value)
            ProactivePerceptionRuntime.runDueCycle(app, "私聊新消息", actor, force = true,
                onlineRevision = batch.revision, requiresUnread = true)
            assertTrue("必须真的向同一角色请求缺失时间", requests.get() >= 2)
            val plans = RoleDayScheduleStore.plans(app, actor)
            assertEquals("补正时间后必须只保存一次原日程", 1, plans.size)
            assertEquals(chosenTitle, plans.single().title)
            assertEquals(chosenIntent, plans.single().intent)
            assertEquals(java.time.OffsetDateTime.parse(dueAt).toInstant(), plans.single().dueAt)
            val receipts = SharedExperienceTimeline.all(actor)
                .filter { it.channel == "实际工具执行" }
                .mapNotNull { runCatching { JSONObject(it.content) }.getOrNull() }
                .filter { it.optString("tool") == "plan_personal_activity" }
            assertEquals("补正只执行一次真实排程", 1, receipts.size)
            assertTrue(JSONObject(receipts.single().getString("result")).optBoolean("success"))
        } finally {
            server.shutdown()
        }
    }


    @Test fun failedOrderedStepSkipsDependentButKeepsIndependentReceiptAndReplayIdempotent() = runBlocking {
        val actor = role()
        val requestId = "ordered-failure-${UUID.randomUUID()}"
        val independentText = "失败后仍独立发布-${UUID.randomUUID()}"
        val steps = JSONArray()
            .put(JSONObject().put("tool", "unknown_ordered_tool")
                .put("args", JSONObject()))
            .put(JSONObject().put("tool", "publish_moment")
                .put("requiresPreviousSuccess", true)
                .put("args", JSONObject().put("text", "依赖失败动作，不能发布")))
            .put(JSONObject().put("tool", "publish_moment")
                .put("requiresPreviousSuccess", false)
                .put("args", JSONObject().put("text", independentText)))
        val first = OrderedCharacterActions.execute(app, actor, steps,
            requestId = requestId, initialCompleted = true)
        assertEquals(1, first.completedCount)
        assertTrue(first.notes.any { it.contains("未知工具") })
        assertTrue(first.notes.any { it.contains("前一步尚未成功") })
        assertEquals(listOf(independentText),
            MomentsStore.posts.value.filter { it.authorCharacterId == actor }.map { it.content })
        val second = OrderedCharacterActions.execute(app, actor, steps,
            requestId = requestId, initialCompleted = true)
        assertEquals(1, second.completedCount)
        assertEquals("同一 requestId 的重放不得再次发布", listOf(independentText),
            MomentsStore.posts.value.filter { it.authorCharacterId == actor }.map { it.content })
    }


    @Test fun failedPrimaryResultSkipsDependentStepButIndependentSuccessUnlocksItsOwnFollower() = runBlocking {
        val actor = role()
        val requestId = "failed-primary-${UUID.randomUUID()}"
        val independentText = "主动作失败后独立分享-${UUID.randomUUID()}"
        val followupText = "独立分享成功后继续分享-${UUID.randomUUID()}"
        val blockedText = "主动作失败不得发布-${UUID.randomUUID()}"
        val steps = JSONArray()
            .put(JSONObject().put("tool", "publish_moment")
                .put("requiresPreviousSuccess", true)
                .put("args", JSONObject().put("text", blockedText)))
            .put(JSONObject().put("tool", "publish_moment")
                .put("requiresPreviousSuccess", false)
                .put("args", JSONObject().put("text", independentText)))
            .put(JSONObject().put("tool", "publish_moment")
                .put("requiresPreviousSuccess", true)
                .put("args", JSONObject().put("text", followupText)))
        // The primary tool's real failure has already been recorded by its caller.
        // Ordered steps must inherit that result, not assume primary success.
        val first = OrderedCharacterActions.execute(app, actor, steps,
            requestId = requestId, initialCompleted = false)
        assertEquals(2, first.completedCount)
        assertTrue(first.notes.any { it.contains("前一步尚未成功") })
        assertEquals(setOf(independentText, followupText),
            MomentsStore.posts.value.filter { it.authorCharacterId == actor }.map { it.content }.toSet())
        assertEquals(2, MomentsStore.posts.value.count { it.authorCharacterId == actor })
        val replay = OrderedCharacterActions.execute(app, actor, steps,
            requestId = requestId, initialCompleted = false)
        assertEquals(2, replay.completedCount)
        assertEquals(setOf(independentText, followupText),
            MomentsStore.posts.value.filter { it.authorCharacterId == actor }.map { it.content }.toSet())
        assertEquals(2, MomentsStore.posts.value.count { it.authorCharacterId == actor })
    }



    @Test fun newerUserBubbleDefersOnlyUnstartedActionsAndReplayDoesNotDuplicateDeliveredSteps() = runBlocking {
        val actor = role()
        val requestId = "stale-turn-${UUID.randomUUID()}"
        val deliveredText = "旧轮已经真实送达-${UUID.randomUUID()}"
        val deferredText = "新消息后必须重新判断-${UUID.randomUUID()}"
        val steps = JSONArray()
            .put(JSONObject().put("tool", "publish_moment")
                .put("args", JSONObject().put("text", deliveredText)))
            .put(JSONObject().put("tool", "publish_moment")
                .put("requiresPreviousSuccess", true)
                .put("args", JSONObject().put("text", deferredText)))
        var checks = 0
        val interrupted = OrderedCharacterActions.execute(app, actor, steps,
            requestId = requestId, shouldDeferRemaining = { ++checks > 1 })
        assertEquals(1, interrupted.completedCount)
        assertTrue(interrupted.notes.any { it.contains("暂缓") })
        assertEquals(1, MomentsStore.posts.value.count {
            it.authorCharacterId == actor && it.content == deliveredText
        })
        assertEquals(0, MomentsStore.posts.value.count {
            it.authorCharacterId == actor && it.content == deferredText
        })
        assertTrue(SharedExperienceTimeline.eventsByIds(actor,
            listOf("tool-$actor-$requestId:step:0")).isNotEmpty())
        assertTrue(SharedExperienceTimeline.eventsByIds(actor,
            listOf("tool-$actor-$requestId:step:1")).isEmpty())

        // This is an explicit resumption by the caller, not automatic execution
        // after a new user bubble. The already delivered step must be replay-safe.
        val resumed = OrderedCharacterActions.execute(app, actor, steps,
            requestId = requestId)
        assertEquals(2, resumed.completedCount)
        assertEquals(1, MomentsStore.posts.value.count {
            it.authorCharacterId == actor && it.content == deliveredText
        })
        assertEquals(1, MomentsStore.posts.value.count {
            it.authorCharacterId == actor && it.content == deferredText
        })
        assertEquals(2, SharedExperienceTimeline.eventsByIds(actor,
            listOf("tool-$actor-$requestId:step:0",
                "tool-$actor-$requestId:step:1")).size)
    }


    @Test fun realPrimaryRejectionDoesNotEraseIndependentReplyOrItsDurableReceipts() = runBlocking {
        val actor = role()
        val turnId = "primary-and-reply-${UUID.randomUUID()}"
        val chat = MigratedDomainStores.chat.ensureConversation(actor, "主副回执持久归属")
        val primary = JSONObject(ToolRouter.execute(app, actor, "unknown_primary_for_receipt_test",
            JSONObject(), requestId = turnId, autonomousLifeTurn = true))
        assertFalse(primary.optBoolean("success"))
        val replyText = "独立发送的回复-${UUID.randomUUID()}"
        val postText = "独立发布的动态-${UUID.randomUUID()}"
        val steps = JSONArray()
            .put(JSONObject().put("tool", "publish_moment")
                .put("requiresPreviousSuccess", true)
                .put("args", JSONObject().put("text", "失败主动作的依赖项")))
            .put(JSONObject().put("tool", "send_private_message")
                .put("requiresPreviousSuccess", false)
                .put("args", JSONObject().put("text", replyText)))
            .put(JSONObject().put("tool", "publish_moment")
                .put("requiresPreviousSuccess", false)
                .put("args", JSONObject().put("text", postText)))
        suspend fun executeSteps() = OrderedCharacterActions.execute(app, actor, steps,
            requestId = turnId,
            initialCompleted = ToolExecutionStatus.primaryAllowsDependentSteps(
                "unknown_primary_for_receipt_test", primary))
        val first = executeSteps()
        assertEquals(2, first.completedCount)
        assertTrue(first.notes.any { it.contains("前一步尚未成功") })
        val delivered = MigratedDomainStores.chat.messages(chat.id).value
            .count { it.content == replyText }
        assertEquals(1, delivered)
        assertEquals(1, MomentsStore.posts.value.count {
            it.authorCharacterId == actor && it.content == postText
        })
        val receiptIds = listOf("tool-rejected-$actor-$turnId",
            "tool-$actor-$turnId:step:1", "tool-$actor-$turnId:step:2")
        assertEquals(3, SharedExperienceTimeline.eventsByIds(actor, receiptIds).size)
        val second = executeSteps()
        assertEquals(2, second.completedCount)
        assertEquals(delivered, MigratedDomainStores.chat.messages(chat.id).value
            .count { it.content == replyText })
        assertEquals(1, MomentsStore.posts.value.count {
            it.authorCharacterId == actor && it.content == postText
        })
        assertEquals(3, SharedExperienceTimeline.eventsByIds(actor, receiptIds).size)
    }

    @Test fun newUserMessageStartsFreshDecisionWithoutRevivingDeferredOldStep() = runBlocking {
        val actor = role()
        val chat = MigratedDomainStores.chat.ensureConversation(actor, "新输入后重新决策")
        val oldRequest = "old-decision-${UUID.randomUUID()}"
        val freshRequest = "fresh-decision-${UUID.randomUUID()}"
        val alreadySent = "旧决定已执行-${UUID.randomUUID()}"
        val staleUnsent = "旧决定不应复活-${UUID.randomUUID()}"
        val newlyChosen = "新决定本人重新选择-${UUID.randomUUID()}"
        val oldSteps = JSONArray()
            .put(JSONObject().put("tool", "publish_moment")
                .put("args", JSONObject().put("text", alreadySent)))
            .put(JSONObject().put("tool", "publish_moment")
                .put("args", JSONObject().put("text", staleUnsent)))
        var checked = 0
        val partial = OrderedCharacterActions.execute(app, actor, oldSteps,
            requestId = oldRequest, shouldDeferRemaining = { ++checked > 1 })
        assertEquals(1, partial.completedCount)
        assertTrue(partial.notes.any { it.contains("暂缓") })

        // A real new chat input changes the decision boundary. The caller must
        // use a new request id and only execute the role's new decision.
        MigratedDomainStores.chat.sendUserMessage(chat.id, "我刚刚改变主意了")
        val newSteps = JSONArray().put(JSONObject().put("tool", "publish_moment")
            .put("args", JSONObject().put("text", newlyChosen)))
        val fresh = OrderedCharacterActions.execute(app, actor, newSteps,
            requestId = freshRequest)
        assertEquals(1, fresh.completedCount)
        val posts = MomentsStore.posts.value.filter { it.authorCharacterId == actor }
        assertEquals(1, posts.count { it.content == alreadySent })
        assertEquals(0, posts.count { it.content == staleUnsent })
        assertEquals(1, posts.count { it.content == newlyChosen })
        assertEquals(1, SharedExperienceTimeline.eventsByIds(actor,
            listOf("tool-$actor-$oldRequest:step:0")).size)
        assertTrue(SharedExperienceTimeline.eventsByIds(actor,
            listOf("tool-$actor-$oldRequest:step:1")).isEmpty())
        assertEquals(1, SharedExperienceTimeline.eventsByIds(actor,
            listOf("tool-$actor-$freshRequest:step:0")).size)
        OrderedCharacterActions.execute(app, actor, newSteps, requestId = freshRequest)
        assertEquals(1, MomentsStore.posts.value.count {
            it.authorCharacterId == actor && it.content == newlyChosen
        })
    }

    @Test(timeout = 90_000) fun freshUserBatchRunsAnotherRealModelDecisionWithoutReplayingPriorPost() = runBlocking {
        val actor = role()
        val chat = MigratedDomainStores.chat.ensureConversation(actor, "跨批次本人决策")
        val firstPost = "第一轮已送达-${UUID.randomUUID()}"
        val secondPost = "新消息后重新选择-${UUID.randomUUID()}"
        val dependentPost = "失败后不应执行-${UUID.randomUUID()}"
        val requests = java.util.concurrent.atomic.AtomicInteger()
        val modelInputs = java.util.concurrent.CopyOnWriteArrayList<String>()
        val source = MigratedDomainStores.chat.sendUserMessage(chat.id, "先看看今天的事情")
        CharacterInnerLifeStore.recordCausalTransition(
            actor, source.id, appraisal = JSONObject().put("pendingConcern", JSONObject()
                .put("status", "open").put("focus", "想核对最初那条材料")
                .put("whyItMatters", "不想把未经核对的事当事实")),
            innerLife = null, innerThoughtBasis = null, selectedAction = "reply",
            innerThought = "", reason = "",
        )
        val concernId = CharacterInnerLifeStore.snapshot(actor).getJSONArray("openConcerns")
            .getJSONObject(0).getString("id")
        val server = okhttp3.mockwebserver.MockWebServer()
        server.dispatcher = object : okhttp3.mockwebserver.Dispatcher() {
            override fun dispatch(request: okhttp3.mockwebserver.RecordedRequest): okhttp3.mockwebserver.MockResponse {
                val index = requests.incrementAndGet()
                modelInputs.add(request.body.readUtf8())
                val choice = JSONObject().put("action", "tool")
                    .put("tool", "publish_moment")
                    .put("args", JSONObject().put("text", if (index == 1) firstPost else secondPost))
                    .put("reason", "本人按当前消息选择")
                    .put("replyChoice", "silent")
                if (index == 1) {
                    choice.put("steps", JSONArray().put(JSONObject()
                        .put("tool", "favorite_user_message").put("concernId", concernId)
                        .put("args", JSONObject().put("messageId", "missing-source-for-favorite")))
                        .put(JSONObject().put("tool", "publish_moment")
                            .put("requiresPreviousSuccess", true)
                            .put("args", JSONObject().put("text", dependentPost))))
                }
                val decision = if (index <= 2) choice else JSONObject().put("action", "silent")
                    .put("reason", "眼下只想核对已知事实")
                return okhttp3.mockwebserver.MockResponse()
                    .setHeader("Content-Type", "application/json")
                    .setBody(JSONObject().put("choices", JSONArray().put(JSONObject()
                        .put("message", JSONObject().put("content", decision.toString()))
                        .put("finish_reason", "stop"))).toString())
            }
        }
        server.start()
        try {
            val store = com.jiacimu.lulu.ai.LuluAiServices.connectionStore
            val config = store.saveConfiguration(null, "two-real-batches-${UUID.randomUUID()}",
                server.url("/").toString(), "test")
            val archive = store.addArchive(config.id, "test")
            // Importing an archive is storage-only. The test must explicitly
            // assign its mock server to this character's real chat route.
            com.jiacimu.lulu.ai.ScopedModelSelections.initialize(app)
            com.jiacimu.lulu.ai.ScopedModelSelections.selectCharacterChatArchive(
                actor, archive.id, store.library.value)
            OnlineChatBatchStore.cancel(app, actor)
            val first = OnlineChatBatchStore.onUserBubble(app, actor,
                atMillis = System.currentTimeMillis() - OnlineChatBatchStore.QUIET_MILLIS - 1_000L)
            ProactivePerceptionRuntime.runDueCycle(app, "私聊新消息", actor, force = true,
                onlineRevision = first.revision, requiresUnread = true)
            assertEquals(1, MomentsStore.posts.value.count {
                it.authorCharacterId == actor && it.content == firstPost
            })

            val attempt = CharacterInnerLifeStore.snapshot(actor).getJSONArray("openConcerns")
                .getJSONObject(0).getJSONObject("lastOutcome")
            val failureId = attempt.getString("id")
            assertFalse(attempt.getBoolean("success"))
            val failure = SharedExperienceTimeline.eventsByIds(actor, listOf(failureId)).single()
            assertEquals("tool-router", failure.source)
            assertEquals(EventEvidenceKind.ToolResult, failure.evidenceKind)
            val receipt = JSONObject(failure.content)
            assertEquals(concernId, receipt.getString("concernId"))
            assertFalse(JSONObject(receipt.getString("result")).optBoolean("success"))
            assertFalse(MomentsStore.posts.value.any {
                it.authorCharacterId == actor && it.content == dependentPost
            })

            MigratedDomainStores.chat.sendUserMessage(chat.id, "我有新的想法，重新看看")
            val second = OnlineChatBatchStore.onUserBubble(app, actor,
                atMillis = System.currentTimeMillis() - OnlineChatBatchStore.QUIET_MILLIS - 1_000L)
            assertTrue(second.revision > first.revision)
            ProactivePerceptionRuntime.runDueCycle(app, "私聊新消息", actor, force = true,
                onlineRevision = second.revision, requiresUnread = true)
            assertEquals("每个有效新批次应有自己的本人决策", 2, requests.get())
            assertTrue("新批次模型输入应承接旧轮已送达动作的真实回执",
                modelInputs[1].contains(firstPost))
            assertTrue("下一轮须看见该心事的真实失败状态及可追溯回执",
                modelInputs[1].contains("上次实际尝试=执行失败；动作=favorite_user_message；回执ID=$failureId"))
            assertEquals(1, MomentsStore.posts.value.count {
                it.authorCharacterId == actor && it.content == firstPost
            })
            assertEquals(1, MomentsStore.posts.value.count {
                it.authorCharacterId == actor && it.content == secondPost
            })
            ProactivePerceptionRuntime.runDueCycle(app, "私聊新消息", actor, force = true,
                onlineRevision = first.revision, requiresUnread = true)
            assertEquals("过期批次不能再次购买模型请求", 2, requests.get())

            SharedExperienceTimeline.deleteEvent(failureId)
            assertTrue(SharedExperienceTimeline.eventsByIds(actor, listOf(failureId)).isEmpty())
            assertEquals(concernId, CharacterInnerLifeStore.snapshot(actor)
                .getJSONArray("openConcerns").getJSONObject(0).getString("id"))
            MigratedDomainStores.chat.sendUserMessage(chat.id, "只按仍能核对的记录继续")
            val third = OnlineChatBatchStore.onUserBubble(app, actor,
                atMillis = System.currentTimeMillis() - OnlineChatBatchStore.QUIET_MILLIS - 1_000L)
            ProactivePerceptionRuntime.runDueCycle(app, "私聊新消息", actor, force = true,
                onlineRevision = third.revision, requiresUnread = true)
            assertEquals(3, requests.get())
            val afterWithdrawal = modelInputs[2]
            assertTrue(afterWithdrawal.contains("想核对最初那条材料"))
            assertTrue(afterWithdrawal.contains("上次尝试的原回执不可用"))
            assertFalse(afterWithdrawal.contains("上次实际尝试：favorite_user_message"))
            assertFalse(afterWithdrawal.contains("上次实际尝试=执行失败；动作=favorite_user_message"))
            assertFalse(afterWithdrawal.contains("回执ID=$failureId"))
            assertTrue(afterWithdrawal.contains(secondPost))
        } finally {
            server.shutdown()
        }
    }

    @Test(timeout = 90_000) fun failedWakeVoiceSurvivesReloadIntoActualNextModelHttpInput() = runBlocking {
        // The background runtime does not initialize the commitment store; the
        // real app does so before saving commitments. Persist through that
        // initialized store before simulating process recreation.
        CommitmentTaskStore.initialize(app)
        val actor = role()
        val task = CommitmentTaskStore.save(CommitmentTask(
            characterId = actor, goal = "在约定时刻有声叫醒",
            status = CommitmentTaskStatus.WaitingForFeedback,
            dueAt = Instant.now().minusSeconds(2),
            deliveryAction = "start_call",
        ))
        CommitmentCallFeedback.onResponse(task.id, true)
        CommitmentCallFeedback.onVoiceFailed(task.id, "failed-audio-${UUID.randomUUID()}", "tts offline")
        val failed = (CommitmentTaskStore.snapshot().firstOrNull { it.id == task.id } ?: error("Wake task missing after voice callback: " + CommitmentTaskStore.snapshot().joinToString { it.id + ":" + it.status }))
        assertEquals(CommitmentTaskStatus.Blocked, failed.status)
        assertTrue(failed.lastActionResult.contains("播放失败"))

        // Reload the authoritative commitment store before the actual runtime
        // builds the next paid decision request, rather than testing a renderer.
        val field = CommitmentTaskStore.javaClass.getDeclaredField("prefs")
        field.isAccessible = true
        field.set(CommitmentTaskStore, null)
        CommitmentTaskStore.initialize(app)
        assertEquals(failed.lastActionResult,
            (CommitmentTaskStore.snapshot().firstOrNull { it.id == task.id } ?: error("Wake task missing after store reload: " + CommitmentTaskStore.snapshot().joinToString { it.id + ":" + it.status })).lastActionResult)

        val chat = MigratedDomainStores.chat.ensureConversation(actor, "有声失败后的新决定")
        MigratedDomainStores.chat.sendUserMessage(chat.id, "你刚才叫醒我了吗？")
        val server = okhttp3.mockwebserver.MockWebServer()
        val requests = java.util.concurrent.CopyOnWriteArrayList<String>()
        server.dispatcher = object : okhttp3.mockwebserver.Dispatcher() {
            override fun dispatch(request: okhttp3.mockwebserver.RecordedRequest): okhttp3.mockwebserver.MockResponse {
                requests.add(request.body.readUtf8())
                val decision = if (requests.size == 1) {
                    JSONObject().put("action", "tool").put("tool", "publish_moment")
                        .put("args", JSONObject().put("text", "重新核实有声失败后的实际安排"))
                        .put("reason", "上一次有声送达失败，需要保留事实并采取新的独立行动")
                        .put("replyChoice", "silent")
                } else {
                    JSONObject().put("action", "silent")
                        .put("reason", "继续核对新的真实行动回执")
                }
                return okhttp3.mockwebserver.MockResponse()
                    .setHeader("Content-Type", "application/json")
                    .setBody(JSONObject().put("choices", JSONArray().put(JSONObject()
                        .put("message", JSONObject().put("content", decision.toString()))
                        .put("finish_reason", "stop"))).toString())
            }
        }
        server.start()
        try {
            val store = com.jiacimu.lulu.ai.LuluAiServices.connectionStore
            val config = store.saveConfiguration(null, "wake-receipt-${UUID.randomUUID()}",
                server.url("/").toString(), "test")
            val archive = store.addArchive(config.id, "test")
            // Importing an archive is storage-only. The test must explicitly
            // assign its mock server to this character's real chat route.
            com.jiacimu.lulu.ai.ScopedModelSelections.initialize(app)
            com.jiacimu.lulu.ai.ScopedModelSelections.selectCharacterChatArchive(
                actor, archive.id, store.library.value)
            OnlineChatBatchStore.cancel(app, actor)
            val batch = OnlineChatBatchStore.onUserBubble(app, actor,
                atMillis = System.currentTimeMillis() - OnlineChatBatchStore.QUIET_MILLIS - 1_000L)
            ProactivePerceptionRuntime.runDueCycle(app, "私聊新消息", actor, force = true,
                onlineRevision = batch.revision, requiresUnread = true)
            assertEquals("本次新批次只应购买一次本人模型决定", 1, requests.size)
            assertTrue("实际模型请求必须包含原任务ID", requests[0].contains(task.id))
            assertTrue("实际模型请求必须包含真实播放失败", requests[0].contains("播放失败"))
            assertFalse("失败不得伪造成已醒", requests[0].contains("已确认醒来"))
            assertEquals(CommitmentTaskStatus.Blocked,
                (CommitmentTaskStore.snapshot().firstOrNull { it.id == task.id } ?: error("Wake task missing after model decision: " + CommitmentTaskStore.snapshot().joinToString { it.id + ":" + it.status })).status)
            val followUp = "重新核实有声失败后的实际安排"
            assertTrue("新决定必须经真实工具送达", MomentsStore.posts.value.any {
                it.authorCharacterId == actor && it.content == followUp
            })
            assertTrue("新动作必须留下独立时间线证据", SharedExperienceTimeline.all(actor).any {
                it.content.contains(followUp)
            })
            MigratedDomainStores.chat.sendUserMessage(chat.id, "刚才那项新安排实际做成了吗？")
            val next = OnlineChatBatchStore.onUserBubble(app, actor,
                atMillis = System.currentTimeMillis() - OnlineChatBatchStore.QUIET_MILLIS - 1_000L)
            ProactivePerceptionRuntime.runDueCycle(app, "私聊新消息", actor, force = true,
                onlineRevision = next.revision, requiresUnread = true)
            assertEquals("第二个有效批次才应再购买一次决定", 2, requests.size)
            assertTrue("下一轮应同时保留旧失败与新动作", requests[1].contains("播放失败") &&
                requests[1].contains(followUp))
            assertEquals("下一轮明确选择沉默时不得重放前轮工具",
                1, MomentsStore.posts.value.count {
                    it.authorCharacterId == actor && it.content == followUp
                })
            assertEquals("新动态不能把原有声失败责任伪装成已完成",
                CommitmentTaskStatus.Blocked,
                CommitmentTaskStore.snapshot().first { it.id == task.id }.status)
        } finally {
            server.shutdown()
        }
    }
}
