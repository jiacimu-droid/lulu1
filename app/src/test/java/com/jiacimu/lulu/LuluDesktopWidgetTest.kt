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

    @Test fun horoscopeCardReplacesDailyHeadingWithTheChosenSign() {
        val horoscope = LuluDesktopWidget("daily", LuluWidgetKind.HOROSCOPE, 1, 0, 0,
            characterId = "example-role", sign = 0)
        assertEquals("♑  摩羯座", luluWidgetCardHeading(horoscope))
        assertNotEquals("每日星座", luluWidgetCardHeading(horoscope))
        assertEquals("角色便签", luluWidgetCardHeading(
            horoscope.copy(kind = LuluWidgetKind.NOTE)))
        // Only the in-card heading changes; the component is still discoverable by name.
        assertEquals("每日星座", LuluWidgetKind.HOROSCOPE.title)
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

    @Test fun fourLargeWidgetsFitOneFullPageAndFifthGoesToNext() {
        val slots = MutableList<String?>(desktopPageStart(2)) { null }
        val placed = mutableListOf<LuluDesktopWidget>()
        repeat(4) {
            val added = luluPlaceWidget(LuluWidgetKind.HOROSCOPE, 1, placed, slots)!!
            assertEquals(1, added.page)
            placed.add(added)
        }
        assertEquals(listOf(0, 2, 4, 6), placed.map { it.row })
        val fifth = luluPlaceWidget(LuluWidgetKind.HOROSCOPE, 1, placed, slots)!!
        assertEquals(2, fifth.page)
        assertEquals(0, fifth.col)
        assertEquals(0, fifth.row)
    }

    @Test fun lowerRowVacanciesAcceptAppsButNeverOverwrittenByWidgets() {
        val slots = MutableList<String?>(desktopPageStart(2)) { null }
        val lastRow = desktopSlotIndex(1, 7, 3)
        slots[lastRow] = "games"
        val note = LuluDesktopWidget("bottom", LuluWidgetKind.NOTE,
            page = 1, col = 2, row = 6)
        assertFalse(luluWidgetFits(note, emptyList(), slots))
        assertTrue(luluWidgetFits(note.copy(col = 0), emptyList(), slots))
        assertEquals(8f * luluDesktopFullCellHeight(360f, 700f, 8),
            minOf(700f, 8 * luluDesktopFullCellHeight(360f, 700f, 8)), 0.01f)
    }

    @Test fun homeChatAndBulletinAreMovableAndMayChangeSizeWithoutCoveringIcons() {
        val slots = MutableList<String?>(64) { null }
        for (row in 4..7) for (col in 0..3)
            slots[desktopSlotIndex(0, row, col)] = "app-$row-$col"
        val chat = LuluDesktopWidget("home-chat", LuluWidgetKind.CHAT, 0, 0, 0)
        val bulletin = LuluDesktopWidget("home-bulletin", LuluWidgetKind.BULLETIN, 0, 0, 2)
        assertTrue(luluWidgetFits(chat, listOf(bulletin), slots))
        assertTrue(luluWidgetFits(bulletin, listOf(chat), slots))
        assertFalse(luluWidgetFits(chat.copy(row = 2), listOf(chat, bulletin), slots))
        assertFalse(luluWidgetFits(chat.copy(row = 4), listOf(chat, bulletin), slots))
        assertTrue(luluWidgetFits(chat.copy(page = 1, row = 6, columns = 2, rows = 2),
            listOf(chat, bulletin), slots))
        assertFalse(luluWidgetFits(bulletin.copy(page = 0, row = 0),
            listOf(chat, bulletin), slots))
    }
}
