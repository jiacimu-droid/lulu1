package com.jiacimu.lulu.data

import android.content.Context
import kotlinx.coroutines.runBlocking
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
import java.time.Instant

@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE, sdk = [28])
class CharacterMotiveContinuityTest {
    private val role = "motive-continuity-owner"
    private val other = "motive-continuity-other"
    private val now = Instant.now().minusSeconds(5)
    private lateinit var context: Context

    @Before fun begin() {
        MemoryTestEnvironment.release()
        context = RuntimeEnvironment.getApplication()
        // Robolectric replaces Application per test but retains Kotlin objects.
        // Bind this test's first write to THIS Application's preferences before
        // testing a reload; otherwise the writes go to the previous sandbox app.
        CharacterInnerLifeStore::class.java.getDeclaredField("prefs").apply { isAccessible = true }
            .set(CharacterInnerLifeStore, null)
        com.jiacimu.lulu.LuluRepositories.initialize(context)
        MigratedDomainStores.initialize(context)
        SharedExperienceTimeline.initialize(context)
        CharacterInnerLifeStore.initialize(context)
        CharacterInnerLifeStore.clear(role)
        CharacterInnerLifeStore.clear(other)
        PerceptionWakePlanStore.initialize(context)
        PerceptionWakePlanStore.invalidate(role)
        ProactivePerceptionPolicyStore.initialize(context)
        ProactivePerceptionPolicyStore.update(role) {
            it.copy(enabled = false, intervalValue = 2, intervalUnit = PerceptionIntervalUnit.Hours)
        }
    }

    @After fun finish() { MemoryTestEnvironment.release() }

    private fun goal() = CharacterInnerLifeStore.snapshot(role).getJSONArray("motives").getJSONObject(0)
    private fun change(op: String, at: Instant, fields: JSONObject = JSONObject()) {
        val proposal = JSONObject(fields.toString()).put("op", op)
        if (op != "start") proposal.put("id", goal().getString("id")).put("reason", "根据真实进展调整")
        CharacterInnerLifeStore.observe(role, "motive-input:$at:$op", "这一次真实的目标取舍",
            JSONObject().put("motives", JSONArray().put(proposal)), now = at)
    }

    private fun start(future: Instant? = null) {
        val proposal = JSONObject().put("ref", "sleep-care")
            .put("aim", "陪她安心睡下").put("why", "她刚说想睡却还睡不着")
            .put("successCondition", "有可靠反馈表明这次已经安心睡下，不能仅凭没有回复")
            .put("stopCondition", "她不需要继续时停止；信息不足时安静等待")
            .put("nextStep", "先试一段舒缓的故事")
        if (future != null) proposal.put("reviewAfter", future.toString())
        change("start", now, proposal)
    }

    private fun delivered(at: Instant) {
        val conversation = MigratedDomainStores.chat.ensureConversation(role, "睡前陪伴")
        val sent = MigratedDomainStores.chat.appendCharacterMessage(
            conversation.id, "故事已经实际送达")
        CharacterInnerLifeStore.recordActionResult(role, goal().getString("id"), sent.id,
            "message", true, "实际发送了一段故事", at)
    }

    @Test fun successfulAttemptStillHasPersistentFollowUpAndDoesNotSatisfyTheGoal() {
        start()
        val id = goal().getString("id")
        delivered(now.plusSeconds(30))
        val waiting = goal()
        assertEquals(id, waiting.getString("id"))
        assertEquals("waiting_feedback", waiting.getString("status"))
        assertTrue(waiting.getString("successCondition").contains("不能仅凭没有回复"))
        assertTrue(CharacterMotiveProgress.brief(waiting).contains("完成依据"))
        assertEquals("", CharacterInnerLifeStore.snapshot(other).optString("motives"))
        val review = CharacterMotiveReview.from(waiting)!!
        assertTrue(review.fallback)
        assertEquals(now.plusSeconds(7200), review.dueAt)
        assertFalse(CharacterInnerLifeStore.claimMotiveReview(role, id, review.token, review.dueAt.minusSeconds(1)))
        assertTrue(CharacterInnerLifeStore.claimMotiveReview(role, id, review.token, review.dueAt))
        val next = CharacterMotiveReview.from(goal())!!
        assertTrue(next.dueAt.isAfter(review.dueAt))
        assertFalse(CharacterInnerLifeStore.claimMotiveReview(role, id, review.token, next.dueAt))
        assertEquals("waiting_feedback", goal().getString("status"))
        // Reload exactly as a fresh process does; the next review is not an in-memory timer.
        CharacterInnerLifeStore::class.java.getDeclaredField("prefs").apply { isAccessible = true }
            .set(CharacterInnerLifeStore, null)
        CharacterInnerLifeStore.initialize(context)
        assertEquals(next, CharacterMotiveReview.from(goal()))
    }

