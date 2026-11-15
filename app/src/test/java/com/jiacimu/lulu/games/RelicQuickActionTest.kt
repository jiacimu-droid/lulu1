package com.jiacimu.lulu.games

import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE, sdk = [33])
class RelicQuickActionTest {
    private fun run() = RelicEscapeWorld.create(915, 2, emptyList(), 2, 2)
        .copy(mobs = emptyList())

    @Test fun centerInteractionHonorsTappedNearbyChestBeforeJoystickFacing() {
        val base = run()
        val h = base.hero
        val east = RelicChest(981, h.x + 1, h.y, RelicTier.COMMON)
        val south = RelicChest(982, h.x, h.y + 1, RelicTier.PRECIOUS)
        val scene = RelicEscapeRules.reveal(base.copy(
            chests = listOf(east, south), sites = emptyList(),
            seen = (0 until base.w * base.h).toSet(),
        ))
        val facing = 1 to 0
        val chosen = relicNearbyAction(scene, facing, south.x to south.y)!!
        assertEquals("open", chosen.action)
        assertEquals(south.x, chosen.x)
        assertEquals(south.y, chosen.y)
        val next = RelicEscapeRules.act(scene,
            RelicChestPresentation.playerAction(scene, chosen.action, chosen.x, chosen.y),
            chosen.x, chosen.y)
        assertEquals(0, next.chestProgress[east.id] ?: 0)
        assertEquals(1, next.chestProgress[south.id] ?: 0)
        // A deliberate distant/empty tap must not silently open a different chest.
        // The facing shortcut is available only after returning selection to the hero.
        assertNull(relicNearbyAction(scene, facing, h.x + 8 to h.y + 8))
        val facingShortcut = relicNearbyAction(scene, facing, h.x to h.y)!!
        assertEquals(east.x, facingShortcut.x)
        assertEquals(east.y, facingShortcut.y)
    }

    @Test fun explicitDistantChestNeverOpensAdjacentChest() {
        val base = run()
        val h = base.hero
        val near = RelicChest(991, h.x, h.y, RelicTier.COMMON)
        val far = RelicChest(992, h.x + 3, h.y, RelicTier.PRECIOUS)
        val scene = base.copy(chests = listOf(near, far),
            seen = (0 until base.w * base.h).toSet())
        assertEquals(near.id, RelicChestPresentation.actionableChest(
            scene, h.x to h.y)?.id)
        assertNull(RelicChestPresentation.actionableChest(scene, far.x to far.y))
        val atFar = scene.copy(hero = h.copy(x = far.x, y = far.y))
        assertEquals(far.id, RelicChestPresentation.actionableChest(
            atFar, far.x to far.y)?.id)
        assertNull(RelicChestPresentation.actionableChest(
            scene.copy(seen = emptySet()), far.x to far.y))
    }

