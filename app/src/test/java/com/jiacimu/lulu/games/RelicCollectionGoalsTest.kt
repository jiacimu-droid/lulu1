package com.jiacimu.lulu.games

import org.junit.Assert.*
import org.junit.Test

/** Permanent collection goals must follow the real codex, never a second award source. */
class RelicCollectionGoalsTest {
    @Test fun allGoalsUseObtainableDistinctNames() {
        val known=RelicCollectibles.names.toSet()
        assertTrue(RelicCollectionGoals.all.size>=4)
        for(goal in RelicCollectionGoals.all) {
            assertTrue(goal.names.isNotEmpty())
            assertEquals(goal.names.size,goal.names.toSet().size)
            assertTrue("unobtainable set item in ${goal.id}",
                goal.names.all{it in known})
        }
    }
    @Test fun countsOnlySuccessfullyDiscoveredAndSurvivesSales() {
        val empty=emptySet<String>()
        assertEquals(0,RelicCollectionGoals.completed(empty))
        val moon=RelicCollectionGoals.all.first{it.id=="moon"}
        assertEquals(0,moon.owned(empty))
        val half=moon.names.take(2).toSet()
        assertEquals(2,moon.owned(half))
        assertFalse(moon.complete(half))
        assertEquals(2,moon.missing(half).size)
        assertTrue(moon.complete(moon.names.toSet()))
        assertEquals(1,RelicCollectionGoals.completed(moon.names.toSet()))
    }
    @Test fun enemyDropsHaveRealCodexEntriesAndPlausiblePricing() {
        val run=RelicEscapeWorld.create(1511,3,emptyList(),0,1)
        for(kind in 0..3) {
            val drop=RelicArsenal.monsterDrop(run,RelicMob(kind,4,4,1,kind))
            assertTrue(drop.name in RelicCollectibles.names)
            assertTrue(drop.value>=drop.tier.base)
            assertTrue(drop.value<=drop.tier.base+62)
        }
    }
}
