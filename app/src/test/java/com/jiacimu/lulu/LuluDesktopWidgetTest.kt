package com.jiacimu.lulu

import java.time.LocalDate
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class LuluDesktopWidgetTest {
    @Test fun desktopGridSizesUseWidthNotEntirePortraitHeight() {
        // Redmi-like tall viewport: the old 4-row division gave 4×2 an almost square card.
        val width = 360f
        val tallPageHeight = 760f
        val row = luluDesktopFullCellHeight(width, tallPageHeight)
        assertEquals(93.6f, row, 0.1f)
        val widget2x1Ratio = (2f * width / 4f) / row
        val widget2x2Ratio = (2f * width / 4f) / (2 * row)
        val widget4x2Ratio = width / (2 * row)
        assertTrue("2×1 should be horizontal", widget2x1Ratio > 1.7f)
        assertTrue("2×2 should be close to square", widget2x2Ratio in 0.85f..1.15f)
        assertTrue("4×2 should be horizontal", widget4x2Ratio > 1.7f)
        assertTrue("4 rows must stay within the page", row * 4 <= tallPageHeight)
        // Landscape/small windows must not render card rows below the viewport.
        assertEquals(65f, luluDesktopFullCellHeight(360f, 260f), 0.001f)
    }

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
