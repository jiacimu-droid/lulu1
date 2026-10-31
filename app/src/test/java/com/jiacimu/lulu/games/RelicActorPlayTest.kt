package com.jiacimu.lulu.games

import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(manifest=Config.NONE,sdk=[28])
class RelicActorPlayTest {
    private fun run(): RelicRun {
        val s = RelicEscapeWorld.create(77,1,listOf("alice","bob"),1,1)
        return s.copy(tiles=List(s.w*s.h) { 0 },mobs=emptyList(),sites=emptyList(),
            chests=emptyList(),seen=s.tiles.indices.toSet())
    }

    @Test fun ownMovementDoesNotMovePlayerOrAdvanceEnemyClock() {
        val s=run()
        val move=RelicActorPlay.options(s,"alice").first { it.kind=="move" }
        val next=RelicActorPlay.apply(s,"alice",IslandActorChoice(move.id,"我去前面看看"))!!
        assertEquals(move.x,next.allies.first().x)
        assertEquals(move.y,next.allies.first().y)
        assertEquals(s.hero,next.hero)
        assertEquals(s.turns,next.turns)
        assertEquals(s.allies[1],next.allies[1])
        assertEquals("我去前面看看",next.events.last().text)
        assertNotNull(RelicRun.read(next.json()))
    }

    @Test fun modelAndFixedFollowerCannotBothActInSameTurn() {
        val s=run()
        val next=RelicEscapeRules.act(s,"wait",autonomousIds=setOf("alice","bob"))
        assertEquals(s.allies,next.allies)
        assertTrue(next.events.none { it.who=="alice" || it.who=="bob" })
        val move=RelicActorPlay.options(next,"alice").first { it.kind=="move" }
        val acted=RelicActorPlay.apply(next,"alice",IslandActorChoice(move.id,""))!!
        assertNull(RelicActorPlay.apply(acted,"alice",IslandActorChoice(move.id,"")))
        // Physical moves are exhausted, but changing the next goal is a
        // conversation-level choice and never grants a second movement.
        val followUp = RelicActorPlay.options(acted,"alice")
        assertTrue(followUp.any { it.id=="none" })
        assertTrue(followUp.any { it.id=="plan_regroup" })
        assertTrue(followUp.none { it.kind in setOf(
            "move","attack","open","heal","rescue","share",
            "guard_player","regroup","support","approach_chest","rush_rescue") })
        assertTrue(RelicActorPlay.acted(RelicRun.read(acted.json())!!,"alice"))
    }

    @Test fun chestGoesToActualCompanionsBagOnceAndRespectsCapacity() {
        val s=run().copy(chests=listOf(RelicChest(4,4,2,RelicTier.RARE)))
        val choice=IslandActorChoice("open:4:2","")
        val next=RelicActorPlay.apply(s,"alice",choice)!!
        assertTrue(next.chests.single().opened)
        assertEquals(1,next.allies.first().bag.size)
        assertTrue(next.hero.bag.isEmpty())
        assertNull(RelicActorPlay.apply(next,"bob",choice))
        val full=s.copy(allies=s.allies.map { if(it.id=="alice")it.copy(bag=List(8){i->
            RelicLoot("$i","coin",RelicTier.COMMON,1) }) else it })
        assertNull(RelicActorPlay.apply(full,"alice",choice))
    }

    @Test fun hazardsAndRescueChangeRealHealthAndConsumeRealSupplies() {
        val s=run()
        val a=s.allies.first()
        val move=RelicActorPlay.options(s,"alice").first { it.kind=="move" }
        val trapped=s.copy(tiles=s.tiles.toMutableList().also { it[s.index(move.x,move.y)]=2 },
            sites=listOf(RelicSite(7,move.x,move.y,"trap")))
        val next=RelicActorPlay.apply(trapped,"alice",IslandActorChoice(move.id,""))!!
        assertEquals(a.hp-3,next.allies.first().hp)
        assertTrue(next.sites.single().spent)
        val down=s.copy(hero=s.hero.copy(hp=0),phase=RelicPhase.DOWNED)
        val rescued=RelicActorPlay.apply(down,"alice",IslandActorChoice("rescue","起来啦"))!!
        assertEquals(4,rescued.hero.hp)
        assertEquals(RelicPhase.EXPLORE,rescued.phase)
        assertEquals(a.kits-1,rescued.allies.first().kits)
        assertEquals(a.rescues+1,rescued.allies.first().rescues)
    }

    @Test fun invalidStaleFinishedOrOtherActorChoicesNeverInventActions() {
        val s=run()
        assertNull(RelicActorPlay.apply(s,"unknown",IslandActorChoice("none","")))
        assertNull(RelicActorPlay.apply(s,"alice",IslandActorChoice("free-loot","")))
        assertNull(RelicActorPlay.apply(s.copy(phase=RelicPhase.SUCCESS),"alice",IslandActorChoice("none","")))
        val move=RelicActorPlay.options(s,"alice").first { it.kind=="move" }
        val blocked=s.copy(tiles=s.tiles.toMutableList().also { it[s.index(move.x,move.y)]=1 })
        assertNull(RelicActorPlay.apply(blocked,"alice",IslandActorChoice(move.id,"")))
    }

    @Test fun hiddenTreasureAndMonstersNeverEnterModelFacts() {
        val s=run().copy(chests=listOf(RelicChest(8,10,10,RelicTier.PRECIOUS)),
            mobs=listOf(RelicMob(9,10,11,5,1)),seen=setOf(0))
        val facts=org.json.JSONObject(RelicActorPlay.facts(s,"alice",""))
        assertEquals(0,facts.getJSONArray("chests").length())
        assertEquals(0,facts.getJSONArray("mobs").length())
        assertEquals(1,facts.getJSONArray("map").length())
    }
}
