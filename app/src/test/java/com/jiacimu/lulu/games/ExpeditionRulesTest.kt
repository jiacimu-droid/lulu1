package com.jiacimu.lulu.games

import org.junit.Assert.*
import org.junit.Test

/** Contract tests for both actual playable maps, combat and persistent results. */
class ExpeditionRulesTest {
    @Test fun sandboxMapIsDeterministicAndBuildingsActuallyChangePersistentTiles() {
        val original = ExpeditionRules.start(ExpeditionMode.HOMESTEAD, 20261028, emptyList())
        assertEquals(original.tiles, ExpeditionRules.start(ExpeditionMode.HOMESTEAD,20261028,emptyList()).tiles)
        val materials = original.copy(wood=6,stone=2,mobs=emptyList())
        val built=ExpeditionRules.act(materials,"home")
        assertEquals("盖好一间带门的小屋", built.events.last())
        assertEquals(0,built.wood)
        assertEquals(0,built.stone)
        assertEquals(4,built.cell(5,4))
        assertEquals(4,built.cell(4,4))
        assertEquals(3,built.cell(4,3))
        val saved=ExpeditionState.fromJson(built.toJson().toString(),ExpeditionMode.HOMESTEAD)!!
        assertEquals(built.tiles,saved.tiles)
        assertEquals(built.events,saved.events)
        assertNull(ExpeditionState.fromJson(built.toJson().toString(),ExpeditionMode.RELIC_RUN))
    }

    @Test fun harvestingAndCompanionActionsChangeTheSameSharedBoard() {
        val base=ExpeditionRules.start(ExpeditionMode.HOMESTEAD,15,listOf("role-a"))
        val nearby = base.tiles.toMutableList().also{ it[base.index(3,5)]=1 }
        val gathered=ExpeditionRules.act(base.copy(tiles=nearby,mobs=emptyList(),friends=emptyList()),"gather",3,5)
        assertEquals(2,gathered.wood)
        assertEquals(0,gathered.cell(3,5))
        val friend=ExpeditionFriend("role-a",4,4)
        val sharedTiles=nearby.toMutableList().also{it[base.index(5,4)]=1}
        val shared=base.copy(friends=listOf(friend),mobs=emptyList(),tiles=sharedTiles)
        val cooperated=ExpeditionRules.act(shared,"move",3,3)
        assertEquals(0,cooperated.cell(5,4))
        assertTrue(cooperated.wood>=1)
        assertTrue(cooperated.events.any{it.contains("role-a")})
    }

    @Test fun treasureCanOnlyBeOpenedOnceAndMustBeExtractedToCount() {
        val start=ExpeditionRules.start(ExpeditionMode.RELIC_RUN,1542,emptyList())
        assertEquals(5,start.tiles.count{it==5})
        assertEquals(1,start.tiles.count{it==6})
        val nearChest=start.copy(x=8,y=3,mobs=emptyList())
        val loot=ExpeditionRules.act(nearChest,"open",9,3)
        assertEquals(1,loot.relics)
        assertTrue(loot.lootValue>0)
        assertEquals(0,loot.cell(9,3))
        val again=ExpeditionRules.act(loot,"open",9,3)
        assertEquals(loot.relics,again.relics)
        assertEquals(loot.lootValue,again.lootValue)
        val atExit=loot.copy(x=15,y=11,mobs=emptyList())
        val extracted=ExpeditionRules.act(atExit,"exit")
        assertTrue(extracted.finished)
        assertTrue(extracted.escaped)
        assertEquals(loot.lootValue,extracted.lootValue)
        val restored=ExpeditionState.fromJson(extracted.toJson().toString(),ExpeditionMode.RELIC_RUN)!!
        assertTrue(restored.escaped)
        assertEquals(extracted.lootValue,restored.lootValue)
    }

    @Test fun defeatingThePlayerForfeitsUnextractedLoot() {
        val start=ExpeditionRules.start(ExpeditionMode.RELIC_RUN,1542,emptyList())
        val dangerous=start.copy(x=8,y=6,hp=1,mobs=listOf(ExpeditionMob(7,8,5,3)),
            relics=2,lootValue=450)
        val fought=ExpeditionRules.act(dangerous,"fight",8,5)
        assertTrue(fought.finished)
        assertFalse(fought.escaped)
        assertEquals(0,fought.lootValue)
        assertEquals(0,fought.relics)
    }

    @Test fun mapNavigationAvoidsWallsAndReachesARealAdjacentTarget() {
        val start=ExpeditionRules.start(ExpeditionMode.RELIC_RUN,42,emptyList())
        val first=expeditionNextStep(start,15,12)
        assertNotNull(first)
        assertTrue(first!!.first in 1 until start.width-1)
        assertTrue(first.second in 1 until start.height-1)
        assertTrue(start.cell(first.first,first.second) in listOf(0,4,6,7))
        assertNull(expeditionNextStep(start,-1,0))
    }
}