    @Test fun authoredWakeTimeSurvivesAnAttemptAndPauseStopsIt() {
        val wake = now.plusSeconds(600)
        start(wake)
        delivered(now.plusSeconds(30))
        assertEquals(wake, CharacterMotiveReview.from(goal())!!.dueAt)
        assertFalse(CharacterMotiveReview.from(goal())!!.fallback)
        change("pause", now.plusSeconds(40))
        assertNull(CharacterMotiveReview.from(goal()))
        assertEquals("paused", goal().getString("status"))
        change("resume", now.plusSeconds(50))
        assertFalse(CharacterMotiveReview.from(goal())!!.fallback)
        change("release", now.plusSeconds(60))
        assertNull(CharacterMotiveReview.from(goal()))
    }

    @Test fun deliveryAndOldOrForeignFeedbackCannotBeUsedAsCompletionEvidence() {
        start()
        delivered(now.plusSeconds(30))
        change("achieve", now.plusSeconds(40), JSONObject().put("outcomeEvidenceId", "sleep-story-delivered"))
        assertEquals("waiting_feedback", goal().getString("status"))
        for ((source, owner, at) in listOf(
            Triple("old-sleep-feedback", role, now.minusSeconds(60)),
            Triple("foreign-sleep-feedback", other, now.plusSeconds(60)),
            Triple("future-sleep-feedback", role, now.plusSeconds(600)),
        )) {
            SharedExperienceTimeline.record(source, owner, "私聊", "用户", "这次睡好了",
                at, triggerExtraction = false, source = "message", evidenceKind = EventEvidenceKind.UserStatement)
            change("achieve", now.plusSeconds(90 + source.length.toLong()), JSONObject().put("outcomeEvidenceId", source))
            assertEquals("waiting_feedback", goal().getString("status"))
        }
        val conversation = MigratedDomainStores.chat.ensureConversation(role, "睡前陪伴")
        val feedback = MigratedDomainStores.chat.sendUserMessage(
            conversation.id, "刚才那次睡得很好")
        change("achieve", now.plusSeconds(201), JSONObject().put("outcomeEvidenceId", feedback.id))
        assertEquals("achieved", goal().getString("status"))
        assertNull(CharacterMotiveReview.from(goal()))
        SharedExperienceTimeline.deleteEvent(feedback.id)
        assertEquals("active", goal().getString("status"))
    }


