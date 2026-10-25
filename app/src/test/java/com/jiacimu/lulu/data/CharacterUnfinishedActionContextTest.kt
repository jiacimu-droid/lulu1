package com.jiacimu.lulu.data

import android.content.Context
import com.jiacimu.lulu.LuluRepositories
import kotlinx.coroutines.runBlocking
import org.json.JSONArray
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

@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE, sdk = [33])
class CharacterUnfinishedActionContextTest {
    private lateinit var context: Context
    private lateinit var role: String
    private lateinit var origin: String
    private lateinit var motiveId: String
    private lateinit var concernId: String
    private lateinit var now: Instant

    @Before fun start() {
        MemoryTestEnvironment.release()
        context = RuntimeEnvironment.getApplication()
        // This suite creates real persistent schedules. Robolectric's manifest
        // excludes AndroidX Startup, so the WorkManager host must be initialized
        // before any role plan is registered for a wake-up.
        if (runCatching { androidx.work.WorkManager.getInstance(context) }.isFailure) {
            androidx.work.WorkManager.initialize(context,
                androidx.work.Configuration.Builder().build())
        }
        CharacterInnerLifeStore::class.java.getDeclaredField("prefs").apply { isAccessible = true }
            .set(CharacterInnerLifeStore, null)
        LuluRepositories.initialize(context)
        MigratedDomainStores.initialize(context)
        SharedExperienceTimeline.initialize(context)
        CharacterInnerLifeStore.initialize(context)
        CharacterCuriosityRuntime.initialize(context)
        CharacterLifeFollowThroughStore.initialize(context)
        MomentsStore.initialize(context)
        CompanionOnlineStore.initialize(context)
        role = MigratedDomainStores.characters.create("未竟事回归").characterId
        MigratedDomainStores.chat.ensureConversation(role, "未竟事回归")
        now = Instant.now()
        origin = "unfinished-origin-$role"
        val old = now.minusSeconds(3 * 86400)
        SharedExperienceTimeline.record(origin, role, "数字世界观察", "执行器", "真实读到一段有趣的材料",
            old, triggerExtraction = false, source = "digital-world", evidenceKind = EventEvidenceKind.Observation)
        CharacterInnerLifeStore.observe(role, origin, "本人产生了想继续探索的愿望",
            JSONObject().put("motives", JSONArray().put(JSONObject().put("op", "start")
                .put("aim", "把自己真正喜欢的阅读片段分享出去").put("why", "这一段很有意思")
                .put("nextStep", "考虑公开表达自己的看法"))), now = old)
        motiveId = CharacterInnerLifeStore.snapshot(role).getJSONArray("motives").getJSONObject(0).getString("id")
        val appraisal = JSONObject().put("meaning", "这个问题还没想通")
            .put("pendingConcerns", JSONArray().put(JSONObject().put("status", "open")
                .put("focus", "怎样用自己的话表达这个阅读发现").put("whyItMatters", "想表达得更贴切")
                .put("possibleNextStep", "试着写一段自己的短感想")))
        CharacterInnerLifeStore.recordCausalTransition(role, origin, appraisal, null, null,
            "silent", "", "先保留这个未解的问题", old)
        concernId = CharacterInnerLifeStore.snapshot(role).getJSONArray("openConcerns").getJSONObject(0).getString("id")
        CharacterLifeFollowThroughStore.register(role, origin, "数字世界观察", "读到真实的阅读材料", old)
        CharacterLifeFollowThroughStore.recordShareWish(role, JSONObject().put("experienceId", origin)
            .put("status", "held").put("why", "有一段自己的看法想公开分享"))
    }

    @After fun finish() {
        CompanionOnlineStore.goOfflineByChoice(role)
        MemoryTestEnvironment.release()
    }

