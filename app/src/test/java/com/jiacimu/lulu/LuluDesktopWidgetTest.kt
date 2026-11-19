package com.jiacimu.lulu

import java.time.LocalDate
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class LuluDesktopWidgetTest {
    @Test fun newWidgetsAndDefaultShortcutUseDesktopPalette() {
        assertEquals(LULU_WIDGET_DESKTOP_SKIN, LuluWidgetKind.CHAT.defaultSkin)
        assertEquals(LULU_WIDGET_DESKTOP_SKIN, LuluWidgetKind.CALENDAR.defaultSkin)
        assertEquals(2, LuluWidgetKind.CALENDAR.columns)
        assertEquals(1, LuluWidgetKind.CALENDAR.rows)
        assertEquals(2, LuluWidgetKind.PHOTO.rows)
        assertNotEquals(LuluWidgetKind.CHAT.defaultSkin, LuluWidgetKind.PHOTO.defaultSkin)
    }

    @Test fun calendarAndClockUseTextOnlyWithoutChangingTheirGridSize() {
        val calendar = LuluDesktopWidget("calendar", LuluWidgetKind.CALENDAR, 1, 0, 0)
        val clock = LuluDesktopWidget("clock", LuluWidgetKind.CLOCK, 1, 2, 0)
        assertTrue(calendar.hasBorderlessSkin)
        assertTrue(clock.hasBorderlessSkin)
        assertEquals(2 to 1, clock.columns to clock.rows)
        assertFalse(clock.copy(skin = 1).hasBorderlessSkin)
        assertFalse(calendar.copy(kind = LuluWidgetKind.CHAT).hasBorderlessSkin)
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
        val slots = MutableList<String?>(desktopPageStart(2)) { null }
        slots[desktopSlotIndex(1, 0, 0)] = "chat"
        val first = luluPlaceWidget(LuluWidgetKind.NOTE, 1, emptyList(), slots)!!
        assertTrue(luluWidgetFits(first, emptyList(), slots))
        assertFalse(first.col == 0 && first.row == 0)
        val second = luluPlaceWidget(LuluWidgetKind.HOROSCOPE, 1, listOf(first), slots)!!
        assertTrue(luluWidgetFits(second, listOf(first), slots))
        assertFalse(luluWidgetFits(second.copy(
            col = first.col, row = first.row, columns = 2, rows = 2,
        ), listOf(first), slots))
    }

    @Test fun threeLargeWidgetsFitOneFullPageAndFourthGoesToNext() {
        val slots = MutableList<String?>(desktopPageStart(2)) { null }
        val placed = mutableListOf<LuluDesktopWidget>()
        repeat(3) {
            val added = luluPlaceWidget(LuluWidgetKind.HOROSCOPE, 1, placed, slots)!!
            assertEquals(1, added.page)
            placed.add(added)
        }
        assertEquals(listOf(0, 2, 4), placed.map { it.row })
        val fourth = luluPlaceWidget(LuluWidgetKind.HOROSCOPE, 1, placed, slots)!!
        assertEquals(2, fourth.page)
        assertEquals(0, fourth.col)
        assertEquals(0, fourth.row)
    }

    @Test fun lowerRowVacanciesAcceptAppsButNeverOverwrittenByWidgets() {
        val slots = MutableList<String?>(desktopPageStart(2)) { null }
        val lastRow = DESKTOP_ROWS - 1
        slots[desktopSlotIndex(1, lastRow, 3)] = "games"
        val note = LuluDesktopWidget("bottom", LuluWidgetKind.NOTE,
            page = 1, col = 2, row = lastRow - 1)
        assertFalse(luluWidgetFits(note, emptyList(), slots))
        assertTrue(luluWidgetFits(note.copy(col = 0), emptyList(), slots))
        assertFalse(luluWidgetFits(note.copy(col = 0, row = lastRow), emptyList(), slots))
    }

    @Test fun freshInstallDesktopShowsDateThenChatAndBulletinWithAppsBelow() {
        val cards = firstInstallDesktopWidgets()
        assertEquals(listOf(LuluWidgetKind.CALENDAR, LuluWidgetKind.CHAT, LuluWidgetKind.BULLETIN),
            cards.map { it.kind })
        assertEquals(listOf(0, 2, 3), cards.map { it.row })
        assertEquals(listOf(2, 1, 1), cards.map { it.rows })
        assertTrue(cards.all { it.page == 0 && it.col == 0 && it.columns == DESKTOP_COLUMNS })
        assertTrue(cards.all { card -> luluWidgetFits(card, cards, emptyList()) })
        assertTrue(cards.all { it.characterId.isEmpty() })

        val launcherIds = (1..15).map { "app-$it" }
        val slots = completeDesktopSlots(emptyList(), cards, launcherIds)
        for (row in 0..3) for (column in 0 until DESKTOP_COLUMNS)
            assertNull(slots[desktopSlotIndex(0, row, column)])
        assertEquals(launcherIds.first(), slots[desktopSlotIndex(0, 4, 0)])
        assertEquals(launcherIds[11], slots[desktopSlotIndex(0, 6, 3)])
        assertEquals(launcherIds[12], slots[desktopSlotIndex(1, 0, 0)])
        assertEquals(launcherIds.toSet(), slots.filterNotNull().toSet())
    }

    @Test fun olderSavedOrEditedDesktopsNeverGetStarterWidgetsReseeded() {
        assertTrue(shouldSeedFirstInstallDesktop(
            hasSavedCards = false, hasAnyPreviousGrid = false, previouslySeeded = false))
        assertFalse(shouldSeedFirstInstallDesktop(
            hasSavedCards = true, hasAnyPreviousGrid = false, previouslySeeded = false))
        assertFalse(shouldSeedFirstInstallDesktop(
            hasSavedCards = false, hasAnyPreviousGrid = true, previouslySeeded = false))
        assertFalse(shouldSeedFirstInstallDesktop(
            hasSavedCards = false, hasAnyPreviousGrid = false, previouslySeeded = true))
    }

    @Test fun homeChatAndBulletinAreMovableAndMayChangeSizeWithoutCoveringIcons() {
        val slots = MutableList<String?>(desktopPageStart(2)) { null }
        for (row in 4..5) for (col in 0..3)
            slots[desktopSlotIndex(0, row, col)] = "app-$row-$col"
        val chat = LuluDesktopWidget("home-chat", LuluWidgetKind.CHAT, 0, 0, 0)
        val bulletin = LuluDesktopWidget("home-bulletin", LuluWidgetKind.BULLETIN, 0, 0, 2)
        assertTrue(luluWidgetFits(chat, listOf(bulletin), slots))
        assertTrue(luluWidgetFits(bulletin, listOf(chat), slots))
        assertFalse(luluWidgetFits(chat.copy(row = 2), listOf(chat, bulletin), slots))
        assertFalse(luluWidgetFits(chat.copy(row = 4), listOf(chat, bulletin), slots))
        assertTrue(luluWidgetFits(chat.copy(page = 1, row = 4, columns = 2, rows = 2),
            listOf(chat, bulletin), slots))
        assertFalse(luluWidgetFits(bulletin.copy(page = 0, row = 0),
            listOf(chat, bulletin), slots))
    }
}
