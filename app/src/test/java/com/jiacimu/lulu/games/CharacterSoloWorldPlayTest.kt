package com.jiacimu.lulu.games

import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import java.time.Instant
import java.util.UUID

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
    @Test fun relicVisualFramesComeOnlyFromRealCommittedTurnsAndReplayIsReadOnly() {
        val actor = "solo-relic-visual-proof"
        val executor = CharacterSoloWorldPlay(context)
        val segment = executor.play(actor, "relic_escape", "safe", 128, now.plusSeconds(700))
        val details = JSONObject(segment.details)
        val physical = requireNotNull(executor.readRelic(actor).run)
        val frames = requireNotNull(RelicSoloReplay.read(segment.details))
        assertEquals(details.getJSONArray("replayFrames").length() + 1, frames.size)
        assertTrue(frames.size >= 2)
        assertEquals(details.getString("runId"), frames.first().id)
        assertEquals(physical.id, frames.last().id)
        assertEquals(physical.turns, frames.last().turns)
        assertEquals(physical.hero, frames.last().hero)
        assertEquals(physical.chests, frames.last().chests)
        assertEquals(physical.mobs, frames.last().mobs)
        assertEquals(physical.seen, frames.last().seen)
        assertTrue(frames.zipWithNext().all { (before, after) ->
            after.turns >= before.turns &&
                after.turns <= before.turns + 1 &&
                (after.turns > before.turns || after.phase != before.phase)
        })
        val durable = executor.readRelic(actor).json().toString()
        repeat(3) { assertNotNull(RelicSoloReplay.read(segment.details)) }
        assertEquals(durable, executor.readRelic(actor).json().toString())
        // Old records lack frames. Never reconstruct unobserved moves or pretend
        // a final snapshot proves what happened across the missing history.
        assertNull(RelicSoloReplay.read("""{"game":"relic_escape","events":[]}"""))
        val tampered = JSONObject(segment.details)
        tampered.getJSONArray("replayFrames").getJSONObject(0)
            .put("turns", frames.first().turns + 999)
        assertNull(RelicSoloReplay.read(tampered.toString()))
    }


    @Test fun finishedExplorationFallsBackToRealHarvestRatherThanReportingNoActions() {
        val actor = "solo-exhausted-explore-${UUID.randomUUID()}"
        val initial = BlockIslandRules.newWorld(173, emptyList())
        val w = initial.world
        val tiles = List(w.width * w.height) { i ->
            val x = i % w.width
            val y = i / w.width
            if (x == 0 || y == 0 || x == w.width-1 || y == w.height-1) 8 else 0
        }.toMutableList()
        tiles[w.index(4,5)] = 7
        tiles[w.index(7,5)] = 1
        val save = initial.copy(
            world = w.copy(tiles=tiles, x=4, y=5, mobs=emptyList()),
            supplyCaches=emptyList(), openedSupplies=emptySet(), animals=emptyList(),
            awardedChallenges=IslandExpeditionTrail.landmarks.map{it.id}.toSet(),
        )
        val prefs=context.getSharedPreferences("character_solo_world_v1",
            android.content.Context.MODE_PRIVATE)
        assertTrue(prefs.edit().putString("island:"+actor,save.toJson()).commit())
        val executor=CharacterSoloWorldPlay(context)
        val result=executor.play(actor,"block_island","explore",8,now)
        val after=requireNotNull(executor.readIsland(actor))
        assertEquals(8,JSONObject(result.details).getInt("sessionTurns"))
        assertEquals(after.world.turns,JSONObject(result.details).getInt("totalTurns"))
        assertTrue("Must physically gather instead of merely wandering",
            after.world.wood >= 2 && after.world.events.any{it.contains("伐木")})
        assertEquals(0,after.world.cell(7,5))
    }

    @Test fun depletedWildlifeFocusCanScoutYetFullyBlockedMapCannotFabricateTurns() {
        val actor = "solo-barren-scout-${UUID.randomUUID()}"
        val initial=BlockIslandRules.newWorld(271,emptyList())
        val w=initial.world
        val tiles=List(w.width*w.height){i->
            val x=i%w.width
            val y=i/w.width
            if(x==0 || y==0 || x==w.width-1 || y==w.height-1)8 else 0
        }.toMutableList()
        tiles[w.index(4,5)]=7
        val base=initial.copy(
            world=w.copy(tiles=tiles,x=4,y=5,mobs=emptyList()),
            supplyCaches=emptyList(),openedSupplies=emptySet(),animals=emptyList(),
            awardedChallenges=IslandExpeditionTrail.landmarks.map{it.id}.toSet(),
        )
        val prefs=context.getSharedPreferences("character_solo_world_v1",
            android.content.Context.MODE_PRIVATE)
        assertTrue(prefs.edit().putString("island:"+actor,base.toJson()).commit())
        val executor=CharacterSoloWorldPlay(context)
        val walk=executor.play(actor,"block_island","wildlife",6,now)
        val after=requireNotNull(executor.readIsland(actor))
        assertEquals(6,JSONObject(walk.details).getInt("sessionTurns"))
        assertTrue(after.world.events.any{it.contains("走到了")})
        // The failure guard still rejects a save with literally no legal moves.
        val trapped="solo-blocked-${UUID.randomUUID()}"
        val closed=base.copy(world=base.world.copy(
            tiles=List(w.width*w.height){8}.toMutableList().also{it[w.index(4,5)]=7}))
        val raw=closed.toJson()
        assertTrue(prefs.edit().putString("island:"+trapped,raw).commit())
        assertTrue(runCatching{
            executor.play(trapped,"block_island","explore",4,now)
        }.isFailure)
        assertEquals(raw,prefs.getString("island:"+trapped,null))
        val settled="solo-settled-${UUID.randomUUID()}"
        val finished=base.copy(world=base.world.copy(finished=true))
        val finishedRaw=finished.toJson()
        assertTrue(prefs.edit().putString("island:"+settled,finishedRaw).commit())
        assertTrue(runCatching{
            executor.play(settled,"block_island","explore",4,now)
        }.isFailure)
        assertEquals(finishedRaw,prefs.getString("island:"+settled,null))
    }
}
