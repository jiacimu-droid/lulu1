package com.jiacimu.lulu

import org.junit.Assert.assertEquals
import org.junit.Test

class DesktopPageCountTest {
    @Test fun smallDesktopStillHasSecondPage() {
        assertEquals(2, desktopAllocatedPageCount(0))
        assertEquals(2, desktopAllocatedPageCount(15))
        assertEquals(2, desktopAllocatedPageCount(16))
    }

    @Test fun fullScreenPageKeepsIndependentSixteenSlotsWhenDraggingAcrossPages() {
        val slots = MutableList<String?>(32) { null }
        slots[0] = "chat"
        slots[15] = "settings"
        slots[18] = "games"
        val moved = desktopMoveLauncher(slots, 0, 20)
        // Page 2 becomes occupied, so keep one extra empty page for future dragging.
        assertEquals(48, moved.size)
        assertEquals(null, moved[0])
        assertEquals("chat", moved[20])
        assertEquals("settings", moved[15])
        assertEquals("games", moved[18])
        // Moving a second-page icon back does not shift or extend the first grid.
        val swapped = desktopMoveLauncher(moved, 18, 0)
        assertEquals("games", swapped[0])
        assertEquals("chat", swapped[20])
        assertEquals(null, swapped[18])
    }

    @Test fun addingAppsCreatesAnotherBlankPage() {
        assertEquals(3, desktopAllocatedPageCount(17))
        assertEquals(3, desktopAllocatedPageCount(32))
        assertEquals(4, desktopAllocatedPageCount(33))
    }
}
