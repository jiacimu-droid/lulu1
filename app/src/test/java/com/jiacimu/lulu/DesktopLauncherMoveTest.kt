package com.jiacimu.lulu

import org.junit.Assert.*
import org.junit.Test

class DesktopLauncherMoveTest {
    private fun pages(vararg ids: Pair<Int, String>): List<String?> =
        MutableList<String?>(desktopPageStart(2)) { null }
            .apply { ids.forEach { (i, id) -> this[i] = id } }

    @Test fun canMoveAnIconWithinThePage() {
        val original = pages(0 to "chat", 1 to "memory")
        val updated = desktopMoveLauncher(original, 0, 1)
        assertEquals("memory", updated[0])
        assertEquals("chat", updated[1])
    }

    @Test fun canMoveAcrossPagesIntoAnEmptySlotAndGrowsNextPage() {
        val original = pages(0 to "chat", 2 to "memory")
        val target = desktopSlotIndex(1, 5, 2)
        val updated = desktopMoveLauncher(original, 0, target)
        assertNull(updated[0])
        assertEquals("memory", updated[2])
        assertEquals("chat", updated[target])
        assertEquals(desktopPageStart(3), updated.size)
    }

    @Test fun invalidDragCannotDeleteIcons() {
        val original = pages(0 to "chat")
        assertEquals(original, desktopMoveLauncher(original, 0, 100))
        assertEquals(original, desktopMoveLauncher(original, 3, 10))
    }
}
