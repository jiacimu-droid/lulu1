package com.jiacimu.lulu

import org.junit.Assert.assertEquals
import org.junit.Test

class DesktopPageCountTest {
    @Test fun everyPageHasEightRowsAndThirtyTwoCells() {
        assertEquals(8, desktopPageRows(0))
        assertEquals(8, desktopPageRows(1))
        assertEquals(0, desktopPageStart(0))
        assertEquals(32, desktopPageStart(1))
        assertEquals(64, desktopPageStart(2))
        assertEquals(0, desktopPageForSlot(31))
        assertEquals(1, desktopPageForSlot(32))
        assertEquals(2, desktopAllocatedPageCount(32))
        assertEquals(3, desktopAllocatedPageCount(33))
        assertEquals(3, desktopAllocatedPageCount(64))
        assertEquals(4, desktopAllocatedPageCount(65))
    }

    @Test fun oldFourByFourPagesPreservePositionsWhileHomeIconsMoveBelowCards() {
        val old = MutableList<String?>(48) { null }
        old[0] = "chat"
        old[15] = "memory"
        old[17] = "games"
        old[31] = "settings"
        old[32] = "guide"
        val migrated = desktopMigrateLegacySlots(old)
        assertEquals(96, migrated.size)
        assertEquals("chat", migrated[desktopSlotIndex(0, 4, 0)])
        assertEquals("memory", migrated[desktopSlotIndex(0, 7, 3)])
        assertEquals("games", migrated[desktopSlotIndex(1, 0, 1)])
        assertEquals("settings", migrated[desktopSlotIndex(1, 3, 3)])
        assertEquals("guide", migrated[desktopSlotIndex(2, 0, 0)])
    }

    @Test fun recentMixedGridAlsoMigratesWithoutLosingIcons() {
        val old = MutableList<String?>(80) { null }
        old[0] = "chat"
        old[15] = "memory"
        old[16] = "games"
        old[47] = "settings"
        old[48] = "guide"
        val migrated = desktopMigrateMixedSlots(old)
        assertEquals(96, migrated.size)
        assertEquals("chat", migrated[desktopSlotIndex(0, 4, 0)])
        assertEquals("memory", migrated[desktopSlotIndex(0, 7, 3)])
        assertEquals("games", migrated[desktopSlotIndex(1, 0, 0)])
        assertEquals("settings", migrated[desktopSlotIndex(1, 7, 3)])
        assertEquals("guide", migrated[desktopSlotIndex(2, 0, 0)])
    }

    @Test fun draggingIntoBottomRowMovesIconWithoutLosingOtherApps() {
        val slots = MutableList<String?>(desktopPageStart(2)) { null }
        slots[desktopSlotIndex(0, 4, 0)] = "chat"
        slots[desktopSlotIndex(1, 0, 2)] = "games"
        val target = desktopSlotIndex(0, 7, 0)
        val moved = desktopMoveLauncher(slots, desktopSlotIndex(0, 4, 0), target)
        assertEquals("chat", moved[target])
        assertEquals(null, moved[desktopSlotIndex(0, 4, 0)])
        assertEquals("games", moved[desktopSlotIndex(1, 0, 2)])
    }
}
