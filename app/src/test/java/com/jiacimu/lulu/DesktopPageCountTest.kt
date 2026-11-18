package com.jiacimu.lulu

import org.junit.Assert.*
import org.junit.Test

class DesktopPageCountTest {
    @Test fun everyPageHasSixRowsAndTwentyFourCells() {
        assertEquals(6, DESKTOP_ROWS)
        assertEquals(0, desktopPageStart(0))
        assertEquals(24, desktopPageStart(1))
        assertEquals(48, desktopPageStart(2))
        assertEquals(0, desktopPageForSlot(23))
        assertEquals(1, desktopPageForSlot(24))
        assertEquals(2, desktopAllocatedPageCount(24))
        assertEquals(3, desktopAllocatedPageCount(25))
        assertEquals(3, desktopAllocatedPageCount(48))
        assertEquals(4, desktopAllocatedPageCount(49))
    }

    @Test fun eightRowUpgradePreservesInBoundsItemsAndRelocatesOverflow() {
        val old = MutableList<String?>(64) { null }
        old[4] = "chat"
        old[24] = "settings"
        old[31] = "guide"
        old[38] = "games"
        val cards = listOf(
            LuluDesktopWidget("calendar", LuluWidgetKind.CALENDAR, 0, 1, 1),
            LuluDesktopWidget("anniversary", LuluWidgetKind.COUNTDOWN, 0, 2, 6,
                skin = 1, targetDate = "2027-01-01", imageToken = "keepsake"),
        )
        val layout = migrateDesktopGrid(old, cards, 3, listOf("chat", "settings", "guide", "games"))
        assertEquals("chat", layout.slots[desktopSlotIndex(0, 1, 0)])
        assertEquals("games", layout.slots[desktopSlotIndex(1, 1, 2)])
        assertEquals(setOf("chat", "settings", "guide", "games"), layout.slots.filterNotNull().toSet())
        assertEquals(cards.first(), layout.widgets.first())
        assertEquals(cards.last().copy(row = 4), layout.widgets.last())
        layout.widgets.forEach { assertTrue(luluWidgetFits(it, layout.widgets, layout.slots)) }
    }

    @Test fun fullPageMovesOverflowToSparePageAndKeepsItCollisionFree() {
        val old = MutableList<String?>(64) { null }
        old[61] = "app"
        val cards = List(4) { index ->
            LuluDesktopWidget("card-$index", LuluWidgetKind.HOROSCOPE, 0, 0, index * 2)
        }
        val home = migrateDesktopGrid(old, cards, 3, listOf("app"))
        assertEquals(listOf(0, 0, 0, 2), home.widgets.map { it.page })
        assertEquals("app", home.slots[desktopSlotIndex(1, 0, 0)])
        home.widgets.forEach { assertTrue(luluWidgetFits(it, home.widgets, home.slots)) }
        assertEquals(4, desktopStoredPageCount(home.slots.size))
    }

    @Test fun bothOlderFormatsKeepTheirValidPageLocations() {
        val v1 = MutableList<String?>(48) { null }.apply {
            this[0] = "chat"
            this[15] = "memory"
            this[17] = "games"
            this[32] = "guide"
        }
        val ids = v1.filterNotNull()
        val home = migrateDesktopGrid(v1, emptyList(), 1, ids)
        assertEquals("chat", home.slots[desktopSlotIndex(0, 4, 0)])
        assertEquals("games", home.slots[desktopSlotIndex(1, 0, 1)])
        assertEquals("guide", home.slots[desktopSlotIndex(2, 0, 0)])
        assertEquals(ids.toSet(), home.slots.filterNotNull().toSet())

        val v2 = MutableList<String?>(80) { null }.apply {
            this[0] = "chat"
            this[24] = "games"
            this[47] = "memory"
            this[48] = "guide"
        }
        val mixed = migrateDesktopGrid(v2, emptyList(), 2, ids)
        assertEquals("chat", mixed.slots[desktopSlotIndex(0, 4, 0)])
        assertEquals("games", mixed.slots[desktopSlotIndex(1, 2, 0)])
        assertEquals("guide", mixed.slots[desktopSlotIndex(2, 0, 0)])
        assertEquals(ids.toSet(), mixed.slots.filterNotNull().toSet())
    }
}
