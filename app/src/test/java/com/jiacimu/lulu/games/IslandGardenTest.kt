package com.jiacimu.lulu.games

import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE, sdk = [28])
class IslandGardenTest {
    @Test fun gardeningConsumesRealResourcesAndRequiresThreeDistinctSteps() {
        val original=BlockIslandRules.newWorld(82,emptyList())
        val start=original.copy(
            fruit=3, animals=emptyList(),supplyCaches=emptyList(),
            world=original.world.copy(wood=10,stone=8,mobs=emptyList()))
        val location=start.world.index(5,5)
        assertEquals("garden",IslandBuildingControls.recipes.first { it.action=="garden" }.action)
        val built=BlockIslandRules.act(start,"garden",5,5)
        assertEquals("garden",built.structures[location])
        assertEquals(8,built.world.wood)
        assertFalse(BlockIslandRules.isWalkable(built,5,5))

        val sowed=BlockIslandRules.act(built,"sow_berries",5,5)
        assertEquals(built.fruit-1,sowed.fruit)
        assertEquals(1,sowed.gardens.size)
        val tooEarly=BlockIslandRules.act(sowed,"tend_berries",5,5)
        assertEquals(sowed.world.turns,tooEarly.world.turns)
        assertEquals(sowed.gardens,tooEarly.gardens)

        val grown=sowed.copy(world=sowed.world.copy(turns=sowed.gardens.getValue(location).plantedTurn+4))
        val tended=BlockIslandRules.act(grown,"tend_berries",5,5)
        assertTrue(tended.gardens.getValue(location).tendedTurn>=4)
        val premature=BlockIslandRules.act(tended,"harvest_berries",5,5)
        assertEquals(tended.world.turns,premature.world.turns)
        assertEquals(tended.fruit,premature.fruit)
        val restored=BlockIslandState.fromJson(tended.toJson())!!
        assertEquals(tended.gardens,restored.gardens)

        val ripened=restored.copy(world=restored.world.copy(
            turns=restored.gardens.getValue(location).plantedTurn+
                IslandGarden.growthTurns(restored.world.turns)))
        assertEquals("harvest_berries",IslandGarden.action(ripened,location))
        assertTrue(IslandGarden.canInteract(ripened,location))
        val harvest=BlockIslandRules.act(ripened,"harvest_berries",5,5)
        assertEquals(ripened.fruit+IslandGarden.harvestYield(ripened.world.turns),harvest.fruit)
        assertFalse(location in harvest.gardens)
        assertEquals("garden",harvest.structures[location])
        val noDouble=BlockIslandRules.act(harvest,"harvest_berries",5,5)
        assertEquals(harvest.world.turns,noDouble.world.turns)
        assertEquals(harvest.fruit,noDouble.fruit)
    }

    @Test fun oldSaveCanLoadWithoutCropsAndDemolishingClearsPlantedCrop() {
        val original=BlockIslandRules.newWorld(73,emptyList())
        val base=original.copy(fruit=2,animals=emptyList(),supplyCaches=emptyList(),
            world=original.world.copy(wood=10,stone=10,mobs=emptyList()))
        val old=org.json.JSONObject(base.toJson()).apply { remove("gardens") }
        assertEquals(emptyMap<Int,IslandGardenCrop>(),BlockIslandState.fromJson(old.toString())!!.gardens)
        val planted=BlockIslandRules.act(BlockIslandRules.act(base,"garden",5,5),"sow_berries",5,5)
        val cleared=BlockIslandRules.act(planted,"remove",5,5)
        assertTrue(cleared.gardens.isEmpty())
        assertFalse(cleared.structures.values.contains("garden"))
        assertTrue(BlockIslandState.fromJson(cleared.toJson())!!.gardens.isEmpty())
    }

    @Test fun fourSeasonsAffectGrowTimeAndAutumnHarvestWithoutTimers() {
        assertEquals("春",IslandGarden.season(0))
        assertEquals("夏",IslandGarden.season(96))
        assertEquals("秋",IslandGarden.season(192))
        assertEquals("冬",IslandGarden.season(288))
        assertEquals("春",IslandGarden.season(384))
        assertEquals(18,IslandGarden.growthTurns(288))
        assertEquals(12,IslandGarden.growthTurns(192))
        assertEquals(5,IslandGarden.harvestYield(192))
        assertFalse(IslandGarden.isReady(IslandGardenCrop(280,284),292))
        assertTrue(IslandGarden.isReady(IslandGardenCrop(280,284),298))
    }
}
