package com.jiacimu.lulu.games

import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(manifest=Config.NONE,sdk=[28])
class IslandTravelTest {
    private val ids=setOf("alice","bob")
    private fun scene(): BlockIslandState {
        val s=BlockIslandRules.newWorld(177,ids.toList())
        val tiles=MutableList(s.world.tiles.size) { 0 }
        tiles[s.world.index(13,5)]=1
        tiles[s.world.index(13,7)]=2
        return s.copy(world=s.world.copy(x=2,y=2,tiles=tiles,mobs=emptyList(),
            friends=listOf(ExpeditionFriend("alice",5,5),ExpeditionFriend("bob",5,7)),
            turns=0,events=emptyList()),structures=emptyMap())
    }
    private fun start(s: BlockIslandState=scene()): BlockIslandState =
        IslandActorPlay.apply(s,"alice",s.world.turns,IslandActorChoice("approach:13:5","我去那片树林看看"))!!
    private fun tick(s: BlockIslandState): BlockIslandState =
        BlockIslandRules.act(s,"move",if(s.world.x==2)3 else 2,2,ids)

    @Test fun oneModelChoiceContinuesAcrossRealTurnsAndStopsBesideItsTarget() {
        var s=start()
        val wood=s.world.wood
        val stone=s.world.stone
        assertTrue("alice" in s.companionTravel)
        repeat(6) { s=tick(s) }
        assertEquals(12,s.world.friends.first().x)
        assertEquals(5,s.world.friends.first().y)
        assertEquals(6,s.world.turns)
        assertTrue(s.companionTravel.isEmpty())
        assertEquals(1,s.world.cell(13,5)) // arriving is not fabricated gathering
        assertEquals(wood,s.world.wood)
        assertEquals(stone,s.world.stone)
        val after=tick(s)
        assertEquals(s.world.friends,after.world.friends)
    }

    @Test fun retriesAndSaveReloadDoNotWalkTwiceInTheSameTurn() {
        val s=start()
        assertEquals(s,IslandTravel.advance(s,ids))
        val next=tick(s)
        val restored=BlockIslandState.fromJson(next.toJson())!!
        assertEquals(next.copy(world=next.world.copy(notice="存档已恢复")),restored)
        assertEquals(restored,IslandTravel.advance(restored,ids))
        assertEquals(8,tick(restored).world.friends.first().x)
    }

    @Test fun changedTargetEndsOnlyTheOriginalTripWithoutInventingAnotherDestination() {
        val s=start()
        val changed=s.copy(world=s.world.copy(tiles=s.world.tiles.toMutableList().also {
            it[s.world.index(13,5)]=0
        }))
        val next=tick(changed)
        assertFalse("alice" in next.companionTravel)
        assertEquals(s.world.friends.first(),next.world.friends.first())
        assertTrue(next.world.events.any { it.contains("原目标已改变") })
        assertTrue(IslandActorPlay.options(next,"alice").any { it.kind=="move" })
    }

    @Test fun blockedPathHasAnHonestFailureAndDoesNotTeleport() {
        val s=start()
        val a=s.world.friends.first()
        val blocked=s.copy(world=s.world.copy(tiles=s.world.tiles.toMutableList().also { tiles ->
            for((x,y) in listOf(a.x+1 to a.y,a.x-1 to a.y,a.x to a.y+1,a.x to a.y-1))
                tiles[s.world.index(x,y)]=3
        }))
        val next=tick(blocked)
        assertTrue(next.companionTravel.isEmpty())
        assertEquals(a,next.world.friends.first())
        assertTrue(next.world.events.any { it.contains("通路受阻") })
    }

    @Test fun roleCanCancelEvenAfterMovingAndCancellationDoesNotConsumeAnotherTurn() {
        val s=start()
        val next=IslandActorPlay.apply(s,"alice",s.world.turns,IslandActorChoice("stop_travel","先不去了"))!!
        assertTrue(next.companionTravel.isEmpty())
        assertEquals(s.world.turns,next.world.turns)
        assertEquals(s.world.friends,next.world.friends)
        assertTrue(next.world.events.last().contains("先不去了"))
        assertNull(IslandActorPlay.apply(s,"bob",s.world.turns,IslandActorChoice("stop_travel","")))
        assertEquals(s.world.friends,tick(next).world.friends)
    }

    @Test fun observationPreservesTripButAnExplicitNewMoveReplacesIt() {
        val s=start().let { it.copy(world=it.world.copy(turns=it.world.turns+1)) }
        val observed=IslandActorPlay.apply(s,"alice",s.world.turns,IslandActorChoice("none",""))!!
        assertEquals(s.companionTravel,observed.companionTravel)
        assertEquals(observed,IslandTravel.advance(observed,ids))
        val moved=IslandActorPlay.apply(s,"alice",s.world.turns,IslandActorChoice("move:5:5",""))!!
        assertTrue(moved.companionTravel.isEmpty())
        assertEquals(5,moved.world.friends.first().x)
    }

    @Test fun noTripWithoutAnActualModelSelectionAndDisablingStopsExistingTrips() {
        val s=scene()
        assertEquals(s.world.friends,tick(tick(s)).world.friends)
        val started=start(s)
        val stopped=IslandTravel.stopAll(started)
        assertTrue(stopped.companionTravel.isEmpty())
        assertEquals(started.world.friends,stopped.world.friends)
        assertEquals(started.world.wood,stopped.world.wood)
        assertEquals(stopped.world.friends,tick(stopped).world.friends)
    }

    @Test fun eachParticipantKeepsOnlyTheirOwnPlanAndLeavingDoesNotEraseOthers() {
        val a=start()
        val both=IslandActorPlay.apply(a,"bob",0,IslandActorChoice("approach:13:7","我去挖矿"))!!
        assertEquals(ids,both.companionTravel.keys)
        val next=tick(both)
        assertEquals(7,next.world.friends.first().x)
        assertEquals(7,next.world.friends[1].x)
        val onlyBob=with(BlockIslandRules) { next.withPartners(listOf("bob")) }
        assertEquals(setOf("bob"),onlyBob.companionTravel.keys)
        assertEquals(next.companionTravel["bob"],onlyBob.companionTravel["bob"])
    }

    @Test fun oldAndMalformedOptionalPlanDataDoNotLoseTheActualIsland() {
        val s=start()
        val old=JSONObject(s.toJson()).also { it.remove("companionTravel") }
        val restoredOld=BlockIslandState.fromJson(old.toString())!!
        assertEquals(s.world.copy(notice="存档已恢复"),restoredOld.world)
        assertTrue(restoredOld.companionTravel.isEmpty())
        val broken=JSONObject(s.toJson()).put("companionTravel",JSONObject()
            .put("alice",JSONObject().put("x",9999)).put("other-person",s.companionTravel.getValue("alice").json()))
        val restored=BlockIslandState.fromJson(broken.toString())!!
        assertEquals(s.world.copy(notice="存档已恢复"),restored.world)
        assertEquals(s.fruit,restored.fruit)
        assertTrue(restored.companionTravel.isEmpty())
    }

    @Test fun downedCompanionCannotContinueAnOldTrip() {
        val s=start()
        val down=s.copy(world=s.world.copy(friends=s.world.friends.map {
            if(it.id=="alice")it.copy(hp=0)else it
        }))
        val next=tick(down)
        assertTrue(next.companionTravel.isEmpty())
        assertEquals(down.world.friends.first(),next.world.friends.first())
    }
}
