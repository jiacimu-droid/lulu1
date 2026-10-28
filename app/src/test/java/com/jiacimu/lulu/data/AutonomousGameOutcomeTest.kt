package com.jiacimu.lulu.data

import com.jiacimu.lulu.games.LuluGameStore
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import java.time.Instant

@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE, sdk = [28])
class AutonomousGameOutcomeTest {
    @Test fun targetAndResultAreReconciledWithActualCardMovesAndPersistedTogether() {
        val context = RuntimeEnvironment.getApplication() as android.content.Context
        val games = LuluGameStore(context)
        val now = Instant.parse("2026-10-16T12:00:00Z")
        val first = games.playAutonomousGame("actual-solo-target", "memory_match", now, targetScore = 900)!!
        val data = JSONObject(first.detailsJson)
        val turns = data.getJSONArray("turns")
        assertEquals(6, (0 until turns.length()).count { turns.getJSONObject(it).getBoolean("matched") })
        assertEquals((1200 - (turns.length() - 6).coerceAtLeast(0) * 60).coerceAtLeast(120), first.score)
        assertEquals(900, data.getInt("targetScore"))
        assertEquals(first.score >= 900, data.getBoolean("targetReached"))
        val stored = games.state.value.records.first { it.id == first.recordId }
        assertEquals(first.detailsJson, stored.detailsJson)
        assertFalse(stored.playedWithCharacter)
        val second = games.playAutonomousGame("actual-solo-target", "memory_match", now.plusSeconds(700), 120)!!
        assertTrue(JSONObject(second.detailsJson).getBoolean("targetReached"))
        assertEquals(first.score, JSONObject(second.detailsJson).getInt("previousBest"))
        assertTrue(runCatching { games.playAutonomousGame("actual-solo-target", "memory_match", now, 5000) }.isFailure)
    }

    @Test fun yachtDiceUsesThirteenLegalRoundsAndPersistsEveryRoll() {
        val context = RuntimeEnvironment.getApplication() as android.content.Context
        val games = LuluGameStore(context)
        val now = Instant.parse("2026-10-28T10:00:00Z")
        val played = games.playAutonomousGame("solo-yacht-record", "yacht_dice", now)!!
        val details = JSONObject(played.detailsJson)
        val turns = details.getJSONArray("turns")
        assertEquals("yacht_dice", details.getString("game"))
        assertEquals(13, turns.length())
        val categories = mutableSetOf<String>()
        var subtotal = 0
        for (index in 0 until turns.length()) {
            val turn = turns.getJSONObject(index)
            assertTrue(categories.add(turn.getString("category")))
            val rolls = turn.getJSONArray("rolls")
            assertEquals(3, rolls.length())
            for (r in 0 until rolls.length()) {
                val dice = rolls.getJSONArray(r)
                assertEquals(5, dice.length())
                for (d in 0 until dice.length()) assertTrue(dice.getInt(d) in 1..6)
            }
            subtotal += turn.getInt("points")
            assertEquals(subtotal, turn.getInt("total"))
        }
        assertEquals(subtotal, played.score)
        assertEquals(played.detailsJson, games.state.value.records.first { it.id == played.recordId }.detailsJson)
        assertTrue(runCatching { games.playAutonomousGame("solo-yacht-record", "yacht_dice", now, 900) }.isFailure)
    }
}
