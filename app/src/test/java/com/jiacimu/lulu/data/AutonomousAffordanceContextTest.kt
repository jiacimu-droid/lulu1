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
        // This layer projects concrete opportunities, not a second copy of the
        // full schedule protocol. Time formats, permissions and step ordering
        // belong to the existing decision and registered-executor contracts.
        assertTrue(text.contains("plan_personal_activity"))
        assertTrue(text.contains("activityAction"))
        assertTrue(text.contains("read_book"))
        assertTrue(text.contains("play_solo_game"))
        assertTrue(text.contains("digital_world_action"))
        assertTrue(text.contains("稍后安排"))
        assertTrue(text.contains("独处"))
        assertTrue(text.contains("不是强制任务"))
        assertTrue(text.contains("日程只保存意愿"))
        assertTrue(text.contains("到点仍须实际执行和取得回执"))
        assertTrue(text.contains("已配置且授权"))
        // The current list must never pretend it is also a completed action.
        assertFalse(text.contains("已自动完成"))
    }

    @Test fun cafeWarmWaterRemainsSelectableEvenAfterLargeBaristaMenu() {
        val options = (1..13).map { "cafe_order_$it" to "咖啡菜单第${it}项" } +
            listOf("order_drink" to "领取一杯温水", "cafe_sit" to "坐一下")
        val prompt = AutonomousAffordanceContext.render(
            digital = true, location = DigitalWorldPublicPlaces.CAFE,
            books = emptyList(), groups = emptyList(),
            locationActivities = options,
        )
        assertTrue(prompt.contains("activityId=order_drink；领取一杯温水"))
        assertTrue(prompt.contains("activityId=cafe_order_13"))
        assertTrue(prompt.contains("不能按动作描述拼出新 ID"))
        assertFalse(prompt.contains("cafe_take_warm_water"))
        // Execution must still reject invented names instead of fabricating a receipt.
        assertNull(DigitalWorldActivityCatalog.locationActivitySummary(
            "某角色", DigitalWorldPublicPlaces.CAFE, "cafe_take_warm_water", "浮光咖啡角"))
        assertNotNull(DigitalWorldActivityCatalog.locationActivitySummary(
            "某角色", DigitalWorldPublicPlaces.CAFE, "order_drink", "浮光咖啡角"))
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