    @Test fun openedSelectedChestNeverRedirectsToAnotherNearbyChest() {
        val base = run()
        val h = base.hero
        val opened = RelicChest(993, h.x, h.y, RelicTier.COMMON, opened = true)
        val unopened = RelicChest(994, h.x + 1, h.y, RelicTier.PRECIOUS)
        val scene = base.copy(chests = listOf(opened, unopened),
            seen = (0 until base.w * base.h).toSet())
        assertNull(RelicChestPresentation.actionableChest(scene, opened.x to opened.y))
        assertEquals(unopened.id, RelicChestPresentation.actionableChest(
            scene, unopened.x to unopened.y)?.id)
    }

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
        // The reveal must be backed by the same committed physical loot,
        // not merely by the chest animation or a previous inventory item.
        val receipt = RelicChestPresentation.committed(current, opened, chest.id)
        assertNotNull(receipt)
        assertEquals(opened.id, receipt!!.runId)
        assertEquals(chest.id, receipt.chestId)
        assertTrue(receipt.inBag)
        assertTrue(opened.hero.bag.contains(receipt.loot))
        assertTrue(RelicChestPresentation.isCurrentOpening(opened, chest.id, receipt))
        assertNull(RelicChestPresentation.committed(opened, opened, chest.id))
        assertNotEquals("open",relicQuickAction(opened,chest.x to chest.y)?.action)
        assertEquals("open",RelicChestPresentation.playerAction(opened,
            "open",chest.x,chest.y))
        assertEquals(opened.turns,RelicEscapeRules.act(opened,"open",chest.x,chest.y).turns)
    }

    @Test fun fullSatchelOpeningProducesOnlyGroundReceiptAndSurvivesReload() {
        val base = run()
        val chest = base.chests.first()
        val heavy = (1..40).map {
            RelicLoot("existing:$it", "已携带珍宝", RelicTier.PRECIOUS, 430)
        }
        val nearby = RelicEscapeRules.reveal(base.copy(
            hero = base.hero.copy(x = chest.x, y = chest.y, bag = heavy),
            mobs = emptyList(), sites = emptyList(),
        ))
        var current = nearby
        repeat(RelicEscapeRules.openSteps(chest) - 1) {
            current = RelicEscapeRules.act(current, "pry", chest.x, chest.y)
        }
        val opened = RelicEscapeRules.act(current, "open", chest.x, chest.y)
        val expected = RelicEscapeRules.treasure(current, chest)
        assertTrue(opened.chests.first { it.id == chest.id }.opened)
        assertEquals(heavy, opened.hero.bag)
        assertTrue(opened.drops.any {
            it.x == chest.x && it.y == chest.y && it.loot == expected
        })
        val receipt = RelicChestPresentation.committed(current, opened, chest.id)
        assertNotNull(receipt)
        assertFalse(receipt!!.inBag)
        assertEquals(expected, receipt.loot)
        assertTrue(RelicChestPresentation.isCurrentOpening(opened, chest.id, receipt))
        assertNull(RelicChestPresentation.committed(opened, opened, chest.id))
        val restored = RelicRun.read(opened.json())!!
        assertEquals(opened.hero.bag, restored.hero.bag)
        assertEquals(opened.drops, restored.drops)
        assertTrue(restored.chests.first { it.id == chest.id }.opened)
    }


    @Test fun groundTreasureMustBePickedUpBeforeItCanLeaveTheRun() {
        val base = run()
        val chest = base.chests.first()
        val carried = (1..40).map {
            RelicLoot("full:$it", "占位实物", RelicTier.PRECIOUS, 430)
        }
        var state = RelicEscapeRules.reveal(base.copy(
            hero = base.hero.copy(x = chest.x, y = chest.y, bag = carried),
            mobs = emptyList(), sites = emptyList(), allies = emptyList(),
        ))
        repeat(RelicEscapeRules.openSteps(chest) - 1) {
            state = RelicEscapeRules.act(state, "pry", chest.x, chest.y)
        }
        state = RelicEscapeRules.act(state, "open", chest.x, chest.y)
        val treasure = state.drops.single { it.loot.id == state.id + ":chest:" + chest.id }.loot
        assertFalse(state.hero.bag.contains(treasure))

        // A dropped object is not secured merely because its chest was opened.
        val leftBehind = state.copy(
            hero = state.hero.copy(x = state.exitX, y = state.exitY),
            mobs = emptyList(), allies = emptyList(),
        )
        var withoutPickup = RelicEscapeRules.act(leftBehind, "extract")
        repeat(3) { withoutPickup = RelicEscapeRules.act(withoutPickup, "wait") }
        assertEquals(RelicPhase.SUCCESS, withoutPickup.phase)
        assertFalse(withoutPickup.secured.contains(treasure))
        assertTrue(withoutPickup.lost.contains(treasure))

        // Free capacity through real drop actions, then recover the same physical ID.
        for (item in carried) {
            state = RelicEscapeRules.act(state, "drop", lootId = item.id)
        }
        val restored = RelicRun.read(state.json())!!
        assertTrue(restored.drops.any { it.loot == treasure })
        state = RelicEscapeRules.act(restored, "pickup", lootId = treasure.id)
        assertTrue(state.hero.bag.contains(treasure))
        assertFalse(state.drops.any { it.loot.id == treasure.id })
        val duplicate = RelicEscapeRules.act(state, "pickup", lootId = treasure.id)
        assertEquals(state.hero.bag, duplicate.hero.bag)
        assertEquals(state.turns, duplicate.turns)

        var escaping = state.copy(
            hero = state.hero.copy(x = state.exitX, y = state.exitY),
            mobs = emptyList(), allies = emptyList(),
        )
        escaping = RelicEscapeRules.act(escaping, "extract")
        repeat(3) { escaping = RelicEscapeRules.act(escaping, "wait") }
        assertEquals(RelicPhase.SUCCESS, escaping.phase)
        assertEquals(1, escaping.secured.count { it.id == treasure.id })
        assertFalse(escaping.lost.any { it.id == treasure.id })
        val reopened = RelicRun.read(escaping.json())!!
        assertEquals(escaping.secured, reopened.secured)
    }


    @Test fun physicalGroundLootWinsOverOverlappingActiveLandmark() {
        val base = run()
        val h = base.hero
        val loot = RelicLoot("site:drop:1", "落地珍宝", RelicTier.PRECIOUS, 430)
        val scene = RelicEscapeRules.reveal(base.copy(
            chests = emptyList(), mobs = emptyList(),
            sites = listOf(RelicSite(711, h.x, h.y, "altar")),
            drops = listOf(RelicDrop(h.x, h.y, loot)),
            seen = (0 until base.w * base.h).toSet(),
        ))
        val command = relicQuickAction(scene, h.x to h.y)!!
        assertEquals("pickup", command.action)
        assertEquals(loot.id, command.id)
        val after = RelicEscapeRules.act(scene, command.action, lootId = command.id)
        assertTrue(after.hero.bag.contains(loot))
        assertFalse(after.drops.any { it.loot.id == loot.id })
        assertFalse(after.sites.single().spent)
    }


    @Test fun droppedTreasureOverlappingUnopenedChestRemainsRecoverable() {
        val base = run()
        val h = base.hero
        val chest = RelicChest(718, h.x, h.y, RelicTier.PRECIOUS)
        val loot = RelicLoot("overlap:physical:1", "地面珍宝", RelicTier.RARE, 100)
        val scene = RelicEscapeRules.reveal(base.copy(
            chests = listOf(chest), mobs = emptyList(), sites = emptyList(),
            drops = listOf(RelicDrop(h.x, h.y, loot)),
            seen = (0 until base.w * base.h).toSet(),
        ))
        val command = relicQuickAction(scene, h.x to h.y)!!
        assertEquals("pickup", command.action)
        assertEquals(loot.id, command.id)
        val after = RelicEscapeRules.act(scene, command.action, lootId = command.id)
        assertEquals(1, after.hero.bag.count { it.id == loot.id })
        assertFalse(after.drops.any { it.loot.id == loot.id })
        assertFalse(after.chests.single().opened)
        val next = relicQuickAction(after, h.x to h.y)!!
        assertEquals("open", next.action)
        val restored = RelicRun.read(after.json())!!
        assertEquals(after.hero.bag, restored.hero.bag)
        assertFalse(restored.chests.single().opened)
    }

    @Test fun dpadPicksUpPhysicalLootUnderfootBeforeFacingAnotherChest() {
        val base = run()
        val h = base.hero
        val loot = RelicLoot("underfoot:1", "脚边遗珍", RelicTier.RARE, 185)
        val chest = RelicChest(720, h.x + 1, h.y, RelicTier.COMMON)
        val scene = RelicEscapeRules.reveal(base.copy(
            chests = listOf(chest), mobs = emptyList(), sites = emptyList(),
            drops = listOf(RelicDrop(h.x, h.y, loot)),
            seen = (0 until base.w * base.h).toSet(),
        ))
        assertEquals("open", relicQuickAction(scene, chest.x to chest.y)?.action)
        val first = relicNearbyAction(scene, 1 to 0)!!
        assertEquals("pickup", first.action)
        assertEquals(loot.id, first.id)
        val picked = RelicEscapeRules.act(scene, first.action, lootId = first.id)
        assertEquals(1, picked.hero.bag.count { it.id == loot.id })
        assertFalse(picked.drops.any { it.loot.id == loot.id })
        assertFalse(picked.chests.single().opened)
        val next = relicNearbyAction(picked, 1 to 0)!!
        assertEquals("open", next.action)
        assertEquals(chest.x, next.x)
        val restored = RelicRun.read(picked.json())!!
        assertEquals(picked.hero.bag, restored.hero.bag)
        assertFalse(restored.chests.single().opened)
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
        val map = run().copy(phase = RelicPhase.EXTRACT, mobs = emptyList(),
            chests = emptyList(), sites = emptyList(),
            seen = (0 until run().w * run().h).toSet())
        val hero = map.hero.x to map.hero.y
        assertEquals("wait", relicQuickAction(map, hero)?.action)
        // A map tap is selection, not consent to spend an extraction turn.
        val empty = (map.hero.x + 3) to map.hero.y
        assertNull(relicQuickAction(map, empty))
        assertNull(relicNearbyAction(map, 1 to 0, empty))
        val unchanged = RelicRun.read(map.json())!!
        assertEquals(map.turns, unchanged.turns)
        assertEquals(map.hero.bag, unchanged.hero.bag)
        assertEquals("wait", relicQuickAction(unchanged,
            unchanged.hero.x to unchanged.hero.y)?.action)
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

    @Test fun explicitlySelectedDistantEnemyNeverRedirectsAttackToNearbyEnemy() {
        val base = run()
        val h = base.hero
        val near = RelicMob(801, h.x + 1, h.y, 5, 0)
        val distant = RelicMob(802, h.x + 3, h.y, 5, 0)
        val scene = RelicEscapeRules.reveal(base.copy(
            mobs = listOf(near, distant),
            seen = (0 until base.w * base.h).toSet(),
        ))
        assertNull(relicAttackTarget(scene, distant.x to distant.y))
        assertEquals(near.id, relicAttackTarget(scene, h.x to h.y)?.id)
        assertEquals(near.id, relicAttackTarget(scene, near.x to near.y)?.id)
        assertTrue(relicAttackHint(scene, distant.x to distant.y).contains("靠近"))
    }

    @Test fun explicitlySelectedEmptyTileNeverRedirectsAttackToNearbyEnemy() {
        val base = run()
        val h = base.hero
        val near = RelicMob(805, h.x + 1, h.y, 5, 0)
        val scene = RelicEscapeRules.reveal(base.copy(
            mobs = listOf(near),
            seen = (0 until base.w * base.h).toSet(),
        ))
        val empty = h.x to (h.y + 1)
        assertTrue(scene.mobs.none { it.x == empty.first && it.y == empty.second })
        assertNull(relicAttackTarget(scene, empty))
        assertEquals(near.id, relicAttackTarget(scene, h.x to h.y)?.id)
        assertEquals(near.id, relicAttackTarget(scene, near.x to near.y)?.id)
    }

    @Test fun defeatedSelectedEnemyNeverRedirectsAttackToAnotherLivingEnemy() {
        val base = run()
        val h = base.hero
        val defeated = RelicMob(803, h.x + 1, h.y, 0, 0)
        val living = RelicMob(804, h.x, h.y + 1, 5, 0)
        val scene = RelicEscapeRules.reveal(base.copy(
            mobs = listOf(defeated, living),
            seen = (0 until base.w * base.h).toSet(),
        ))
        assertNull(relicAttackTarget(scene, defeated.x to defeated.y))
        assertEquals(living.id, relicAttackTarget(scene, h.x to h.y)?.id)
        assertEquals(living.id, relicAttackTarget(scene, living.x to living.y)?.id)
        val noTarget = scene.copy(mobs = listOf(defeated))
        assertNull(relicAttackTarget(noTarget, defeated.x to defeated.y))
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

    @Test fun explicitUnreachableSelectionNeverOpensDifferentAdjacentChest() {
        val base = run()
        val h = base.hero
        val chest = RelicChest(990, h.x + 1, h.y, RelicTier.COMMON)
        val scene = RelicEscapeRules.reveal(base.copy(
            chests = listOf(chest), mobs = emptyList(), sites = emptyList(),
            seen = (0 until base.w * base.h).toSet(),
        ))
        val distant = h.x + 3 to h.y
        assertNull(relicNearbyAction(scene, 1 to 0, distant))
        assertNull(relicNearbyAction(scene, 1 to 0, h.x to (h.y + 1)))
        // Returning the cursor to the hero restores the direction shortcut.
        assertEquals("open", relicNearbyAction(scene, 1 to 0, h.x to h.y)?.action)
        assertEquals("open", relicNearbyAction(scene, 1 to 0, chest.x to chest.y)?.action)
        assertFalse(scene.chests.single().opened)
        assertTrue(scene.chestProgress.isEmpty())
    }

    @Test fun explicitAdjacentChestWinsOverUnrelatedUnderfootTreasure() {
        val base = run()
        val h = base.hero
        val loot = RelicLoot("ground:explicit:1", "脚下遗珍", RelicTier.RARE, 100)
        val chest = RelicChest(997, h.x + 1, h.y, RelicTier.COMMON)
        val scene = RelicEscapeRules.reveal(base.copy(
            chests = listOf(chest), mobs = emptyList(), sites = emptyList(),
            drops = listOf(RelicDrop(h.x, h.y, loot)),
            seen = (0 until base.w * base.h).toSet(),
        ))
        val tapped = relicNearbyAction(scene, 1 to 0, chest.x to chest.y)!!
        assertEquals("open", tapped.action)
        assertEquals(chest.x to chest.y, tapped.x to tapped.y)
        val started = RelicEscapeRules.act(scene,
            RelicChestPresentation.playerAction(scene, tapped.action, tapped.x, tapped.y),
            tapped.x, tapped.y)
        assertEquals(1, started.chestProgress[chest.id])
        assertTrue(started.drops.any { it.loot.id == loot.id })
        assertFalse(started.hero.bag.any { it.id == loot.id })
        // Returning the cursor to the hero deliberately picks up the same item.
        val pickup = relicNearbyAction(started, 1 to 0, h.x to h.y)!!
        assertEquals("pickup", pickup.action)
        assertEquals(loot.id, pickup.id)
        val collected = RelicEscapeRules.act(started, pickup.action, lootId = pickup.id)
        assertTrue(collected.hero.bag.any { it.id == loot.id })
        assertFalse(collected.drops.any { it.loot.id == loot.id })
        assertEquals(1, collected.chestProgress[chest.id])
        val restored = RelicRun.read(collected.json())!!
        assertTrue(restored.hero.bag.any { it.id == loot.id })
        assertEquals(1, restored.chestProgress[chest.id])
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

    @Test fun implicitAttackCursorFollowsOnlyCommittedHeroMovement() {
        val base = run()
        val h = base.hero
        val start = h.x to h.y
        val next = h.x + 1 to h.y
        val far = h.x + 2 to h.y
        assertEquals(next, relicSelectionAfterMove(start, start, next))
        assertEquals(far, relicSelectionAfterMove(far, start, next))
        assertEquals(start, relicSelectionAfterMove(start, start, start))

        // A successful physical move leaves the player able to use nearby attack
        // without selecting the new hero tile manually.
        val scene = RelicEscapeRules.reveal(base.copy(
            tiles = List(base.w * base.h) { 0 },
            chests = emptyList(), sites = emptyList(), mobs = emptyList(),
            seen = (0 until base.w * base.h).toSet()))
        val moved = RelicEscapeRules.act(scene, "move", next.first, next.second)
        assertEquals(next, moved.hero.x to moved.hero.y)
        val cursor = relicSelectionAfterMove(start, start, moved.hero.x to moved.hero.y)
        val enemy = RelicMob(986, moved.hero.x + 1, moved.hero.y, 5, 0)
        val encounter = RelicEscapeRules.reveal(moved.copy(mobs = listOf(enemy)))
        assertEquals(enemy.id, relicAttackTarget(encounter, cursor)?.id)
        assertNull(relicAttackTarget(encounter, start))
    }
}
