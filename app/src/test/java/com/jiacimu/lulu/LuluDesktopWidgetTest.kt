package com.jiacimu.lulu

import java.time.LocalDate
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class LuluDesktopWidgetTest {
    @Test fun profileBirthdayMapsToExactSignsAndBadInputNeverGuesses() {
        assertEquals(0, luluSignFromBirthday("2001-01-19"))
        assertEquals(1, luluSignFromBirthday("2001-01-20"))
        assertEquals(2, luluSignFromBirthday("2月19日"))
        assertEquals(5, luluSignFromBirthday("06/04"))
        assertEquals(11, luluSignFromBirthday("12月21日"))
        assertEquals(0, luluSignFromBirthday("12月22日"))
        assertEquals(null, luluSignFromBirthday("2月30日"))
        assertEquals(null, luluSignFromBirthday("没有写日期"))
    }

    @Test fun aFortuneIsStableForOneLocalDayAndSign() {
        val day = LocalDate.of(2026, 11, 18)
        val value = luluDailyFortune(day, 2)
        assertEquals(value, luluDailyFortune(day, 2))
        assertTrue(value.first.isNotBlank())
        assertTrue(value.second.isNotBlank())
        assertNotEquals(value, luluDailyFortune(day.plusDays(1), 2))
    }

    @Test fun addingWidgetsAvoidsEveryOccupiedLauncherCellAndWidget() {
        val slots = MutableList<String?>(32) { null }
        slots[16] = "chat"
        val first = luluPlaceWidget(LuluWidgetKind.NOTE, 1, emptyList(), slots)!!
        assertTrue(luluWidgetFits(first, emptyList(), slots))
        assertFalse(first.col == 0 && first.row == 0)
        val second = luluPlaceWidget(LuluWidgetKind.HOROSCOPE, 1, listOf(first), slots)!!
        assertTrue(luluWidgetFits(second, listOf(first), slots))
        assertFalse(luluWidgetFits(second.copy(
            col = first.col, row = first.row, columns = 2, rows = 2,
        ), listOf(first), slots))
    }

    @Test fun aFullPageAllocatesAnotherWithoutErasingExistingItems() {
        val slots = MutableList<String?>(32) { null }
        val first = luluPlaceWidget(LuluWidgetKind.HOROSCOPE, 1, emptyList(), slots)!!
        val second = luluPlaceWidget(LuluWidgetKind.HOROSCOPE, 1, listOf(first), slots)!!
        assertEquals(1, first.page)
        assertEquals(1, second.page)
        val third = luluPlaceWidget(LuluWidgetKind.HOROSCOPE, 1, listOf(first, second), slots)!!
        assertEquals(2, third.page)
        assertEquals(0, third.col)
        assertEquals(0, third.row)
    }

    @Test fun cannotDragAWidgetOntoTheFirstDashboardOrOverASecondWidget() {
        val slots = List<String?>(32) { null }
        val first = luluPlaceWidget(LuluWidgetKind.NOTE, 1, emptyList(), slots)!!
        val second = luluPlaceWidget(LuluWidgetKind.NOTE, 1, listOf(first), slots)!!
        assertFalse(luluWidgetFits(first.copy(page = 0), listOf(first, second), slots))
        assertFalse(luluWidgetFits(first.copy(col = second.col, row = second.row),
            listOf(first, second), slots))
    }
}