    @Test fun completePlanConditionsSurvivePersistenceBothContextsAndTailOnlyRevision() {
        val prefix = "先核对材料、现场与真实反馈，不预判执行结果。".repeat(16)
        val fields = JSONObject()
            .put("aim", "核实并继续自己的调查")
            .put("why", "需要保留实际条件而非只保存前半句")
            .put("nextStep", prefix + "只有明确获准后才联系，未获准不发送。")
            .put("waitingFor", prefix + "等待对方确认；没有回复不等于同意。")
            .put("successCondition", prefix + "必须有实际结果证据，单纯通知已送达不算完成。")
            .put("stopCondition", prefix + "对方撤销或材料推翻假设时先停止，不能照旧推进。")
            .put("completionKind", "effect")
            .put("reviewAfter", now.plusSeconds(600).toString())
        change("start", now, fields)
        val id = goal().getString("id")
        val planKeys = listOf("nextStep", "waitingFor", "successCondition", "stopCondition")
        fun assertComplete(expected: JSONObject, at: Instant) {
            val stored = goal()
            assertEquals(id, stored.getString("id"))
            for (key in planKeys) assertEquals(expected.getString(key), stored.getString(key))
            val before = CharacterInnerLifeStore.snapshot(role).toString()
            val projections = listOf(
                CharacterMotiveProgress.brief(stored),
                CharacterInnerLifeStore.compactContext(role, at),
                CharacterInnerLifeStore.context(role, at),
                CharacterMotiveDeliberation.context(JSONArray().put(stored), at),
            )
            for (projection in projections) for (key in planKeys) {
                assertTrue("Missing complete $key", projection.contains(expected.getString(key)))
            }
            val handoff = CharacterMotiveDeliberation.context(JSONArray().put(stored), at)
            val nextStepLines = handoff.lineSequence().filter {
                it.contains("拟议下一步：") || it.contains("曾考虑的下一步：")
            }.toList()
            assertEquals(1, nextStepLines.size)
            assertTrue(nextStepLines.single().contains(expected.getString("nextStep")))
            assertEquals(before, CharacterInnerLifeStore.snapshot(role).toString())
            assertFalse(CharacterInnerLifeStore.context(other, at).contains(prefix))
            assertEquals("effect", stored.getString("completionKind"))
            assertEquals(now.plusSeconds(600), CharacterMotiveReview.from(stored)?.dueAt)
        }
        assertComplete(fields, now)
        // A real process restart must read the complete original plan from disk.
        CharacterInnerLifeStore::class.java.getDeclaredField("prefs").apply { isAccessible = true }
            .set(CharacterInnerLifeStore, null)
        CharacterInnerLifeStore.initialize(context)
        assertComplete(fields, now.plusSeconds(10))

        val revised = JSONObject(fields.toString())
            .put("successCondition", prefix + "修订：原先材料不足，须补齐独立观察后再判断完成。")
        val revisionAt = now.plusSeconds(20)
        change("revise", revisionAt, revised)
        assertComplete(revised, revisionAt)
        assertEquals("motive-input:$revisionAt:revise", goal().getString("planEvidenceId"))
        assertFalse(CharacterInnerLifeStore.compactContext(role, revisionAt)
            .contains(fields.getString("successCondition")))
        // Retraction still follows the original source, rather than retaining a
        // silently clipped condition that appears unchanged under a shared prefix.
        CharacterInnerLifeStore.invalidateEvidence("motive-input:$revisionAt:revise")
        assertFalse(goal().has("successCondition"))
        assertFalse(goal().has("stopCondition"))
        assertEquals(id, goal().getString("id"))
    }


