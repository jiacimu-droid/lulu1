package com.jiacimu.lulu.games

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** A route hint may only depend on actually discovered, safe world tiles. */
class RelicObjectiveRouteTest {
    private fun corridor():RelicRun {
        val base=RelicEscapeWorld.create(915,2,emptyList(),1,1)
        val width=12
        val height=10
        val terrain=List(width*height){ index ->
            val x=index%width
            val y=index/width
            if(y==2 && x in 2..6)0 else 1
        }
        return base.copy(
            w=width,h=height,tiles=terrain,
            hero=base.hero.copy(x=2,y=2),
            chests=listOf(RelicChest(31,6,2,RelicTier.COMMON)),
            mobs=emptyList(),sites=emptyList(),
            seen=setOf(2*width+2,2*width+6)
        )
    }

    @Test fun visibleChestIsNotFalselyReportedReachableThroughFog() {
        val limited=corridor()
        val destination=RelicObjectiveGuide.nextVisible(limited)!!
        assertEquals(6 to 2,destination.x to destination.y)
        assertNull(RelicObjectiveGuide.knownRouteSteps(limited,destination))
        assertTrue(RelicObjectiveGuide.prompt(limited).contains("通路未探明"))
    }

    @Test fun fullyDiscoveredPathReportsPhysicalStepsWithoutMovingPlayer() {
        val s=corridor().copy(seen=(2..6).map{2*12+it}.toSet())
        val destination=RelicObjectiveGuide.nextVisible(s)!!
        assertEquals(3,RelicObjectiveGuide.knownRouteSteps(s,destination))
        assertTrue(RelicObjectiveGuide.prompt(s).contains("约3步"))
        assertEquals(2 to 2,s.hero.x to s.hero.y)
        assertEquals(0,RelicObjectiveGuide.knownRouteSteps(
            s.copy(hero=s.hero.copy(x=5,y=2)),destination))
    }

    @Test fun enemyTrapAndAcidBlockSafeRouteEstimate() {
        val s=corridor().copy(seen=(2..6).map{2*12+it}.toSet())
        val target=RelicObjectiveGuide.nextVisible(s)!!
        assertNull(RelicObjectiveGuide.knownRouteSteps(s.copy(
            mobs=listOf(RelicMob(91,4,2,5,0))),target))
        assertNull(RelicObjectiveGuide.knownRouteSteps(s.copy(
            sites=listOf(RelicSite(3,4,2,"trap"))),target))
        assertNull(RelicObjectiveGuide.knownRouteSteps(s.copy(
            tiles=s.tiles.toMutableList().also{it[2*12+4]=2}),target))
        assertNull(RelicObjectiveGuide.knownRouteSteps(s.copy(
            phase=RelicPhase.FAILURE),RelicObjectiveTarget(10,8,"失败终点")))
    }
    @Test fun mapRouteMatchesHudAndDisappearsWhenNewThreatBlocksCorridor() {
        val discovered=corridor().copy(seen=(2..6).map { 2*12+it }.toSet())
        val target=RelicObjectiveGuide.nextVisible(discovered)!!
        val expected=listOf(2 to 2,3 to 2,4 to 2,5 to 2)
        assertEquals(expected,RelicObjectiveGuide.knownRouteTiles(discovered,target))
        assertEquals(expected.size-1,RelicObjectiveGuide.knownRouteSteps(discovered,target))
        assertEquals("已知路线约3步",RelicObjectiveGuide.optionLabel(discovered,target))
        // Rendering a hint never moves the hero or opens the physical chest.
        assertEquals(2 to 2,discovered.hero.x to discovered.hero.y)
        assertTrue(discovered.chests.none { it.opened })

        val threatened=discovered.copy(mobs=listOf(RelicMob(92,4,2,5,0)))
        assertNull(RelicObjectiveGuide.knownRouteTiles(threatened,target))
        assertEquals("路径未探明",RelicObjectiveGuide.optionLabel(threatened,target))
        assertEquals(2 to 2,threatened.hero.x to threatened.hero.y)
        assertTrue(threatened.chests.none { it.opened })
    }

