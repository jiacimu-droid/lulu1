package com.jiacimu.lulu

import org.junit.Assert.assertEquals
import org.junit.Test

class DesktopPageCountTest {
    @Test fun dashboardHasFourRowsButFullPagesHaveEightRows() {
        assertEquals(4, desktopPageRows(0))
        assertEquals(8, desktopPageRows(1))
        assertEquals(8, desktopPageRows(2))
        assertEquals(16, desktopPageStart(1))
        assertEquals(48, desktopPageStart(2))
        assertEquals(80, desktopPageStart(3))
        assertEquals(1, desktopPageForSlot(47))
        assertEquals(2, desktopPageForSlot(48))
        assertEquals(2, desktopAllocatedPageCount(16))
        assertEquals(3, desktopAllocatedPageCount(17))
        assertEquals(3, desktopAllocatedPageCount(48))
        assertEquals(4, desktopAllocatedPageCount(49))
    }

    @Test fun oldFourByFourPagesUpgradeWithoutMovingVisibleIcons() {
        val old = MutableList<String?>(48) { null }
        old[0] = "chat"
        old[15] = "memory"
        old[17] = "games"
        old[31] = "settings"
        old[32] = "guide"
        val migrated = desktopMigrateLegacySlots(old)
        assertEquals(80, migrated.size)
        assertEquals("chat", migrated[desktopSlotIndex(0, 0, 0)])
        assertEquals("memory", migrated[desktopSlotIndex(0, 3, 3)])
        assertEquals("games", migrated[desktopSlotIndex(1, 0, 1)])
        assertEquals("settings", migrated[desktopSlotIndex(1, 3, 3)])
        assertEquals("guide", migrated[desktopSlotIndex(2, 0, 0)])
    }

    @Test fun draggingAcrossFullPagesUsesEveryFreeCell() {
        val slots = MutableList<String?>(desktopPageStart(2)) { null }
        slots[0] = "chat"
        slots[15] = "settings"
        slots[desktopSlotIndex(1, 0, 2)] = "games"
        val target = desktopSlotIndex(1, 7, 0)
        val moved = desktopMoveLauncher(slots, 0, target)
        assertEquals(desktopPageStart(3), moved.size)
        assertEquals(null, moved[0])
        assertEquals("chat", moved[target])
        assertEquals("settings", moved[15])
        assertEquals("games", moved[desktopSlotIndex(1, 0, 2)])
        assertEquals("games", desktopMoveLauncher(moved, desktopSlotIndex(1, 0, 2), 0)[0])
    }
}
