package com.jiacimu.lulu.games

import org.junit.Assert.*
import org.junit.Test

class IslandActorPlayTest {
    private fun world(): BlockIslandState {
        val original=BlockIslandRules.newWorld(1337,listOf("alice","bob"))
        return original.copy(world=original.world.copy(mobs=emptyList()))
    }

    @Test fun companionChoosesOwnLegalMoveAndDoesNotMovePlayer() {
        val s=world()
        val option=IslandActorPlay.options(s,"alice").first{it.kind=="move"}
        val result=IslandActorPlay.apply(s,"alice",s.world.turns,
            IslandActorChoice(option.id,"我去那边看看"))!!
        assertEquals(option.x,result.world.friends.first().x)
        assertEquals(option.y,result.world.friends.first().y)
        assertEquals(s.world.x,result.world.x)
        assertEquals(s.world.y,result.world.y)
        assertEquals(s.world.turns,result.world.turns)
        assertTrue(result.world.events.last().contains("我去那边看看"))
        assertNotNull(BlockIslandState.fromJson(result.toJson()))
    }

    @Test fun noDuplicateStaleOrInventedAction() {
        val s=world()
        val option=IslandActorPlay.options(s,"alice").first{it.kind=="move"}
        val choice=IslandActorChoice(option.id,"")
        assertNull(IslandActorPlay.apply(s,"alice",s.world.turns+1,choice))
        assertNull(IslandActorPlay.apply(s,"fake",s.world.turns,choice))
        assertNull(IslandActorPlay.apply(s,"alice",s.world.turns,
            IslandActorChoice("give-me-100-wood","")))
        val result=IslandActorPlay.apply(s,"alice",s.world.turns,choice)!!
        assertNull(IslandActorPlay.apply(result,"alice",result.world.turns,choice))
    }

    @Test fun gatheringChangesActualMapAndResourcesOnce() {
        val s=world()
        val a=s.world.friends.first()
        val x=a.x+1;val y=a.y
        val w=s.world.copy(tiles=s.world.tiles.toMutableList().also{it[s.world.index(x,y)]=1})
        val prepared=s.copy(world=w)
        val choice=IslandActorChoice("gather:$x:$y","")
        val result=IslandActorPlay.apply(prepared,"alice",w.turns,choice)!!
        assertEquals(w.wood+1,result.world.wood)
        assertEquals(0,result.world.cell(x,y))
        assertNull(IslandActorPlay.apply(result,"alice",w.turns,choice))
    }

    @Test fun invalidStructuredChoicesAreRejectedNotReinterpreted() {
        assertNull(IslandActorPlay.parse("我去采集"))
        assertNull(IslandActorPlay.parse("""{"actionId":"none"}"""))
        assertEquals(IslandActorChoice("none",""),
            IslandActorPlay.parse("""{"actionId":"none","speech":""}"""))
        val fence = 96.toChar().toString().repeat(3)
        assertEquals(IslandActorChoice("none",""),
            IslandActorPlay.parse(fence+"json\n"+"""{"actionId":"none","speech":""}"""+"\n"+fence))
    }
}
