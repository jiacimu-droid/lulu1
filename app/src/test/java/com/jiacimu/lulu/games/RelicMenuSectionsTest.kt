package com.jiacimu.lulu.games

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class RelicMenuSectionsTest {
    @Test fun activeRunSeparatesLiveStateTacticsInventoryAndEvents() {
        val names=RelicMenuSections.forPhase(RelicPhase.EXPLORE)
        assertEquals(listOf("现场","战术","背包","记录"),names)
        assertEquals(names,RelicMenuSections.forPhase(RelicPhase.EXTRACT))
        assertEquals(names,RelicMenuSections.forPhase(RelicPhase.DOWNED))
        assertEquals(names.size,names.toSet().size)
        assertEquals("现场",RelicMenuSections.defaultFor(RelicPhase.DOWNED))
    }

    @Test fun finishedOrAbsentRunSeparatesPreparationAndPermanentCollections() {
        for(phase in listOf(null,RelicPhase.PREPARE,RelicPhase.SUCCESS,
                RelicPhase.FAILURE,RelicPhase.SETTLEMENT)) {
            assertEquals(listOf("准备","仓库","图鉴"),RelicMenuSections.forPhase(phase))
            assertEquals("准备",RelicMenuSections.defaultFor(phase))
        }
        assertFalse("仓库" in RelicMenuSections.expedition)
        assertTrue("图鉴" in RelicMenuSections.camp)
    }
}
