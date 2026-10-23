package com.jiacimu.lulu.data

import org.junit.Assert.*
import org.junit.Test

class AutonomousAffordanceContextTest {
    @Test fun offersOnlyExistingBookAndGroupsAndExistingWorldIdentifiers() {
        val text = AutonomousAffordanceContext.render(
            digital = true, location = "shared:reading_lounge",
            books = listOf(AutonomousAffordanceContext.Book("book-1", "真实书名")),
            groups = listOf("group-1" to "朋友群"),
            locationActivities = listOf("browse_reading" to "看书架"),
            publicPlaces = listOf("shared:courtyard" to "庭院"),
        )
        assertTrue(text.contains("readingBookId=book-1"))
        assertTrue(text.contains("groupId=group-1"))
        assertTrue(text.contains("activityId=browse_reading"))
        assertTrue(text.contains("location=shared:courtyard"))
        assertFalse(text.contains("book-2"))
    }

    @Test fun aRealColocatedResidentCanBeMetButAnAbsentOneCannotBeInvented() {
        val nearby = AutonomousAffordanceContext.render(
            digital = true, location = DigitalWorldPublicPlaces.CAFE,
            books = emptyList(), groups = emptyList(),
            currentResidents = listOf(Triple("resident-37", "闻澜", "公共区域维护员")),
        )
        assertTrue(nearby.contains("worldAction=interact_resident"))
        assertTrue(nearby.contains("itemId=resident-37"))
        assertTrue(nearby.contains("greet|chat|ask_place|sit_together"))
        val empty = AutonomousAffordanceContext.render(
            digital = true, location = DigitalWorldPublicPlaces.COURTYARD,
            books = emptyList(), groups = emptyList(),
        )
        assertFalse(empty.contains("worldAction=interact_resident"))
        assertTrue(empty.contains("没有登记在场的居民"))
    }

    @Test fun actualLifeChoiceOffersVoluntaryFuturePlanningWithoutMandatoryScheduling() {
        val text = AutonomousAffordanceContext.render(
            digital = true, location = DigitalWorldPublicPlaces.CAFE,
            books = listOf(AutonomousAffordanceContext.Book("readable-1", "晚点想读的书")),
            groups = emptyList(),
        )
        assertTrue(text.contains("plan_personal_activity"))
        assertTrue(text.contains("activityAction"))
        assertTrue(text.contains("ISO8601"))
        assertTrue(text.contains("本人日程工具"))
        assertTrue(text.contains("只在心声里写"))
        assertTrue(text.contains("暂时不行动"))
        assertTrue(text.contains("到点由本人醒来选择真实执行"))
        assertTrue(text.contains("同一 ToolRouter 权限和状态核验"))
        assertTrue(text.contains("无需主人发号施令或者指定几点"))
    }

    @Test fun nonDigitalCharactersDoNotGetInventedWorldAccess() {
        val text = AutonomousAffordanceContext.render(
            digital = false, location = "", books = emptyList(), groups = emptyList(),
        )
        assertFalse(text.contains("worldAction=visit_public_place"))
        assertFalse(text.contains("readingBookId="))
        assertTrue(text.contains("solo_game"))
    }
}
