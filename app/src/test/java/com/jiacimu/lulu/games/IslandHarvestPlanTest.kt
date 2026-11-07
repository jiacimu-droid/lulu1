package com.jiacimu.lulu.games

import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(manifest=Config.NONE,sdk=[28])
class IslandHarvestPlanTest {
    private fun island(treeCount:Int=3):BlockIslandState {
        val base=BlockIslandRules.newWorld(44,listOf("alice"))
        val w=base.world
        val tiles=MutableList(w.width*w.height) { 0 }
        for(x in 8 until 8+treeCount)tiles[w.index(x,4)]=1
        return base.copy(supplyCaches=emptyList(),animals=emptyList(),
            world=w.copy(tiles=tiles,wood=0,mobs=emptyList(),events=emptyList()))
    }

    private fun step(s:BlockIslandState):BlockIslandState =
        IslandTravel.advance(s.copy(world=s.world.copy(turns=s.world.turns+1)),setOf("alice"))

    @Test fun chosenHarvestWalksCutsPersistsAndStopsWithoutDuplicating() {
        val state=island()
        assertTrue(IslandActorPlay.options(state,"alice").any { it.id=="plan_harvest_wood:3" })
        val chosen=IslandActorPlay.apply(state,"alice",state.world.turns,
            IslandActorChoice("plan_harvest_wood:3","我去给你备木材"))!!
        assertEquals(0,chosen.world.wood)
        assertEquals(3,chosen.companionTravel["alice"]?.harvestRemaining)
        assertEquals(chosen,IslandTravel.advance(chosen,setOf("alice")))
        var current=BlockIslandState.fromJson(chosen.toJson())!!
        assertEquals(chosen.companionTravel,current.companionTravel)
        repeat(20) { if(current.companionTravel.isNotEmpty()) {
            val before=current
            current=step(current)
            val movement=kotlin.math.abs(current.world.friends.first().x-before.world.friends.first().x)+
                kotlin.math.abs(current.world.friends.first().y-before.world.friends.first().y)
            assertTrue(movement<=1)
            assertTrue(current.world.wood-before.world.wood in 0..1)
            assertEquals(current,IslandTravel.advance(current,setOf("alice")))
        } }
        assertTrue(current.companionTravel.isEmpty())
        assertEquals(3,current.world.wood)
        assertTrue((8..10).all { current.world.cell(it,4)==0 })
        val restored=BlockIslandState.fromJson(current.toJson())!!
        assertEquals(current.world.wood,restored.world.wood)
        assertTrue(restored.companionTravel.isEmpty())
    }

    @Test fun cancellationAndUnreachableSupplyNeverInventWood() {
        val state=island(treeCount=2)
        assertEquals(0,step(state).world.wood)
        val chosen=IslandActorPlay.apply(state,"alice",state.world.turns,
            IslandActorChoice("plan_harvest_wood:6","我去采木"))!!
        val cancelled=IslandActorPlay.apply(chosen,"alice",chosen.world.turns,
            IslandActorChoice("stop_travel","不去了"))!!
        assertTrue(cancelled.companionTravel.isEmpty())
        assertEquals(0,step(cancelled).world.wood)
        var current=chosen
        repeat(20) { if(current.companionTravel.isNotEmpty())current=step(current) }
        assertEquals(2,current.world.wood)
        assertTrue(current.companionTravel.isEmpty())
        assertTrue(current.world.events.any { it.contains("剩余4次未执行") })
    }

    @Test fun rejectsForgedHarvestCountPreservingWorldAndOldPlans() {
        val chosen=IslandActorPlay.apply(island(),"alice",0,
            IslandActorChoice("plan_harvest_wood:3",""))!!
        val raw=JSONObject(chosen.toJson())
        raw.getJSONObject("companionTravel").getJSONObject("alice")
            .put("harvestRemaining",99)
        val rejected=BlockIslandState.fromJson(raw.toString())!!
        assertTrue(rejected.companionTravel.isEmpty())
        assertEquals(chosen.world.tiles,rejected.world.tiles)
        val legacy=JSONObject(chosen.toJson())
        legacy.getJSONObject("companionTravel").getJSONObject("alice")
            .put("targetMobId",-1).remove("harvestRemaining")
        assertEquals(0,BlockIslandState.fromJson(legacy.toString())!!
            .companionTravel["alice"]?.harvestRemaining)
    }

    @Test fun builderMustPauseBeforeChoosingHarvest() {
        val state=island()
        val site=IslandHouseProjects.sites(state,"alice").first()
        val started=IslandHouseProjects.choose(state,"alice",
            "start_house:"+site.first+":"+site.second+":warm","我来盖")!!
        assertFalse(IslandActorPlay.options(started,"alice")
            .any { it.id=="plan_harvest_wood:3" })
        assertNull(IslandTravel.choose(started,"alice","plan_harvest_wood:3",""))
        val paused=IslandHouseProjects.choose(started,"alice","pause_house","先备木材")!!
        assertNotNull(IslandTravel.choose(paused,"alice","plan_harvest_wood:3",""))
    }
}
