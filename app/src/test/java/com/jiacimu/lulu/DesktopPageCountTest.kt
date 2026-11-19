package com.jiacimu.lulu

import org.junit.Assert.*
import org.junit.Test

class DesktopPageCountTest {
    @Test fun everyPageHasSevenRowsAndTwentyEightCells() {
        assertEquals(7, DESKTOP_ROWS)
        assertEquals(0, desktopPageStart(0))
        assertEquals(28, desktopPageStart(1))
        assertEquals(56, desktopPageStart(2))
        assertEquals(0, desktopPageForSlot(27))
        assertEquals(1, desktopPageForSlot(28))
        assertEquals(2, desktopAllocatedPageCount(28))
        assertEquals(3, desktopAllocatedPageCount(29))
        assertEquals(3, desktopAllocatedPageCount(56))
        assertEquals(4, desktopAllocatedPageCount(57))
    }

    @Test fun squareGridKeepsExactWidgetFootprintsAndCentersTheSpareSpace() {
        for ((width, height) in listOf(320 to 712, 373 to 739, 600 to 449)) {
            val grid = DesktopGridGeometry.fit(width, height)
            val gridWidth = grid.x(DESKTOP_COLUMNS) - grid.x(0)
            val gridHeight = grid.y(DESKTOP_ROWS) - grid.y(0)
            assertEquals(gridWidth / DESKTOP_COLUMNS, gridHeight / DESKTOP_ROWS)
            assertTrue(kotlin.math.abs(grid.leftPx - (width - grid.x(DESKTOP_COLUMNS))) <= 1)
            assertTrue(kotlin.math.abs(grid.topPx - (height - grid.y(DESKTOP_ROWS))) <= 1)
            assertEquals(grid.x(2) - grid.x(0), grid.y(2) - grid.y(0))
            assertEquals(grid.x(4) - grid.x(0), grid.y(4) - grid.y(0))
            assertEquals((grid.x(2) - grid.x(0)) / 2 * 3, grid.y(3) - grid.y(0))
            assertEquals((grid.x(2) - grid.x(0)) * 2, grid.y(4) - grid.y(0))
        }
    }

    @Test fun hitTestingUsesTheSameCenteredCellBoundaries() {
        val grid = DesktopGridGeometry.fit(373, 739)
        assertNull(grid.cellAt(grid.leftPx.toFloat(), grid.topPx - .5f))
        assertNull(grid.cellAt(grid.x(4).toFloat(), grid.topPx.toFloat()))
        assertNull(grid.cellAt(grid.leftPx.toFloat(), grid.y(7).toFloat()))
        assertEquals(DesktopGridCell(0, 0), grid.cellAt(grid.x(0).toFloat(), grid.y(0).toFloat()))
        assertEquals(DesktopGridCell(6, 3), grid.cellAt(grid.x(4) - .5f, grid.y(7) - .5f))
        assertEquals(DesktopGridCell(3, 2), grid.cellAt(grid.x(2).toFloat(), grid.y(3).toFloat()))
    }

    @Test fun sixRowUpgradeKeepsCoordinatesAcrossPagesAndEmptyTrailingPages() {
        val old = MutableList<String?>(96) { null }.apply {
            this[23] = "chat"
            this[24] = "settings"
            this[24 + 21] = "guide"
            this[48] = "games"
        }
        val photo = LuluDesktopWidget("photo", LuluWidgetKind.PHOTO, 1, 2, 1, imageToken = "keepsake")
        val home = migrateDesktopGrid(old, listOf(photo), 4, old.filterNotNull())
        assertEquals("chat", home.slots[desktopSlotIndex(0, 5, 3)])
        assertEquals("settings", home.slots[desktopSlotIndex(1, 0, 0)])
        assertEquals("guide", home.slots[desktopSlotIndex(1, 5, 1)])
        assertEquals("games", home.slots[desktopSlotIndex(2, 0, 0)])
        assertEquals(photo, home.widgets.single())
        assertEquals(4, desktopStoredPageCount(home.slots.size))
        assertNull(home.slots[desktopSlotIndex(0, 6, 3)])
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
        assertEquals("settings", layout.slots[desktopSlotIndex(0, 6, 0)])
        assertEquals(setOf("chat", "settings", "guide", "games"), layout.slots.filterNotNull().toSet())
        assertEquals(cards.first(), layout.widgets.first())
        assertEquals(cards.last().copy(row = 5), layout.widgets.last())
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
