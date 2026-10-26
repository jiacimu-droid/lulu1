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
class CharacterMotiveStateTest {
    @Test fun currentCueRecallsAnOtherwiseHiddenWishWithoutConsumingOrExecutingIt() {
        val now = Instant.parse("2026-10-26T12:00:00Z")
        val motives = JSONArray()
        repeat(20) { index -> motives.put(JSONObject().put("id", "wish-$index")
            .put("aim", "独立计划编号${index}").put("status", "active")) }
        val initial = CharacterMotiveState.workingSet(motives, now)
        val hidden = CharacterMotiveState.active(motives).first { it !in initial }
        hidden.put("waitingFor", "再次聊到海边灯塔时")
            .put("nextStep", "考虑分享那张尚未发出的灯塔速写")
        val before = motives.toString()
        val selected = CharacterMotiveState.workingSet(motives, now,
            query = "当前输入：海边灯塔的灯又亮了")
        assertEquals(6, selected.size)
        assertTrue(selected.any { it.optString("id") == hidden.optString("id") })
        assertEquals(before, motives.toString())
        assertEquals(initial.map { it.optString("id") },
            CharacterMotiveState.workingSet(motives, now, query = "火星陨石").map { it.optString("id") })
    }

    @Test fun contextualRecallKeepsDueAndImportantWishesAndNeverRevivesClosedOnes() {
        val now = Instant.parse("2026-10-26T12:00:00Z")
        val motives = JSONArray()
            .put(JSONObject().put("id", "due").put("aim", "等到复查时重新考虑")
                .put("reviewAfter", now.minusSeconds(60).toString()))
            .put(JSONObject().put("id", "important").put("aim", "重要的独立打算").put("priority", 3))
        repeat(9) { index -> motives.put(JSONObject().put("id", "cue-$index")
            .put("aim", "海边灯塔计划${index}").put("status", "active")) }
        listOf("paused", "achieved", "released").forEach { status ->
            motives.put(JSONObject().put("id", status).put("aim", "海边灯塔")
                .put("status", status))
        }
        val selected = CharacterMotiveState.workingSet(motives, now, query = "海边灯塔")
            .map { it.optString("id") }
        assertEquals(6, selected.size)
        assertTrue("due" in selected)
        assertTrue("important" in selected)
        assertTrue(selected.any { it.startsWith("cue-") })
        assertFalse(selected.any { it in listOf("paused", "achieved", "released") })
        assertTrue(CharacterMotiveState.workingSet(motives, now, 0, "海边灯塔").isEmpty())
    }

    @Test fun cueSelectionCannotHideAnyDuePlanThePerceptionOwnerWillAcknowledge() {
        val now = Instant.parse("2026-10-26T12:00:00Z")
        val motives = JSONArray()
        repeat(10) { index -> motives.put(JSONObject().put("id", "due-$index")
            .put("aim", "待复查的计划${index}").put("reviewAfter", now.minusSeconds(60L + index).toString())) }
        repeat(20) { index -> motives.put(JSONObject().put("id", "cue-$index")
            .put("aim", "海边灯塔计划${index}")) }
        val baselineDue = CharacterMotiveState.workingSet(motives, now)
            .filter { CharacterMotiveReview.from(it)?.dueAt?.isAfter(now) == false }
            .map { it.getString("id") }
        val contextual = CharacterMotiveState.workingSet(motives, now, query = "海边灯塔")
        assertEquals(6, contextual.size)
        assertTrue(contextual.map { it.getString("id") }.containsAll(baselineDue))
        assertTrue(contextual.any { it.getString("id").startsWith("cue-") })
    }


    @Test fun savedWishesRotateAcrossAwakeWindowsInsteadOfStallingUntilTomorrow() {
        val start = Instant.parse("2026-10-26T10:00:00Z")
        val motives = JSONArray()
        repeat(20) { index -> motives.put(JSONObject()
            .put("id", "wish-$index").put("aim", "长期想做的第$index件事")
            .put("status", "active")) }
        val before = motives.toString()
        val first = CharacterMotiveState.workingSet(motives, start)
            .map { it.getString("id") }
        val next = CharacterMotiveState.workingSet(motives, start.plusSeconds(300L))
            .map { it.getString("id") }
        assertEquals(6, first.size)
        assertEquals("same moment must have stable context", first,
            CharacterMotiveState.workingSet(motives, start.plusSeconds(1L))
                .map { it.getString("id") })
        assertNotEquals("awake turns must not show the same six all day", first, next)
        val seen = (0..50).flatMap { pulse ->
            CharacterMotiveState.workingSet(motives, start.plusSeconds(pulse * 300L))
                .map { it.getString("id") }
        }.toSet()
        assertEquals(20, seen.size)
        assertEquals("attention must never mutate persistent wishes", before, motives.toString())
    }

    @Test fun lowFrequencyReviewsDoNotGetStuckOnOneModuloOfWishes() {
        val start = Instant.parse("2026-10-26T10:00:00Z")
        val motives = JSONArray()
        repeat(12) { index -> motives.put(JSONObject()
            .put("id", "wish-$index").put("aim", "独立愿望$index")
            .put("status", "active")) }
        val seen = (0..14).flatMap { day ->
            CharacterMotiveState.workingSet(motives, start.plusSeconds(day * 86400L))
                .map { it.getString("id") }
        }.toSet()
        assertEquals("fixed-hour background wakeups also rotate", 12, seen.size)
    }

    @Test fun suspendedAndReleasedWishesNeverBecomeActiveInAnotherSurface() {
        val motives = JSONArray("""[
            {"id":"active","aim":"继续学习","status":"active","priority":3},
            {"id":"paused","aim":"暂缓去公园","status":"paused","priority":3},
            {"id":"released","aim":"已经放弃","op":"release","priority":3},
            {"id":"pausedProposal","aim":"刚刚决定暂停","op":"pause","priority":3}
        ]""")
        assertEquals(listOf("active"), CharacterMotiveState.active(motives)
            .map { it.optString("id") })
        assertEquals(setOf("paused", "pausedProposal"), CharacterMotiveState.paused(motives)
            .map { it.optString("id") }.toSet())
        assertEquals(listOf("继续学习"),
            CharacterActionFollowThroughPolicy.pendingChoices(
                JSONObject().put("motives", motives), null, null, ""))
    }

    @Test fun continuityCanShareTimeAndActualWordsWithoutReinjectingMotives() {
        val state = CharacterContinuityRuntime.Snapshot(
            previousInteractionAt = Instant.parse("2026-10-01T12:00:00Z"),
            minutesSinceInteraction = 8L * 60,
            lastUserStatement = null,
            lastCharacterStatement = null,
            preferredAddress = "宝宝",
            addressIsManual = true,
            activeMotiveAims = listOf("回来看小说"),
        )
        val full = CharacterContinuityRuntime.render(state)
        val gateway = CharacterContinuityRuntime.render(state, includeMotives = false)
        assertTrue(full.contains("回来看小说"))
        assertFalse(gateway.contains("回来看小说"))
        assertTrue(gateway.contains("宝宝"))
        assertTrue(gateway.contains("时间确实过去了一段"))
    }
}
