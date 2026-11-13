package com.jiacimu.lulu.games

import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE, sdk = [28])
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
        assertEquals(48,s.lastRareSupplyTurn)
        val reloaded=checkNotNull(BlockIslandState.fromJson(s.toJson()))
        assertEquals(s.rareSupplyCaches,reloaded.rareSupplyCaches)
        assertEquals(s.supplyCaches,reloaded.supplyCaches)
        assertEquals(s.lastRareSupplyTurn,reloaded.lastRareSupplyTurn)
        val replay=IslandRareEvents.advance(s)
        assertEquals(s.rareSupplyCaches,replay.rareSupplyCaches) // same turn cannot mint more chests
        val reloadedReplay=IslandRareEvents.advance(reloaded)
        assertEquals(s.rareSupplyCaches,reloadedReplay.rareSupplyCaches)
        assertEquals(48,reloadedReplay.lastRareSupplyTurn)
        // A save written before the turn marker existed must also be safe to reload.
        val legacy=org.json.JSONObject(s.toJson()).apply { remove("lastRareSupplyTurn") }.toString()
        val legacyReload=checkNotNull(BlockIslandState.fromJson(legacy))
        assertEquals(48,legacyReload.lastRareSupplyTurn)
        assertEquals(s.rareSupplyCaches,IslandRareEvents.advance(legacyReload).rareSupplyCaches)
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

    @Test fun laterEligibleTideCanSpawnAgainButNeverTwiceOnThatTurn() {
        val pair=(0..800).firstNotNullOfOrNull { seed ->
            if(!IslandRareEvents.arrives(seed,48) || !IslandRareEvents.arrives(seed,96))
                return@firstNotNullOfOrNull null
            val start=BlockIslandRules.newWorld(seed,emptyList())
            val first=IslandRareEvents.advance(start.copy(world=start.world.copy(turns=48)))
            if(first.rareSupplyCaches.size!=1)return@firstNotNullOfOrNull null
            val next=IslandRareEvents.advance(first.copy(world=first.world.copy(turns=96)))
            next.takeIf{it.rareSupplyCaches.size==2}
        }
        val s=checkNotNull(pair)
        assertEquals(96,s.lastRareSupplyTurn)
        assertEquals(2,s.rareSupplyCaches.size)
        assertEquals(2,IslandRareEvents.advance(s).rareSupplyCaches.size)
        assertEquals(2,IslandRareEvents.advance(checkNotNull(BlockIslandState.fromJson(s.toJson())))
            .rareSupplyCaches.size)
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
