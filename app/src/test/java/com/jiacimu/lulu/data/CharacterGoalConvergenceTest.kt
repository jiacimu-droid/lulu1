package com.jiacimu.lulu.data

import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.time.Instant

@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE, sdk = [28])
class CharacterGoalConvergenceTest {
    private val now = Instant.parse("2026-10-16T08:00:00Z")
    private fun wish() = JSONObject().put("status", "ready")
        .put("aim", "想逗她开心").put("why", "因为今天聊了好玩的事")
        .put("approach", "先讲个小玩笑").put("deliveryAction", "message")

    @Test fun sendingIsNotGoalCompletionAndRetriesCannotDuplicateAttempts() {
        val started = CharacterGoalConvergence.applyRelationship(null, wish(), "source-1", "persona", now = now)
        val originalId = started.getJSONObject(0).getString("id")
        val delivered = CharacterGoalConvergence.applyRelationship(started, wish(),
            "source-1", "persona", "message", true, "actual-message-id", "真的发出去", now)
        assertEquals("waiting_feedback", delivered.getJSONObject(0).getString("status"))
        assertTrue(CharacterMotiveState.isActive(delivered.getJSONObject(0)))
        val repeated = CharacterGoalConvergence.applyRelationship(delivered, wish(),
            "source-1", "persona", "message", true, "actual-message-id", "重试", now)
        assertEquals(1, repeated.length())
        assertEquals(originalId, repeated.getJSONObject(0).getString("id"))
        assertEquals(1, repeated.getJSONObject(0).getJSONArray("outcomes").length())
    }

    @Test fun fleetingThoughtCannotBecomeGoalAndFailedAttemptDoesNotCloseIt() {
        assertEquals(0, CharacterGoalConvergence.applyRelationship(null,
            JSONObject().put("status", "ready").put("aim", "想笑"), "", "persona").length())
        val failed = CharacterGoalConvergence.applyRelationship(null, wish(),
            "source", "persona", "message", false, "real-failure", "网络失败", now)
        assertEquals("active", failed.getJSONObject(0).getString("status"))
        assertEquals("needs_review", failed.getJSONObject(0).getString("lastAttemptOutcome"))
    }

    @Test fun releasingOneWishCannotEraseUnrelatedOtherGoals() {
        val other = JSONObject().put("id", "book").put("aim", "继续读书")
            .put("status", "active")
        val both = CharacterGoalConvergence.applyRelationship(JSONArray().put(other),
            wish(), "source", "persona", now = now)
        val relationId = both.getJSONObject(1).getString("id")
        val released = CharacterGoalConvergence.applyRelationship(both,
            JSONObject().put("status", "released").put("motiveId", relationId)
                .put("reason", "现在她需要休息"), "feedback", "persona", now = now)
        assertEquals("active", released.getJSONObject(0).getString("status"))
        assertEquals("released", released.getJSONObject(1).getString("status"))
    }
}