    @Test fun completedChestIsNoLongerSelectableForMapRouteAfterRealWorldUpdate() {
        val discovered=corridor().copy(seen=(2..6).map { 2*12+it }.toSet())
        val target=RelicObjectiveGuide.visibleChest(discovered)!!
        assertEquals(3,RelicObjectiveGuide.knownRouteSteps(discovered,target))

        // The player may still have the old map tile selected after opening.
        // Map route projection must resolve a live unopened objective, not
        // reuse the stale selection as an authorization to draw a path.
        val opened=discovered.copy(chests=discovered.chests.map { it.copy(opened=true) })
        assertNull(RelicObjectiveGuide.visibleChest(opened))
        val liveTarget=listOfNotNull(
            RelicObjectiveGuide.visibleChest(opened),
            RelicObjectiveGuide.visibleExit(opened)
        ).firstOrNull { it.x==target.x && it.y==target.y }
        assertNull(liveTarget)
        assertTrue(opened.chests.all { it.opened })
    }

    @Test fun manuallySelectedSecondChestUsesLiveDiscoveredTargetAndNeverStaleLoot() {
        val base=corridor().copy(
            chests=listOf(
                RelicChest(31,3,2,RelicTier.COMMON),
                RelicChest(32,6,2,RelicTier.COMMON),
            ),
            seen=(2..6).map { 2*12+it }.toSet()
        )
        // HUD suggests the nearest, but a deliberate tap on the farther box
        // must still produce a real known route without opening anything.
        assertEquals(3 to 2,RelicObjectiveGuide.visibleChest(base)?.let { it.x to it.y })
        val farther=RelicObjectiveGuide.selectedVisible(base,6,2)!!
        assertEquals(6 to 2,farther.x to farther.y)
        assertEquals(listOf(2 to 2,3 to 2,4 to 2,5 to 2),
            RelicObjectiveGuide.knownRouteTiles(base,farther))
        assertTrue(base.chests.none { it.opened })
        assertNull(RelicObjectiveGuide.selectedVisible(base,9,2)) // fog
        assertNull(RelicObjectiveGuide.selectedVisible(base,-1,2)) // bounds
        val opened=base.copy(chests=base.chests.map {
            if(it.id==32) it.copy(opened=true) else it
        })
        assertNull(RelicObjectiveGuide.selectedVisible(opened,6,2))
        assertEquals(3 to 2,RelicObjectiveGuide.visibleChest(opened)?.let { it.x to it.y })
        assertNull(RelicObjectiveGuide.selectedVisible(
            base.copy(phase=RelicPhase.FAILURE),6,2))
    }


    @Test fun manuallySelectedExitUsesOnlyDiscoveredSafeRouteAndNeverMovesHero() {
        val discovered=corridor().copy(
            chests=emptyList(),
            exitX=6,exitY=2,
            seen=(2..6).map { 2*12+it }.toSet()
        )
        val exit=RelicObjectiveGuide.selectedVisible(discovered,6,2)!!
        assertEquals("已发现的撤离门",exit.label)
        assertEquals(listOf(2 to 2,3 to 2,4 to 2,5 to 2),
            RelicObjectiveGuide.knownRouteTiles(discovered,exit))
        assertEquals(3,RelicObjectiveGuide.knownRouteSteps(discovered,exit))
        assertEquals(2 to 2,discovered.hero.x to discovered.hero.y)

        val fog=discovered.copy(seen=setOf(2*12+2))
        assertNull(RelicObjectiveGuide.selectedVisible(fog,6,2))
        assertNull(RelicObjectiveGuide.knownRouteTiles(fog,exit))
        val blocked=discovered.copy(mobs=listOf(RelicMob(93,4,2,5,0)))
        assertNull(RelicObjectiveGuide.knownRouteTiles(blocked,exit))
        assertNull(RelicObjectiveGuide.selectedVisible(
            discovered.copy(phase=RelicPhase.FAILURE),6,2))
    }


}