    @Test fun oldConcernsFailedResearchCuriosityGoalsAndHeldSharesAllReachTheFirstDecision() {
        val research = CharacterLearningAgenda.start(role, JSONObject().put("topic", "表达方法")
            .put("question", "怎样让表达更有自己的特点").put("sourceEvidenceId", origin), now.minusSeconds(7200))
        CharacterLearningAgenda.failed(role, research.getString("id"), "connection closed", now.minusSeconds(3600))
        CharacterCuriosityRuntime.recordInquiry(role, JSONObject().put("topic", "阅读中的另一条线索")
            .put("question", "作者为何选用这个视角").put("why", "还没有弄清")
            .put("nextStep", "继续核对真实材料"), origin, "真实读到阅读材料", now.minusSeconds(60))
        repeat(10) { i ->
            CharacterInnerLifeStore.observe(role, origin, "另一种真实的个人愿望$i",
                JSONObject().put("motives", JSONArray().put(JSONObject().put("op", "start")
                    .put("aim", "探索第${i}种文学表达").put("why", "想看看不同可能"))), now = now)
        }
        assertTrue(CharacterOpenConcernRuntime.recent(
            CharacterInnerLifeStore.snapshot(role).optJSONArray("openConcerns"), now).isEmpty())
        val before = CharacterInnerLifeStore.snapshot(role).toString()
        val choices = CharacterUnfinishedActionContext.candidates(context, role, now)
        assertEquals(setOf("持续目标", "挂心", "正在琢磨", "好奇", "想分享"), choices.map { it.kind }.toSet())
        assertTrue(choices.any { it.kind == "挂心" && it.detail.contains(concernId) })
        assertTrue(choices.any { it.kind == "正在琢磨" && it.detail.contains("learn_topic") && it.detail.contains("connection closed") })
        assertEquals(before, CharacterInnerLifeStore.snapshot(role).toString())
        assertTrue(CharacterUnfinishedActionContext.candidates(context, "unrelated-role", now).isEmpty())
        SharedExperienceTimeline.deleteEvent(origin)
        assertTrue(CharacterUnfinishedActionContext.candidates(context, role, now.plusSeconds(1)).isEmpty())
    }

    @Test fun researchCanBeScheduledInTheExistingDecisionWithoutLosingItsGoal() = runBlocking {
        val goal = CharacterLearningAgenda.start(role, JSONObject().put("topic", "文学表达")
            .put("question", "怎样理解这段材料").put("sourceEvidenceId", origin), now.minusSeconds(60))
        val choices = CharacterUnfinishedActionContext.candidates(context, role, now)
        val research = choices.first { it.kind == "正在琢磨" && it.id == goal.getString("id") }
        assertTrue(research.detail.contains("activityAction=learn_topic"))
        assertTrue(research.detail.contains("activityArgs="))
        assertTrue(research.detail.contains(goal.getString("id")))
        val rendered = CharacterUnfinishedActionContext.render(choices)
        assertTrue(rendered.contains("tool=plan_personal_activity"))
        assertTrue(rendered.contains("motiveId放在args内"))
        assertTrue(rendered.contains("先检查已有日程"))
        assertTrue(rendered.contains("安排日程不代表挂心已解决"))
        CompanionOnlineStore.wakeCharacter(role, CompanionOnlineReason.NewActivity, "安排探索日程", perceiveNow = false)
        val selectedNextAction = JSONObject().put("tool", "plan_personal_activity")
            .put("args", JSONObject().put("title", "稍后继续研究文学表达")
                .put("intent", "想留出时间核对原来的材料")
                .put("dueAt", Instant.now().plusSeconds(3600).toString())
                .put("activityAction", "learn_topic")
                .put("activityArgs", JSONObject().put("op", "study").put("goalId", goal.getString("id"))))
        val authoredDecision = JSONObject().put("action", "message")
            .put("appraisal", JSONObject().put("pendingConcerns", JSONArray().put(JSONObject()
                .put("threadId", concernId).put("status", "revise").put("nextAction", selectedNextAction))))
            .put("steps", JSONArray().put(JSONObject().put("tool", "send_private_message")
                .put("args", JSONObject().put("text", "我晚点再接着看看。"))))
        val actions = CharacterThoughtActionHandoff.actions(authoredDecision)
        try {
            val result = OrderedCharacterActions.execute(context, role, actions, "schedule-research-$role")
            assertTrue(result.notes.toString(), result.anyAccepted)
            val savedPlans = RoleDayScheduleStore.plans(context, role)
            assertEquals(result.notes.joinToString("\n"), 1, savedPlans.size)
            val saved = savedPlans.single()
            assertEquals(goal.getString("id"), JSONObject(saved.activityArgs).getString("goalId"))
            assertEquals("planned", saved.status)
            assertFalse(RoleDayScheduleStore.canBegin(context, role, saved.id, "learn_topic",
                JSONObject(saved.activityArgs), now))
            OrderedCharacterActions.execute(context, role, actions, "schedule-research-$role")
            assertEquals(1, RoleDayScheduleStore.plans(context, role).size)
            val chat = MigratedDomainStores.chat.conversations.value.single { it.characterId == role }
            assertEquals(1, MigratedDomainStores.chat.messages(chat.id).value.count {
                it.sender == LuluChatMessage.Sender.Character && it.content == "我晚点再接着看看。" })
        } finally {
            RoleDayScheduleStore.clearCharacter(context, role)
        }
    }

