package com.jiacimu.lulu.games

import org.junit.Assert.*
import org.junit.Test

class IslandRareEventsTest {
    @Test fun rareTideEventSpawnsPhysicalReachableSaveAndAwardsOnlyWhenOpened() {
        val spawned=(0..500).firstNotNullOfOrNull { seed ->
            if(!IslandRareEvents.arrives(seed,48))return@firstNotNullOfOrNull null
            val world=BlockIslandRules.newWorld(seed,emptyList())
            val nearNight=world.copy(world=world.world.copy(turns=48))
            val next=IslandRareEvents.advance(nearNight)
            next.takeIf { it.rareSupplyCaches.isNotEmpty() }
        }
        assertNotNull("Expected a deterministic possible rare tide event",spawned)
        val s=checkNotNull(spawned)
        assertEquals(1,s.rareSupplyCaches.size)
        val rare=s.rareSupplyCaches.single()
        assertTrue(rare in s.supplyCaches)
        assertTrue(rare !in s.openedSupplies)
        assertEquals(s.rareSupplyCaches,BlockIslandState.fromJson(s.toJson())?.rareSupplyCaches)
        assertEquals(s.supplyCaches,BlockIslandState.fromJson(s.toJson())?.supplyCaches)
        val replay=IslandRareEvents.advance(s)
        assertEquals(1,replay.rareSupplyCaches.size) // same turn may not duplicate the chest
        val w=s.world
        val x=rare%w.width
        val y=rare/w.width
        val far=BlockIslandRules.act(s,"salvage",x,y)
        assertEquals(s.openedSupplies,far.openedSupplies)
        assertEquals(s.crystals,far.crystals)
        val neighbor=listOf(x+1 to y,x-1 to y,x to y+1,x to y-1)
            .first { (nx,ny) -> BlockIslandRules.isWalkable(s,nx,ny) &&
                w.mobs.none{it.hp>0 && it.x==nx && it.y==ny} &&
                s.animals.none{it.x==nx && it.y==ny} }
        val arrived=s.copy(world=w.copy(x=neighbor.first,y=neighbor.second))
        val opened=BlockIslandRules.act(arrived,"salvage",x,y)
        assertTrue(rare in opened.openedSupplies)
        assertEquals(arrived.crystals+1,opened.crystals)
        assertTrue(opened.world.wood>=arrived.world.wood+6)
        assertTrue(opened.world.stone>=arrived.world.stone+4)
        val twice=BlockIslandRules.act(opened,"salvage",x,y)
        assertEquals(opened.crystals,twice.crystals)
        assertEquals(opened.openedSupplies,twice.openedSupplies)
    }

    @Test fun noRareChestAndNoGemOnOrdinaryTurn() {
        val start=BlockIslandRules.newWorld(999,emptyList())
        val notNight=start.copy(world=start.world.copy(turns=47))
        val next=IslandRareEvents.advance(notNight)
        assertEquals(notNight.supplyCaches,next.supplyCaches)
        assertEquals(notNight.crystals,next.crystals)
        assertEquals(notNight.rareSupplyCaches,next.rareSupplyCaches)
    }
}
