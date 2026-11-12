package com.jiacimu.lulu.games

import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE, sdk = [28])
class IslandPlayerHouseTest {
    @Test fun blueprintIsNotInstantLootAndNinePhysicalActionsCreateFinishedRoof() {
        val base=BlockIslandRules.newWorld(127,emptyList())
        val start=base.copy(animals=emptyList(),supplyCaches=emptyList(),
            world=base.world.copy(wood=30,stone=10,mobs=emptyList()))
        assertTrue(IslandHouseProjects.canStartPlayer(start,5,3))
        val preview=IslandBuildingControls.preview(start,"house_warm",5,3)!!
        assertTrue(preview.allowed)
        assertEquals(start.world.wood,BlockIslandRules.act(start,"house_warm",5,3).world.wood)
        var state=BlockIslandRules.act(start,"house_warm",5,3)
        assertEquals("player",state.houseProject!!.ownerId)
        assertEquals(0,state.houseProject!!.placed.size)
        assertEquals("building",state.houseProject!!.phase)
        val first=IslandHouseProjects.nextPlayerPart(state)!!
        val farAttempt=BlockIslandRules.act(state,"house_step",
            first.first%state.world.width,first.first/state.world.width)
        assertEquals(state.world.turns,farAttempt.world.turns)
        for(i in 1..9) {
            val next=IslandHouseProjects.nextPlayerPart(state)!!
            val x=next.first%state.world.width
            val y=next.first/state.world.width
            val stand=listOf((x-1) to y,(x+1) to y,x to (y-1),x to (y+1))
                .first { (px,py) -> BlockIslandRules.isWalkable(state,px,py) &&
                    state.world.friends.none { it.x==px && it.y==py } }
            state=state.copy(world=state.world.copy(x=stand.first,y=stand.second))
            assertTrue(IslandHouseProjects.canPlayerBuildNext(state))
            val beforeWood=state.world.wood
            state=BlockIslandRules.act(state,"house_step",x,y)
            assertEquals(beforeWood-(if(next.second=="floor")1 else 2),state.world.wood)
            assertEquals(i,state.houseProject!!.placed.size)
        }
        assertEquals("complete",state.houseProject!!.phase)
        assertNull(IslandHouseProjects.nextPlayerPart(state))
        assertEquals("warm",state.houseProject!!.style)
        assertEquals(13,state.world.wood)
        val restored=BlockIslandState.fromJson(state.toJson())!!
        assertEquals(state.houseProject,restored.houseProject)
        assertEquals(state.world.tiles,restored.world.tiles)
        val duplicate=BlockIslandRules.act(restored,"house_step",5,3)
        assertEquals(restored.world.turns,duplicate.world.turns)
    }

    @Test fun removingOneBuiltTileRemovesRoofUntilPlayerRepairsIt() {
        val base=BlockIslandRules.newWorld(128,emptyList())
        val w=base.world
        val left=5;val top=3
        val parts=IslandHouseProjects.parts(w,left,top)
        val tiles=w.tiles.toMutableList()
        parts.forEach { (id,kind) -> tiles[id]=if(kind=="wall")3 else 4 }
        val door=parts.first { it.second=="door" }.first
        val complete=base.copy(animals=emptyList(),supplyCaches=emptyList(),
            structures=mapOf(door to "door"),
            houseProject=IslandHouseProject("player",left,top,
                phase="complete",placed=parts.map { it.first }),
            world=w.copy(tiles=tiles,wood=10,mobs=emptyList(),x=4,y=4))
        val first=parts.first()
        val removed=BlockIslandRules.act(complete,"remove",
            first.first%w.width,first.first/w.width)
        // Player must stand next to the tile before the physical removal can happen.
        assertEquals(complete.world.turns,removed.world.turns)
        val near=complete.copy(world=complete.world.copy(x=5,y=4))
        val taken=BlockIslandRules.act(near,"remove",6,4)
        assertEquals("building",taken.houseProject!!.phase)
        assertEquals(8,taken.houseProject!!.placed.size)
        assertEquals(first.first,IslandHouseProjects.nextPlayerPart(taken)!!.first)
    }

    @Test fun blueprintCannotOverwriteWildlifeExistingHomesOrSaveResources() {
        val base=BlockIslandRules.newWorld(91,emptyList())
        val available=base.copy(animals=emptyList(),supplyCaches=emptyList(),
            world=base.world.copy(wood=20,mobs=emptyList()))
        assertTrue(IslandHouseProjects.canStartPlayer(available,5,3))
        val occupied=available.copy(structures=mapOf(available.world.index(6,4) to "bed"))
        assertFalse(IslandHouseProjects.canStartPlayer(occupied,5,3))
        val rejected=BlockIslandRules.act(occupied,"house_pine",5,3)
        assertEquals(occupied.world.turns,rejected.world.turns)
        assertNull(rejected.houseProject)
        assertEquals(occupied.world.wood,rejected.world.wood)
    }
}
