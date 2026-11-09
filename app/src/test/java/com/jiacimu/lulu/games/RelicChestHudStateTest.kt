package com.jiacimu.lulu.games

import org.junit.Assert.*
import org.junit.Test

class RelicChestHudStateTest {
    private fun receipt(run: String = "run-1", chest: Int = 7, inBag: Boolean = true) =
        RelicChestReceipt(run, chest, RelicLoot("loot-1", "珍宝", RelicTier.RARE, 250), inBag)

    @Test fun skipAndTimedDismissPreserveCommittedBagEvidence() {
        for (inBag in listOf(true, false)) {
            val actual = receipt(inBag = inBag)
            val opened = RelicChestHudState().committed(actual)
            assertEquals(actual, opened.opening)
            assertEquals(actual, opened.lastCommitted)
            val dismissed = opened.dismiss(actual)
            assertNull(dismissed.opening)
            assertEquals(inBag, dismissed.lastCommitted?.inBag)
            assertEquals(actual.loot, dismissed.lastCommitted?.loot)
            // Dismissing twice cannot erase the actual result or mint another item.
            assertEquals(dismissed, dismissed.dismiss(actual))
        }
    }

    @Test fun staleAnimationCannotDismissNewerCommittedOpening() {
        val old = receipt()
        val newer = receipt(chest = 8)
        val state = RelicChestHudState().committed(old).committed(newer)
        assertEquals(state, state.dismiss(old))
        assertEquals(newer, state.dismiss(newer).lastCommitted)
    }

    @Test fun nextActionClearsOldHudWithoutChangingAnyWorldInventory() {
        val evidence = receipt()
        val state = RelicChestHudState().committed(evidence).dismiss(evidence)
        assertEquals(RelicChestHudState(), state.nextAction())
        assertEquals(evidence.loot, evidence.loot)
    }
    @Test fun rejectedWorldActionKeepsBothOpeningAndPhysicalReceipt() {
        for (inBag in listOf(true, false)) {
            val actual = receipt(inBag = inBag)
            val before = RelicChestHudState().committed(actual)
            val rejected = before.afterWorldAction(committed = false)
            assertEquals(before, rejected)
            assertEquals(actual, rejected.opening)
            assertEquals(actual, rejected.lastCommitted)
            // A later successful world action clears only the presentation.
            assertEquals(RelicChestHudState(), rejected.afterWorldAction(committed = true))
        }
    }

}
