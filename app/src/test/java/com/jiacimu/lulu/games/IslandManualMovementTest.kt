package com.jiacimu.lulu.games

import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE, sdk = [28])
class IslandManualMovementTest {
    @Test fun collidingWithTreeStoneCrystalsAndChestNeverCollectsOrOpens() {
        val original=BlockIslandRules.newWorld(118,emptyList())
        val index=original.world.index(5,5)
        val seed=(1..100).first { IslandProgression.crystalVein(it,index) }
        val start=BlockIslandRules.newWorld(seed,emptyList())
        val tiles=start.world.tiles.toMutableList().also {it[index]=2}
        val ore=start.copy(animals=emptyList(),supplyCaches=emptyList(),
            world=start.world.copy(tiles=tiles,wood=4,stone=2,mobs=emptyList()))
        assertNull(IslandManualMovement.destination(ore,1,0))
        assertEquals(4,ore.world.wood)
        assertEquals(2,ore.world.stone)
        assertEquals(0,ore.crystals)
        assertFalse(islandTargetAction(ore,5 to 5).enabled)
        assertNull(IslandManualMovement.destination(ore.copy(tools=setOf("pickaxe")),1,0))
        assertEquals("gather",islandTargetAction(ore.copy(tools=setOf("pickaxe")),5 to 5).action)
        val wood=ore.copy(world=ore.world.copy(
            tiles=ore.world.tiles.toMutableList().also{it[index]=1}))
        assertNull(IslandManualMovement.destination(wood,1,0))
        assertTrue(islandTargetAction(wood,5 to 5).enabled)
        val cache=ore.copy(supplyCaches=listOf(index),openedSupplies=emptySet(),
            world=ore.world.copy(tiles=ore.world.tiles.toMutableList().also{it[index]=0}))
        assertEquals(5 to 5,IslandManualMovement.destination(cache,1,0))
        assertEquals("salvage",islandTargetAction(cache,5 to 5).action)
        assertEquals(0,cache.openedSupplies.size)
        assertEquals(4,cache.world.wood)
        assertNull(IslandManualMovement.destination(ore,0,0))
        assertNull(IslandManualMovement.destination(ore,1,1))
    }

    @Test fun blockedWalkingExplainsAnActionWithoutModifyingWorld() {
        val original=BlockIslandRules.newWorld(622,emptyList())
        val w=original.world.copy(
            x=4,y=5,tiles=List(original.world.tiles.size){0},
            mobs=emptyList(),friends=emptyList())
        val clear=original.copy(world=w,animals=emptyList())
        assertEquals(5 to 5,IslandManualMovement.destination(clear,1,0))
        assertNull(IslandManualMovement.blockedHint(clear,1,0))
        assertNull(IslandManualMovement.blockedHint(clear,1,1))

        val wood=clear.copy(world=w.copy(tiles=w.tiles.toMutableList().also {
            it[w.index(5,5)]=1
        }))
        val snapshot=wood.toJson()
        assertNull(IslandManualMovement.destination(wood,1,0))
        assertTrue(IslandManualMovement.blockedHint(wood,1,0)!!.contains("采集"))
        // A UI hint must not spend wood, a turn or alter the user's save.
        assertEquals(snapshot,wood.toJson())
        assertEquals(w.turns,wood.world.turns)

        val hostile=clear.copy(world=w.copy(
            mobs=listOf(ExpeditionMob(78,5,5,4))))
        assertNull(IslandManualMovement.destination(hostile,1,0))
        assertTrue(IslandManualMovement.blockedHint(hostile,1,0)!!.contains("攻击"))
        assertEquals(4,hostile.world.mobs.single().hp)
        val ally=clear.copy(world=w.copy(
            friends=listOf(ExpeditionFriend("ally",5,5,hp=0))))
        assertNull(IslandManualMovement.destination(ally,1,0))
        assertTrue(IslandManualMovement.blockedHint(ally,1,0)!!.contains("2枚野果"))
        val unready=clear.copy(world=w.copy(tiles=w.tiles.toMutableList().also{
            it[w.index(5,5)]=8
        }))
        assertNull(IslandManualMovement.destination(unready,1,0))
        assertTrue(IslandManualMovement.blockedHint(unready,1,0)!!.contains("鱼竿"))
    }

    @Test fun clearTileMovementStillWorksAndCannotWalkIntoMonsters() {
        val start=BlockIslandRules.newWorld(2,emptyList())
        val base=start.copy(animals=emptyList(),world=start.world.copy(mobs=emptyList()))
        assertEquals(5 to 5,IslandManualMovement.destination(base,1,0))
        val blocked=base.copy(world=base.world.copy(
            mobs=listOf(ExpeditionMob(1,5,5,4))))
        assertNull(IslandManualMovement.destination(blocked,1,0))
    }
}
