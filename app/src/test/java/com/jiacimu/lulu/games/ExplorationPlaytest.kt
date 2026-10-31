package com.jiacimu.lulu.games

import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(manifest=Config.NONE,sdk=[28])
class ExplorationPlaytest {
    @Test fun supplyCachesHaveReachableStableLocationsAndClaimExactlyOnce() {
        for (seed in listOf(1, 7, 99, -21, 20261031)) {
            val fresh = BlockIslandRules.newWorld(seed, emptyList())
            assertEquals(4, fresh.supplyCaches.size)
            assertEquals(4, fresh.supplyCaches.distinct().size)
            val cache = fresh.supplyCaches.first()
            val x = cache % fresh.world.width
            val y = cache / fresh.world.width
            assertTrue(BlockIslandRules.isWalkable(fresh, x, y))
            assertNotNull(BlockIslandRules.nextStep(fresh, fresh.world.x, fresh.world.y, x, y))
            val adjacent = fresh.copy(world=fresh.world.copy(x=x,y=y,mobs=emptyList()))
            val opened = BlockIslandRules.act(adjacent, "salvage", x, y)
            assertEquals(1, opened.openedSupplies.size)
            assertEquals(adjacent.world.turns+1, opened.world.turns)
            assertTrue(opened.world.wood > adjacent.world.wood)
            val again = BlockIslandRules.act(opened,"salvage",x,y)
            assertEquals(opened.openedSupplies, again.openedSupplies)
            assertEquals(opened.world.turns, again.world.turns)
            val restored=BlockIslandState.fromJson(opened.toJson())!!
            assertEquals(opened.supplyCaches, restored.supplyCaches)
            assertEquals(opened.openedSupplies, restored.openedSupplies)
        }
    }

    @Test fun legacySavesGainCachesWithoutLosingOldStructures() {
        val initial=BlockIslandRules.newWorld(40,emptyList())
        val old=JSONObject(initial.toJson()).apply{
            remove("supplyCaches")
            remove("openedSupplies")
        }
        val restored=BlockIslandState.fromJson(old.toString())!!
        assertEquals(initial.world.tiles,restored.world.tiles)
        assertEquals(initial.structures,restored.structures)
        assertEquals(initial.supplyCaches,restored.supplyCaches)
        val far=restored.supplyCaches.last()
        val refused=BlockIslandRules.act(restored,"salvage",
            far%restored.world.width, far/restored.world.width)
        assertEquals(restored.world.turns,refused.world.turns)
    }

    @Test fun relicSurveyRevealsFogCostsTurnAndCannotBeFarmed() {
        val run=RelicEscapeWorld.create(431,2,emptyList(),2,1).copy(mobs=emptyList())
        assertTrue(RelicEscapeRules.canSurvey(run))
        val surveyed=RelicEscapeRules.act(run,"survey")
        assertEquals(run.turns+1,surveyed.turns)
        assertTrue(surveyed.seen.size>run.seen.size)
        assertEquals(run.chests,surveyed.chests)
        assertEquals(run.hero.bag,surveyed.hero.bag)
        val restored=RelicRun.read(JSONObject(surveyed.json().toString()))!!
        assertEquals(surveyed.seen,restored.seen)
        val explored=surveyed.copy(seen=surveyed.tiles.indices.toSet())
        assertFalse(RelicEscapeRules.canSurvey(explored))
        val rejected=RelicEscapeRules.act(explored,"survey")
        assertEquals(explored.turns,rejected.turns)
        assertEquals(explored.hero.bag,rejected.hero.bag)
    }
}
