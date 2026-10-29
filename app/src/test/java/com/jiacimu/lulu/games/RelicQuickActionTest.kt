package com.jiacimu.lulu.games

import org.junit.Assert.*
import org.junit.Test

class RelicQuickActionTest {
    private fun run() = RelicEscapeWorld.create(915, 2, emptyList(), 2, 2)
        .copy(mobs = emptyList())

    @Test fun fogNeverBecomesFreeMovementOrTreasure() {
        val map = run().copy(seen = emptySet())
        val chest = map.chests.first()
        assertNull(relicQuickAction(map, chest.x to chest.y))
        assertNull(relicQuickAction(map, -1 to -1))
    }

    @Test fun chestUsesRealActionAndCannotOpenTwice() {
        val original = run()
        val chest = original.chests.first()
        val nearby = RelicEscapeRules.reveal(
            original.copy(hero = original.hero.copy(x = chest.x, y = chest.y)))
        val quick = relicQuickAction(nearby, chest.x to chest.y)!!
        assertEquals("open", quick.action)
        val opened = RelicEscapeRules.act(nearby, quick.action, quick.x, quick.y, quick.id)
        assertTrue(opened.chests.first { it.id == chest.id }.opened)
        assertTrue(opened.hero.bag.any { it.id == nearby.id + ":chest:" + chest.id })
        assertNotEquals("open", relicQuickAction(opened, chest.x to chest.y)?.action)
    }

    @Test fun navigationIsOneLegalStepAndCannotEnterMonsterTile() {
        val map = run().let { it.copy(seen = (0 until it.w * it.h).toSet()) }
        val quick = relicQuickAction(map, map.exitX to map.exitY)!!
        assertEquals("move", quick.action)
        assertEquals(1, RelicEscapeRules.distance(map.hero.x, map.hero.y, quick.x, quick.y))
        assertTrue(map.walk(quick.x, quick.y))
        val blocked = map.copy(mobs = listOf(RelicMob(400, quick.x, quick.y, 5, 0)))
        assertNull(relicQuickAction(blocked, map.exitX to map.exitY))
    }

    @Test fun extractionNeverSuggestsLeavingGate() {
        val map = run().copy(phase = RelicPhase.EXTRACT)
        assertEquals("wait", relicQuickAction(map, map.hero.x to map.hero.y)?.action)
    }

    @Test fun enemyCanBeAttackedOnlyWhenCloseAndSeen() {
        val original = run()
        val x = original.hero.x + 1
        val y = original.hero.y
        val map = RelicEscapeRules.reveal(original.copy(
            mobs = listOf(RelicMob(41, x, y, 5, 0))))
        assertEquals("attack", relicQuickAction(map, x to y)?.action)
        assertNull(relicQuickAction(map.copy(seen = emptySet()), x to y))
    }
}
