package com.jiacimu.lulu.games

import org.junit.Assert.*
import org.junit.Test

/** Scene indexes must retain every physical entity and never merge stacked drops. */
class RelicSceneIndexTest {
    @Test fun sceneIndexPreservesChestSitesAndStackedPhysicalDrops() {
        val run=RelicEscapeWorld.create(412,2,emptyList(),1,1)
        val chestByCell=run.chests.associateBy { it.y*run.w+it.x }
        val siteByCell=run.sites.associateBy { it.y*run.w+it.x }
        assertEquals(run.chests.size,chestByCell.size)
        assertEquals(run.sites.size,siteByCell.size)
        for(y in 0 until run.h)for(x in 0 until run.w) {
            assertEquals(run.chests.firstOrNull { it.x==x && it.y==y },
                chestByCell[y*run.w+x])
            assertEquals(run.sites.firstOrNull { it.x==x && it.y==y },
                siteByCell[y*run.w+x])
        }
    }
}
