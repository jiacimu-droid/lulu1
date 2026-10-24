package com.jiacimu.lulu.data

import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.time.Instant
import java.time.ZoneId

@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE, sdk = [28])
class CharacterMotiveReviewTest {
    private fun motive() = JSONObject("""{"id":"g","status":"active","aim":"接着读这本书",
        "nextStep":"看下一章","reviewAfter":"2026-10-18T15:30:00Z","planEvidenceId":"real-source"}""")

    @Test fun revisionPauseAndClaimInvalidateOnlyTheMatchingPlan() {
        val m = motive()
        val original = CharacterMotiveReview.from(m)!!
        m.put("status", "paused")
        assertNull(CharacterMotiveReview.from(m))
        m.put("status", "active").put("reviewedPlan", original.token)
        assertNull(CharacterMotiveReview.from(m))
        m.put("reviewAfter", "2026-10-18T16:30:00Z")
        assertNotEquals(original.token, CharacterMotiveReview.from(m)!!.token)
        m.remove("reviewAfter")
        assertNull(CharacterMotiveReview.from(m))
    }

    @Test fun currentNewMotivesHaveAStableOrderedSchedulingProjection() {
        val first = motive()
        val second = motive().put("id", "later").put("reviewAfter", "2026-10-18T17:00:00Z")
        val state = JSONObject().put("motives", JSONArray().put(second).put(first))
        assertEquals(listOf("g", "later"), CharacterMotiveReview.pending(state).map { it.id })
        assertEquals(CharacterMotiveReview.from(first), CharacterMotiveReview.from(JSONObject(first.toString())))
    }

    @Test fun changedWaitingConditionDoesNotReplayAnAlreadyReviewedPastAppointment() {
        val m = motive()
        val plan = CharacterMotiveReview.from(m)!!
        val now = plan.dueAt.plusSeconds(1)
        m.put("reviewedPlan", plan.token).put("reviewedAt", now.toString())
            .put("waitingFor", "等真实效果，而不是再执行旧动作")
        CharacterMotiveProgress.ensureFollowUp(m, now.plusSeconds(7200), now)
        assertTrue(CharacterMotiveReview.from(m)!!.fallback)
        assertTrue(CharacterMotiveReview.from(m)!!.dueAt.isAfter(now))
        CharacterMotiveProgress.applyPlan(m, JSONObject().put("nextStep", "改用另一种办法"), "revision", now)
        assertFalse(m.has("reviewAfter"))
    }

    @Test fun overnightQuietHoursMoveTheOpportunityToLocalMorning() {
        val due = Instant.parse("2026-10-18T15:30:00Z") // 23:30 in Shanghai
        assertEquals(Instant.parse("2026-10-18T23:00:00Z"), CharacterMotiveReview.eligibleAt(
            due, ProactivePerceptionPolicy(), ZoneId.of("Asia/Shanghai")))
        assertEquals(due, CharacterMotiveReview.eligibleAt(due,
            ProactivePerceptionPolicy(quietHoursEnabled = false), ZoneId.of("Asia/Shanghai")))
    }

    @Test fun oneClaimPersistsAndCoalescesDueGoalsWithoutConsumingFuturePlans() {
        val context = org.robolectric.RuntimeEnvironment.getApplication() as android.content.Context
        // This test seeds the authoritative JSON exactly as process restoration reads it.
        CharacterInnerLifeStore::class.java.getDeclaredField("prefs").apply { isAccessible = true }
            .set(CharacterInnerLifeStore, null)
        CharacterInnerLifeStore.initialize(context)
        val first = motive()
        val second = motive().put("id", "also-due")
        val future = motive().put("id", "future").put("reviewAfter", "2026-10-19T15:30:00Z")
        val state = JSONObject().put("motives", JSONArray().put(first).put(second).put(future))
        context.getSharedPreferences("lulu_inner_life_v1", 0).edit()
            .putString("review-owner", state.toString()).commit()
        val plan = CharacterMotiveReview.from(first)!!
        val now = Instant.parse("2026-10-18T15:30:01Z")
        assertTrue(CharacterInnerLifeStore.claimMotiveReview("review-owner", plan.id, plan.token, now))
        assertFalse(CharacterInnerLifeStore.claimMotiveReview("review-owner", plan.id, plan.token, now))
        val next = CharacterMotiveReview.pending(CharacterInnerLifeStore.snapshot("review-owner"))
        assertEquals(setOf("g", "also-due", "future"), next.map { it.id }.toSet())
        assertTrue(next.filter { it.id != "future" }.all { it.fallback && it.dueAt.isAfter(now) })
        assertFalse(next.single { it.id == "future" }.fallback)
    }
}