    @Test fun replayRepairsADailySchedulesProjectionWithoutRepeatingThePostOrAdvancingTwice() = runBlocking {
        val instant = Instant.now()
        val plan = RoleDayScheduleStore.arrange(context, role, "分享阅读发现", "想发表自己对真实材料的看法",
            instant.minusSeconds(180), now = instant.minusSeconds(3600),
            activityAction = "publish_moment", repeat = "daily")
        val args = JSONObject().put("text", "这段真实阅读让我想再核对一次自己的看法。")
            .put("planId", plan.id)
        val request = "daily-receipt-recovery-$role"
        try {
            val first = JSONObject(ToolRouter.execute(context, role, "publish_moment", args, requestId = request))
            assertTrue(first.toString(), first.getBoolean("success"))
            val completed = RoleDayScheduleStore.plans(context, role).single()
            val postCount = MomentsStore.posts.value.count { it.authorCharacterId == role }
            // Simulate death after the real effect/receipt was saved but before plan projection.
            val prefs = context.getSharedPreferences("lulu_role_day_plans_v1", Context.MODE_PRIVATE)
            val plans = JSONArray(prefs.getString("plans", "[]"))
            for (i in 0 until plans.length()) if (plans.getJSONObject(i).optString("id") == plan.id)
                plans.getJSONObject(i).put("status", "due").put("dueAt", plan.dueAt.toString())
                    .put("resultEvidenceId", "").put("resultSummary", "")
            prefs.edit().putString("plans", plans.toString()).commit()
            repeat(2) { ToolRouter.execute(context, role, "publish_moment", args, requestId = request) }
            val repaired = RoleDayScheduleStore.plans(context, role).single()
            assertEquals(completed.dueAt, repaired.dueAt)
            assertEquals(completed.resultEvidenceId, repaired.resultEvidenceId)
            assertEquals("planned", repaired.status)
            assertEquals(postCount, MomentsStore.posts.value.count { it.authorCharacterId == role })
        } finally {
            RoleDayScheduleStore.clearCharacter(context, role)
        }
    }

    @Test fun aPendingReceiptDoesNotResolveTheExpectationAsACompletedExperience() = runBlocking {
        val request = "pending-expectation-$role"
        val receiptId = "tool-$role-$request"
        CharacterInnerLifeStore.beginActivityExpectation(role, receiptId, "read_book",
            JSONObject().put("wanted", "想知道故事的真实后续").put("why", "在意还未解开的疑问"))
        val result = JSONObject().put("success", true).put("status", "pending")
            .put("evidenceId", receiptId).put("summary", "请求已接收，尚未返回内容")
        val execution = JSONObject().put("characterId", role).put("requestId", request)
            .put("tool", "read_book").put("fingerprint", "read_book:{}")
            .put("status", "pending").put("result", result.toString())
            .put("finishedAt", Instant.now().toString())
        context.getSharedPreferences("lulu_tool_executions", Context.MODE_PRIVATE).edit()
            .putString("$role:$request", execution.toString()).commit()
        val replay = JSONObject(ToolRouter.execute(context, role, "read_book", JSONObject(), requestId = request))
        assertEquals("pending", replay.getString("status"))
        val expectation = CharacterInnerLifeStore.snapshot(role).getJSONArray("activityExpectations")
            .getJSONObject(0)
        assertEquals("awaiting_result", expectation.getString("status"))
        assertFalse(expectation.has("evidenceId"))
    }

    private fun steps(postText: String) = JSONArray()
        .put(JSONObject().put("tool", "send_private_message").put("args", JSONObject().put("text", "嗯，我还在听。")))
        .put(JSONObject().put("tool", "publish_moment").put("args", JSONObject().put("text", postText))
            .put("motiveId", motiveId).put("concernId", concernId).put("experienceId", origin))

