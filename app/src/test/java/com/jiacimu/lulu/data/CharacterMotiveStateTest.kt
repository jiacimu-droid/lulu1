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
            minutesSinceInteraction = 85,
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