    @Test fun nestedNewGoalActionsReachRealScheduleAndIndependentReceiptsOnce() = runBlocking {
        val person = MigratedDomainStores.characters.create("目标行动交接", "独立、有条理")
        val actor = person.characterId
        DigitalLifeProfileStore.registerNewLife(actor, person.displayName, "数字生命", Instant.now())
        val observedAt = Instant.now()
        val sourceId = "new-goal-source-$actor"
        SharedExperienceTimeline.record(sourceId, actor, "数字世界事件", "现场",
            "本人确实看到一份值得继续研究的材料", observedAt, triggerExtraction = false,
            source = "digital-world", evidenceKind = EventEvidenceKind.Observation)
        val ref = "investigation-this-turn"
        val post = JSONObject().put("tool", "publish_moment")
            .put("args", JSONObject().put("text", "想把刚看到的材料继续弄清楚"))
        val later = JSONObject().put("tool", "plan_personal_activity").put("args", JSONObject()
            .put("title", "稍后继续比较材料").put("intent", "继续刚才的独立问题")
            .put("dueAt", observedAt.plusSeconds(3600).atOffset(java.time.ZoneOffset.UTC).toString())
            .put("activityAction", "publish_moment")
            .put("activityArgs", JSONObject().put("text", "继续整理自己的观察")))
        val decision = JSONObject().put("innerLife", JSONObject().put("motives", JSONArray()
            .put(JSONObject().put("op", "start").put("ref", ref).put("aim", "把这份材料弄清楚")
                .put("why", "本人独立兴趣").put("nextAction", post))))
            .put("steps", JSONArray().put(JSONObject(later.toString()).put("motiveRef", ref)))
        val steps = CharacterThoughtActionHandoff.actions(decision)!!
        CharacterInnerLifeStore.observe(actor, sourceId, "真实观察",
            decision.getJSONObject("innerLife"), now = observedAt)
        val motiveId = CharacterInnerLifeStore.snapshot(actor).getJSONArray("motives")
            .getJSONObject(0).getString("id")
        val unbound = steps.toString()
        val requestId = "new-goal-actions-$actor"
        suspend fun execute() = OrderedCharacterActions.execute(context, actor, steps,
            requestId = requestId, causeEvidenceId = sourceId, autonomousLifeTurn = true)
        val report = execute()
        assertEquals(report.notes.toString(), 2, report.completedCount)
        val plans = RoleDayScheduleStore.plans(context, actor)
        assertEquals(1, plans.size)
        assertEquals(motiveId, plans.single().motiveId)
        val receipts = SharedExperienceTimeline.all(actor).filter { it.source == "tool-router" }
            .map { JSONObject(it.content) }.filter { it.optString("requestId").startsWith(requestId) }
        assertEquals(2, receipts.size)
        assertTrue(receipts.all { it.optString("motiveId") == motiveId &&
            it.optString("causeEvidenceId") == sourceId })
        val goal = CharacterInnerLifeStore.snapshot(actor).getJSONArray("motives").getJSONObject(0)
        assertNotEquals("achieved", goal.getString("status"))
        assertEquals(2, goal.getJSONArray("outcomes").length())
        val beforeReplay = CharacterInnerLifeStore.snapshot(actor).toString()
        assertEquals(2, execute().completedCount)
        assertEquals(1, RoleDayScheduleStore.plans(context, actor).size)
        assertEquals(beforeReplay, CharacterInnerLifeStore.snapshot(actor).toString())
        assertEquals(unbound, steps.toString()) // Runtime binding never rewrites the authored decision.
    }

    @Test fun stepCreationRefsRequireUnambiguousActiveOwnershipAndTheActualInput() {
        fun motive(id: String, source: String) = JSONObject().put("id", id).put("aim", "同名问题")
            .put("status", "active").put("creationRef", "shared-ref").put("evidenceId", source)
        val motives = JSONArray().put(motive("first", "first-input")).put(motive("second", "second-input"))
        val step = JSONObject().put("tool", "plan_personal_activity").put("motiveRef", "shared-ref")
            .put("args", JSONObject().put("title", "原计划"))
        val before = step.toString()
        val bound = CharacterMotiveProgress.bindStepMotive(motives, step, listOf("first-input"))
        assertEquals("first", bound.getString("motiveId"))
        assertEquals("first", bound.getJSONObject("args").getString("motiveId"))
        for ((state, sources) in listOf(
            motives to listOf("unrelated-input"),
            motives to listOf("first-input", "second-input"),
            JSONArray() to listOf("first-input"),
            JSONArray().put(motive("paused", "first-input").put("status", "paused")) to listOf("first-input"),
        )) assertFalse(CharacterMotiveProgress.bindStepMotive(state, step, sources).has("motiveId"))
        assertEquals(before, step.toString())
        val explicit = JSONObject(step.toString()).put("motiveId", "explicit-owned-id")
        assertEquals("explicit-owned-id", CharacterMotiveProgress.bindStepMotive(motives,
            explicit, listOf("first-input")).getString("motiveId"))
    }

    @Test fun sameDecisionReferenceLinksOnlyItsOwnNewGoalAndRevisedCriteriaRetractWithSource() {
        start()
        val id = goal().getString("id")
        val motives = CharacterInnerLifeStore.snapshot(role).getJSONArray("motives")
        assertEquals(id, CharacterMotiveProgress.resolveMotiveId(motives, "", "sleep-care", "motive-input:$now:start"))
        assertEquals("", CharacterMotiveProgress.resolveMotiveId(motives, "", "sleep-care", "another-input"))
        val at = now.plusSeconds(20)
        change("revise", at, JSONObject().put("successCondition", "以她后来明确的真实反馈判断"))
        assertEquals(id, goal().getString("id"))
        CharacterInnerLifeStore.invalidateEvidence("motive-input:$at:revise")
        assertFalse(goal().has("successCondition"))
    }
}
