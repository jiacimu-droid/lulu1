package com.jiacimu.lulu.games

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
class CharacterSoloWorldPlayTest {
    private val context get() = RuntimeEnvironment.getApplication() as android.content.Context
    private val now = Instant.parse("2026-11-13T12:00:00Z")

    @Test fun islandIsLongLivedAndSeparatelyOwnedWithoutTouchingTheOwnersSave() {
        val owner = context.getSharedPreferences("block_island_v2",android.content.Context.MODE_PRIVATE)
        val beforeOwner = owner.all.toMap()
        val executor = CharacterSoloWorldPlay(context)
        val a = "solo-island-a-unique"
        val b = "solo-island-b-unique"
        val first = executor.play(a,"block_island","explore",12,now)
        val one = requireNotNull(executor.readIsland(a))
        assertEquals("block_island", JSONObject(first.details).getString("game"))
        assertTrue(one.world.turns > 0)
        assertEquals(one.world.turns,JSONObject(first.details).getInt("totalTurns"))
        assertEquals(one.world.seed,JSONObject(first.details).getInt("seed"))
        val later = executor.play(a,"block_island","gather",12,now.plusSeconds(600))
        val two = requireNotNull(executor.readIsland(a))
        assertEquals(one.world.seed,two.world.seed)
        assertTrue(two.world.turns>=one.world.turns)
        assertEquals(two.world.turns,JSONObject(later.details).getInt("totalTurns"))
        executor.play(b,"block_island","build",8,now)
        assertEquals(two.world.seed,requireNotNull(executor.readIsland(a)).world.seed)
        assertEquals(beforeOwner,owner.all.toMap())
    }

    @Test fun relicSettlementCannotMintLootWithoutRealExtractionAndIsRoleIsolated() {
        val owner = context.getSharedPreferences("relic_escape_v2",android.content.Context.MODE_PRIVATE)
        val beforeOwner=owner.all.toMap()
        val executor=CharacterSoloWorldPlay(context)
        val id="solo-relic-actual-loot"
        val result=executor.play(id,"relic_escape","safe",128,now)
        val snapshot=executor.readRelic(id)
        val run=requireNotNull(snapshot.run)
        val details=JSONObject(result.details)
        assertEquals("relic_escape",details.getString("game"))
        assertEquals(run.id,details.getString("runId"))
        assertEquals(run.phase.name,details.getString("phase"))
        if(run.phase==RelicPhase.SUCCESS) {
            assertEquals(run.secured.sumOf{it.value},result.score)
            assertEquals(run.secured.map{it.id}.toSet(),
                snapshot.vault.filter{it.id in run.secured.map{loot->loot.id}}.map{it.id}.toSet())
            assertTrue(run.id in snapshot.awarded)
        } else {
            assertEquals(0,result.score)
            assertTrue(snapshot.vault.none{it.id.startsWith(run.id+":")})
            if(run.phase in setOf(RelicPhase.EXPLORE,RelicPhase.DOWNED,RelicPhase.EXTRACT))
                assertTrue(run.id !in snapshot.awarded)
        }
        val other=executor.play("solo-relic-second-role","relic_escape","explore",4,now)
        assertEquals("relic_escape",JSONObject(other.details).getString("game"))
        assertEquals(run.id,executor.readRelic(id).run?.id)
        assertEquals(beforeOwner,owner.all.toMap())
    }

    @Test fun invalidFocusAndTurnBudgetCannotChangePersonalSave() {
        val executor=CharacterSoloWorldPlay(context)
        val id="solo-invalid-settings"
        assertTrue(runCatching{executor.play(id,"block_island","unavailable",12,now)}.isFailure)
        assertTrue(runCatching{executor.play(id,"relic_escape","safe",200,now)}.isFailure)
        assertNull(executor.readIsland(id))
        assertNull(executor.readRelic(id).run)
    }
}
