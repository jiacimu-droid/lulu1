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
        var current=nearby
        repeat(RelicEscapeRules.openSteps(chest)-1) { press ->
            assertEquals("pry",RelicChestPresentation.playerAction(
                current,quick.action,quick.x,quick.y))
            val advanced=RelicEscapeRules.act(current,
                RelicChestPresentation.playerAction(current,quick.action,quick.x,quick.y),
                quick.x,quick.y,quick.id)
            assertEquals(current.turns+1,advanced.turns)
            assertFalse(advanced.chests.first { it.id==chest.id }.opened)
            assertEquals(press+1,advanced.chestProgress[chest.id])
            assertFalse(advanced.hero.bag.any { it.id==nearby.id+":chest:"+chest.id })
            current=advanced
        }
        assertEquals("open",RelicChestPresentation.playerAction(
            current,quick.action,quick.x,quick.y))
        val opened=RelicEscapeRules.act(current,
            RelicChestPresentation.playerAction(current,quick.action,quick.x,quick.y),
            quick.x,quick.y,quick.id)
        assertTrue(opened.chests.first { it.id==chest.id }.opened)
        assertTrue(opened.hero.bag.any { it.id==nearby.id+":chest:"+chest.id })
        assertNotEquals("open",relicQuickAction(opened,chest.x to chest.y)?.action)
        assertEquals("open",RelicChestPresentation.playerAction(opened,
            "open",chest.x,chest.y))
        assertEquals(opened.turns,RelicEscapeRules.act(opened,"open",chest.x,chest.y).turns)
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

    @Test fun adjacentEnemyCanBeAttackedWithoutMovingCursorOffPlayer() {
        val original = run()
        val x = original.hero.x + 1
        val y = original.hero.y
        val map = RelicEscapeRules.reveal(original.copy(
            mobs = listOf(RelicMob(81, x, y, 5, 0))))
        val cursor = map.hero.x to map.hero.y
        assertEquals(81, relicAttackTarget(map, cursor)?.id)
        val primary = relicQuickAction(map, cursor)!!
        assertEquals("attack", primary.action)
        assertEquals(x, primary.x)
        assertEquals(y, primary.y)
        val after = RelicEscapeRules.act(map, primary.action, primary.x, primary.y)
        assertTrue(after.turns > map.turns)
        assertTrue(after.mobs.first().hp < map.mobs.first().hp)
    }

    @Test fun selectedHittableEnemyWinsButDistantMonsterIsNotAttacked() {
        val original = run()
        val h = original.hero
        val near = RelicMob(82, h.x + 1, h.y, 5, 0)
        val another = RelicMob(83, h.x, h.y + 1, 5, 0)
        val map = RelicEscapeRules.reveal(original.copy(mobs = listOf(near, another)))
        assertEquals(83, relicAttackTarget(map, another.x to another.y)?.id)
        assertEquals(82, relicAttackTarget(map, h.x to h.y)?.id)
        val far = map.copy(mobs = listOf(RelicMob(84, h.x + 3, h.y, 5, 0)))
        assertNull(relicAttackTarget(far, far.hero.x to far.hero.y))
        assertFalse(relicAttackHint(far, h.x + 3 to h.y).isBlank())
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

    @Test fun joystickNeverAutoPriesEvenWhenHeldTowardAChest() {
        val base=run()
        val chest=base.chests.first()
        val scene=RelicEscapeRules.reveal(base.copy(
            hero=base.hero.copy(x=chest.x-1,y=chest.y),
            sites=emptyList(),mobs=emptyList(),
            seen=(0 until base.w*base.h).toSet()))
        val command=relicDirectionAction(scene,1,0)!!
        assertEquals("move",command.action)
        assertEquals(chest.x to chest.y,command.x to command.y)
        assertNull(relicDirectionAction(scene,1,1))
        assertNull(relicDirectionAction(scene.copy(phase=RelicPhase.DOWNED),1,0))

        val walking=RelicEscapeRules.act(scene,command.action,command.x,command.y)
        assertEquals(scene.turns+1,walking.turns)
        assertEquals(chest.x to chest.y,walking.hero.x to walking.hero.y)
        assertFalse(walking.chests.first { it.id==chest.id }.opened)
        assertTrue(walking.chestProgress.isEmpty())
        assertTrue(walking.hero.bag.isEmpty())

        // Standing right on the chest must NOT strand the player:
        // only the separate centre control starts real, persisted prying.
        val interact=relicNearbyAction(walking,1 to 0)!!
        assertEquals("open",interact.action)
        assertEquals(chest.x to chest.y,interact.x to interact.y)
        val pry=RelicChestPresentation.playerAction(
            walking,interact.action,interact.x,interact.y)
        assertEquals("pry",pry)
        val started=RelicEscapeRules.act(walking,pry,interact.x,interact.y)
        assertEquals(1,started.chestProgress[chest.id])
        assertFalse(started.chests.first { it.id==chest.id }.opened)
    }

    @Test fun joystickNeverAutoInvokesOrDisablesLandmarks() {
        val base=run()
        val altar=RelicSite(456,base.hero.x+1,base.hero.y,"altar")
        val tower=altar.copy(kind="alarm")
        for(site in listOf(altar,tower)) {
            val scene=RelicEscapeRules.reveal(base.copy(
                sites=listOf(site),mobs=emptyList(),
                tiles=List(base.w*base.h){0},
                seen=(0 until base.w*base.h).toSet()))
            val step=relicDirectionAction(scene,1,0)!!
            val moved=RelicEscapeRules.act(scene,step.action,step.x,step.y)
            assertEquals(scene.turns+1,moved.turns)
            assertFalse(moved.sites.single().spent)
            assertEquals(scene.tools,moved.tools)
            assertEquals(scene.hero.bag,moved.hero.bag)
            assertTrue(relicNearbyAction(moved,1 to 0)?.action in
                setOf("invoke","silence"))
        }
    }

    @Test fun dpadCanOpenAdjacentTreasureWithoutSelectingMapCell() {
        val base = run()
        val chest = base.chests.first()
        val adjacent = RelicEscapeRules.reveal(base.copy(
            hero = base.hero.copy(x = chest.x - 1, y = chest.y),
            seen = (0 until base.w * base.h).toSet(),
            sites = emptyList(),
        ))
        val quick = relicNearbyAction(adjacent, 1 to 0)!!
        assertEquals("open", quick.action)
        assertEquals(chest.x, quick.x)
        assertEquals(chest.y, quick.y)
        val first=RelicEscapeRules.act(adjacent,
            RelicChestPresentation.playerAction(adjacent,quick.action,quick.x,quick.y),
            quick.x,quick.y)
        assertFalse(first.chests.first { it.id==chest.id }.opened)
        assertEquals(1,first.chestProgress[chest.id])
        // D-pad continues to select the same physical chest from the adjacent tile.
        assertEquals(chest.id,first.chests.first { it.id==chest.id }.id)
        assertEquals("open",relicNearbyAction(first,1 to 0)?.action)
        var current=first
        repeat(RelicEscapeRules.openSteps(chest)-1) {
            val nearbyAction=relicNearbyAction(current,1 to 0)!!
            current=RelicEscapeRules.act(current,
                RelicChestPresentation.playerAction(
                    current,nearbyAction.action,nearbyAction.x,nearbyAction.y),
                nearbyAction.x,nearbyAction.y)
        }
        assertTrue(current.chests.first { it.id==chest.id }.opened)
        assertNotEquals("open",relicNearbyAction(current,1 to 0)?.action)
    }

    @Test fun contextInteractionNeverAutoAttacksEnemyOrActsThroughFog() {
        val base = run()
        val h = base.hero
        val enemy = RelicMob(667, h.x + 1, h.y, 5, 0)
        val field = base.copy(chests = emptyList(), sites = emptyList(),
            mobs = listOf(enemy), drops = emptyList())
        assertNull(relicNearbyAction(field, 1 to 0))
        assertNull(relicNearbyAction(field.copy(seen = emptySet()), 1 to 0))
        assertEquals("attack", relicQuickAction(RelicEscapeRules.reveal(field),
            enemy.x to enemy.y)?.action)
    }
}
