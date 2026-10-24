package com.jiacimu.lulu.data

import android.content.Context
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
    private val now = Instant.parse("2026-10-24T12:00:00Z")
    private lateinit var context: Context

    @Before fun begin() {
        MemoryTestEnvironment.release()
        context = RuntimeEnvironment.getApplication()
        // Robolectric replaces Application per test but retains Kotlin objects.
        // Bind this test's first write to THIS Application's preferences before
        // testing a reload; otherwise the writes go to the previous sandbox app.
        CharacterInnerLifeStore::class.java.getDeclaredField("prefs").apply { isAccessible = true }
            .set(CharacterInnerLifeStore, null)
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
        SharedExperienceTimeline.record("sleep-story-delivered", role, "私聊", "角色",
            "故事已经实际送达", at, triggerExtraction = false,
            source = "message", evidenceKind = EventEvidenceKind.CharacterStatement)
        CharacterInnerLifeStore.recordActionResult(role, goal().getString("id"), "sleep-story-delivered",
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
        SharedExperienceTimeline.record("actual-sleep-feedback", role, "私聊", "用户", "刚才那次睡得很好",
            now.plusSeconds(200), triggerExtraction = false, source = "message", evidenceKind = EventEvidenceKind.UserStatement)
        change("achieve", now.plusSeconds(201), JSONObject().put("outcomeEvidenceId", "actual-sleep-feedback"))
        assertEquals("achieved", goal().getString("status"))
        assertNull(CharacterMotiveReview.from(goal()))
        SharedExperienceTimeline.deleteEvent("actual-sleep-feedback")
        assertEquals("active", goal().getString("status"))
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
