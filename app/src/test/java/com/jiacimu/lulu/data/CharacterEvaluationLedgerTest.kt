package com.jiacimu.lulu.data

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import java.time.Duration
import java.time.Instant

/**
 * Long-term evaluation, phase 1: a weekly local census. The ledger only
 * counts stored facts (motive statuses, thought statuses, concerns,
 * corrections, action receipts) and stores one snapshot per week, so
 * week-over-week deltas are real diffs, not guesses. Nothing here calls
 * a model or judges meaning.
 */
@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE, sdk = [28])
class CharacterEvaluationLedgerTest {

    private fun seed(role: String, at: Instant) {
        CharacterInnerLifeStore.observe(
            role, "eval-1", "用户谈起新书",
            JSONObject().put("motives", JSONArray().put(
                JSONObject().put("op", "start").put("aim", "想读完那本书").put("why", "用户提过"))),
            setOf("user"), at,
        )
        CharacterInnerLifeStore.observe(
            role, "eval-2", "用户谈起另一本书",
            JSONObject().put("motives", JSONArray().put(
                JSONObject().put("op", "start").put("aim", "想整理书架").put("why", "看着乱"))),
            setOf("user"), at,
        )
        CharacterInnerLifeStore.observe(
            role, "eval-t1", "seed eval-t1",
            JSONObject().put("thoughts", JSONArray().put(
                JSONObject().put("thought", "还没想通的琢磨事")
                    .put("impulse", "想聊聊").put("hesitation", "怕打扰"))),
            now = at,
        )
        CharacterInnerLifeStore.observe(
            role, "eval-t2", "seed eval-t2",
            JSONObject().put("thoughts", JSONArray().put(
                JSONObject().put("thought", "已经想通的琢磨事")
                    .put("impulse", "想聊聊").put("hesitation", "怕打扰"))),
            now = at,
        )
        CharacterInnerLifeStore.observe(
            role, "eval-t3", "seed eval-t3",
            JSONObject().put("thoughtUpdates", JSONArray().put(
                JSONObject().put("thought", "已经想通的琢磨事").put("op", "resolve"))),
            now = at,
        )
    }

    @Test
    fun weeklyReportCountsFactsAndRollsOverOnlyWeekly() {
        CharacterInnerLifeStore.initialize(RuntimeEnvironment.getApplication() as Context)
        CharacterEvaluationLedger.initialize(RuntimeEnvironment.getApplication() as Context)
        val role = "eval-week-a"
        CharacterInnerLifeStore.clear(role)
        CharacterEvaluationLedger.clear(role)
        val now = Instant.parse("2026-10-26T12:00:00Z")
        seed(role, now)

        val first = CharacterEvaluationLedger.weeklyReport(role, now)
        assertNotNull(first)
        assertTrue(first!!.contains("进行中2件"))
        assertTrue(first.contains("还留着1条"))
        assertTrue(first.contains("已解决1条"))
        assertTrue(first.contains("暂未衡量"))

        // Same week: no new snapshot, no report.
        assertNull(CharacterEvaluationLedger.weeklyReport(role, now.plus(Duration.ofDays(1))))
        assertNull(CharacterEvaluationLedger.weeklyReport(role, now.plus(Duration.ofDays(6))))

        // Eight days later: a new snapshot with deltas against last week.
        val second = CharacterEvaluationLedger.weeklyReport(role, now.plus(Duration.ofDays(8)))
        assertNotNull(second)
        assertTrue(second!!.contains("进行中2（与上周持平）件"))
        assertTrue(second.contains("还留着1（与上周持平）条"))

        // Census itself is pure counting, independent of the rollover.
        val census = CharacterEvaluationLedger.census(role, now)
        assertEquals(2, census.optInt("activeMotives"))
        assertEquals(1, census.optInt("heldThoughts"))
        assertEquals(1, census.optInt("resolvedThoughts"))

        CharacterInnerLifeStore.clear(role)
        CharacterEvaluationLedger.clear(role)
    }

    @Test
    fun renderWithoutPreviousWeekHasNoDeltaNotes() {
        val current = JSONObject()
            .put("activeMotives", 1).put("pausedMotives", 0).put("waitingMotives", 0)
            .put("achievedMotives", 0).put("releasedMotives", 0)
            .put("heldThoughts", 0).put("resolvedThoughts", 0)
            .put("discardedThoughts", 0).put("evolvedThoughts", 0)
            .put("openConcerns", 0).put("closedConcerns", 0)
            .put("corrections", 0).put("decisions", 0).put("succeededDecisions", 0)
            .put("emotionHistory", 0)
        val text = CharacterEvaluationLedger.render(current, null)
        assertTrue(text.contains("进行中1件"))
        assertFalse(text.contains("与上周持平"))
    }
}