    @Test fun replyingAndPostingInOneDecisionUseSeparateReceiptsAndKeepTheirLinksOnReplay() = runBlocking {
        CompanionOnlineStore.wakeCharacter(role, CompanionOnlineReason.NewActivity, "真实多步执行", perceiveNow = false)
        val before = MomentsStore.posts.value.count { it.authorCharacterId == role }
        val action = steps("刚读到的这个转折，让我想了很久。")
        val result = OrderedCharacterActions.execute(context, role, action, "reply-and-post-$role")
        assertTrue(result.notes.toString(), result.anyAccepted)
        assertEquals(before + 1, MomentsStore.posts.value.count { it.authorCharacterId == role })
        val post = MomentsStore.posts.value.first { it.authorCharacterId == role }
        val postEvidence = "moment-${post.id}-$role"
        val motive = CharacterInnerLifeStore.snapshot(role).getJSONArray("motives").getJSONObject(0)
        assertEquals(1, motive.getJSONArray("outcomes").length())
        assertEquals(postEvidence, motive.getJSONArray("outcomes").getJSONObject(0).getString("id"))
        assertEquals("waiting_feedback", motive.getString("status"))
        val concern = CharacterInnerLifeStore.snapshot(role).getJSONArray("openConcerns").getJSONObject(0)
        assertEquals(postEvidence, concern.getJSONObject("lastOutcome").getString("id"))
        val share = CharacterLifeFollowThroughStore.auditSnapshot(role).single { it.getString("id") == origin }
        assertEquals("shared", share.getString("shareStatus"))
        assertEquals(postEvidence, share.getString("shareReceiptId"))
        CharacterInnerLifeStore::class.java.getDeclaredField("prefs").apply { isAccessible = true }
            .set(CharacterInnerLifeStore, null)
        CharacterInnerLifeStore.initialize(context)
        OrderedCharacterActions.execute(context, role, action, "reply-and-post-$role")
        assertEquals(before + 1, MomentsStore.posts.value.count { it.authorCharacterId == role })
        assertEquals(1, CharacterInnerLifeStore.snapshot(role).getJSONArray("motives")
            .getJSONObject(0).getJSONArray("outcomes").length())
        val chat = MigratedDomainStores.chat.conversations.value.single { it.characterId == role }
        assertEquals(1, MigratedDomainStores.chat.messages(chat.id).value.count {
            it.sender == LuluChatMessage.Sender.Character && it.content.contains("我还在听") })
        CharacterInnerLifeStore.observe(role, origin, "本人明确只想完成一次公开表达",
            JSONObject().put("motives", JSONArray().put(JSONObject().put("op", "revise").put("id", motiveId)
                .put("reason", "完成标准是实际发布文字")
                .put("completionKind", "execution").put("successCondition", "这段文字已真实发布到我的朋友圈"))))
        CharacterInnerLifeStore.observe(role, origin, "这次公开表达已经有实际发布回执",
            JSONObject().put("motives", JSONArray().put(JSONObject().put("op", "achieve").put("id", motiveId)
                .put("reason", "已核对本人这条朋友圈的实际发布结果").put("outcomeEvidenceId", postEvidence))))
        assertEquals("achieved", CharacterInnerLifeStore.snapshot(role).getJSONArray("motives")
            .getJSONObject(0).getString("status"))
    }

    @Test fun failedSidePostCannotBeCreditedToTheSuccessfulChat() = runBlocking {
        CompanionOnlineStore.wakeCharacter(role, CompanionOnlineReason.NewActivity, "失败回执隔离", perceiveNow = false)
        val result = OrderedCharacterActions.execute(context, role, steps(""), "failed-post-$role")
        assertTrue(result.anyAccepted) // only the independently chosen chat succeeded
        assertFalse(MomentsStore.posts.value.any { it.authorCharacterId == role })
        val concern = CharacterInnerLifeStore.snapshot(role).getJSONArray("openConcerns").getJSONObject(0)
        assertFalse(concern.getJSONObject("lastOutcome").getBoolean("success"))
        val motive = CharacterInnerLifeStore.snapshot(role).getJSONArray("motives").getJSONObject(0)
        assertEquals("needs_review", motive.getString("lastAttemptOutcome"))
        assertEquals("held", CharacterLifeFollowThroughStore.auditSnapshot(role)
            .single { it.getString("id") == origin }.getString("shareStatus"))
    }
}
