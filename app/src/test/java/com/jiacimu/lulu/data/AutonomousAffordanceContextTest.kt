package com.jiacimu.lulu.data

import org.junit.Assert.*
import org.junit.Test
import java.time.Instant
import java.time.temporal.ChronoUnit

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

    @Test fun cafeTouchAndSkyWatchingNeedRealLocationActionAndObservedWorldTime() {
        val evening = Instant.parse("2026-11-16T10:30:00Z") // Shared world local 18:30.
        val morning = Instant.parse("2026-11-16T00:30:00Z")
        val offered = DigitalWorldActivityCatalog.locationOptions(DigitalWorldPublicPlaces.CAFE)
        assertTrue(offered.any { it.first == "cafe_touch_table" })
        val touch = DigitalWorldActivityCatalog.locationActivitySummary(
            "江渡", DigitalWorldPublicPlaces.CAFE, "cafe_touch_table", "浮光咖啡角", evening)
        assertTrue(touch.orEmpty().contains("平整光滑的数字材质"))
        assertNull(DigitalWorldActivityCatalog.locationActivitySummary(
            "江渡", DigitalWorldPublicPlaces.COURTYARD, "cafe_touch_table", "共生庭院", evening))
        assertTrue(DigitalWorldActivityCatalog.locationOptions(DigitalWorldPublicPlaces.COURTYARD)
            .any { it.first == "courtyard_feel_air" })
        val air = DigitalWorldActivityCatalog.locationActivitySummary(
            "江渡", DigitalWorldPublicPlaces.COURTYARD, "courtyard_feel_air",
            "共生庭院", evening)
        val actualWorld = DigitalWorldEnvironment.snapshot(evening)
        assertTrue(air.orEmpty().contains("${actualWorld.temperatureCelsius}℃"))
        assertTrue(air.orEmpty().contains(actualWorld.season))
        assertNull(DigitalWorldActivityCatalog.locationActivitySummary(
            "江渡", DigitalWorldPublicPlaces.CAFE, "courtyard_feel_air",
            "浮光咖啡角", evening))
        val eveningSky = DigitalWorldActivityCatalog.locationActivitySummary(
            "江渡", DigitalWorldPublicPlaces.COURTYARD, "watch_sky", "共生庭院", evening)
        val morningSky = DigitalWorldActivityCatalog.locationActivitySummary(
            "江渡", DigitalWorldPublicPlaces.COURTYARD, "watch_sky", "共生庭院", morning)
        assertTrue(eveningSky.orEmpty().contains("暮色"))
        assertTrue(morningSky.orEmpty().contains("晨光"))
        val window = DigitalWorldActivityCatalog.locationActivitySummary(
            "江渡", DigitalWorldPublicPlaces.CAFE, "window_cafe", "浮光咖啡角", evening)
        assertTrue(window.orEmpty().contains("暮色"))
    }

    @Test fun furnitureOptionsAppearOnlyWhenActorIsActuallyHomeWithCapacity() {
        val atHome = AutonomousAffordanceContext.render(
            digital = true, location = "resident-home:homeowner",
            books = emptyList(), groups = emptyList(),
            atOwnHome = true, homeCapacityRemaining = 3,
            homeFurniture = listOf("owned-chair-1" to "书桌旁的小椅子"),
        )
        assertTrue(atHome.contains("worldAction=build_home_item"))
        assertTrue(atHome.contains("worldAction=move_home_item"))
        assertTrue(atHome.contains("itemId=owned-chair-1"))
        assertTrue(atHome.contains("3个可用位置"))
        val full = AutonomousAffordanceContext.render(
            digital = true, location = "resident-home:full",
            books = emptyList(), groups = emptyList(),
            atOwnHome = true, homeCapacityRemaining = 0,
        )
        assertFalse(full.contains("worldAction=build_home_item"))
        val away = AutonomousAffordanceContext.render(
            digital = true, location = DigitalWorldPublicPlaces.CAFE,
            books = emptyList(), groups = emptyList(),
            homeCapacityRemaining = 3,
        )
        assertFalse(away.contains("worldAction=build_home_item"))
        assertTrue(away.contains("worldAction=go_home"))
        val nonDigital = AutonomousAffordanceContext.render(
            digital = false, location = "", books = emptyList(), groups = emptyList(),
            atOwnHome = true, homeCapacityRemaining = 3,
        )
        assertFalse(nonDigital.contains("worldAction=build_home_item"))
    }

    @Test fun nonDigitalCharactersDoNotGetInventedWorldAccess() {
        val text = AutonomousAffordanceContext.render(
            digital = false, location = "", books = emptyList(), groups = emptyList(),
        )
        assertFalse(text.contains("worldAction=visit_public_place"))
        assertFalse(text.contains("readingBookId="))
        assertTrue(text.contains("solo_game"))
    }

    @Test fun invitationOpportunityIsAsVisibleAsSoloPlayAndNeverConflatesAcceptanceWithVictory() {
        val context = AutonomousAffordanceContext.render(
            digital = false, location = "", books = emptyList(), groups = emptyList(),
        )
        assertTrue(context.contains("游戏意愿先于入口"))
        assertTrue(context.contains("方块小岛（独立持久生存存档）"))
        assertTrue(context.contains("迷雾摸金（独立探索、真实撤离）"))
        assertTrue(context.contains("不要误以为只有快艇骰子能独玩"))
        assertTrue(context.contains("可以真心喜欢某款而重玩"))
        assertTrue(context.contains("action=game_invite"))
        assertTrue(context.contains("appraisal.ownActivity={stage:decided,action:game_invite"))
        assertTrue(context.contains("无须再写相同 ownActivity"))
        assertTrue(context.contains("不会自动发消息"))
        assertTrue(context.contains("tool=send_game_invite"))
        assertTrue(context.indexOf("action=game_invite") < context.indexOf("想自己找消遣"))
        com.jiacimu.lulu.games.GameInvitationCatalog.titles.forEach { (id, title) ->
            assertTrue("Missing invitation option $id", context.contains("$id=$title"))
        }
        assertTrue(context.contains("未回复不是拒绝"))
        assertTrue(context.contains("发出邀请不等于被接受"))
        assertTrue(context.contains("真实游戏结果"))
        assertTrue(context.contains("不愿联系"))
        assertTrue(context.contains("不用为活跃而机械邀请"))
    }

    @Test fun exposesBothAutonomousGamesAndOnlySuppliedPersonalPlayHistory() {
        val withoutHistory = AutonomousAffordanceContext.render(
            digital = false, location = "", books = emptyList(), groups = emptyList(),
        )
        assertTrue(withoutHistory.contains("memory_match=记忆配对"))
        assertTrue(withoutHistory.contains("yacht_dice=快艇骰子"))
        assertTrue(withoutHistory.contains("block_island=方块小岛"))
        assertTrue(withoutHistory.contains("relic_escape=迷雾摸金"))
        assertTrue(withoutHistory.contains("tool=invite_duel_game"))
        assertTrue(withoutHistory.contains("tool=respond_duel_invite"))
        assertTrue(withoutHistory.contains("不等于支持任意人数"))
        com.jiacimu.lulu.games.AutonomousGameCatalog.gameNames.forEach { (id, name) ->
            assertTrue("Missing executable solo game $id", withoutHistory.contains("$id=$name"))
        }
        assertFalse(withoutHistory.contains("本人最近实际玩过的游戏"))
        val played = AutonomousAffordanceContext.render(
            digital = false, location = "", books = emptyList(), groups = emptyList(),
            recentSoloGames = listOf("记忆配对" to 840, "快艇骰子" to 167),
        )
        assertTrue(played.contains("记忆配对：840 分"))
        assertTrue(played.contains("快艇骰子：167 分"))
        assertTrue(played.contains("不是强制轮换任务"))
    }

    @Test fun onlyTrueIndependentGamesEnterSoloHistoryNotCharacterDuelsOrOwnerGames() {
        fun record(title: String, actor: String, solo: Boolean, withOwner: Boolean,
            summary: String) = com.jiacimu.lulu.games.LuluGameRecord(
            type = com.jiacimu.lulu.games.LuluGameType.YachtDice,
            title = title, score = 42, rewardCoins = 0,
            characterId = actor, playedWithCharacter = withOwner,
            characterSolo = solo, summary = summary)
        val records = listOf(
            record("人物对战", "role-A", false, false, "甲乙共同比赛，甲得 42 分"),
            record("与主人同玩", "role-A", false, true, "共同竞技的结果"),
            record("其他人物独玩", "role-B", true, false, "另一位角色真的独玩"),
            record("方块小岛", "role-A", true, false, "真实独立生存 18 回合"),
            record("旧版快艇骰子", "role-A", false, false, "独自完成一局快艇骰子"),
        )
        assertEquals(listOf("方块小岛", "旧版快艇骰子"),
            AutonomousAffordanceContext.recentSoloGames(records, "role-A").map { it.first })
        assertEquals(listOf("其他人物独玩"),
            AutonomousAffordanceContext.recentSoloGames(records, "role-B").map { it.first })
    }


    @Test fun realPastHourSoloBurstIsVisibleWithoutInventingASocialObligation() {
        val now = Instant.parse("2026-11-15T13:11:00Z")
        fun game(minutesAgo: Long, title: String = "方块小岛",
                 actor: String = "role-A", solo: Boolean = true,
                 withOwner: Boolean = false) = com.jiacimu.lulu.games.LuluGameRecord(
            type = com.jiacimu.lulu.games.LuluGameType.BlockIsland,
            title = title, score = 0, rewardCoins = 0,
            characterId = actor, characterSolo = solo,
            playedWithCharacter = withOwner,
            summary = if (solo) "独自玩了方块小岛" else "双人对战",
            createdAt = now.minus(minutesAgo, ChronoUnit.MINUTES),
        )
        val actual = listOf(
            game(41), game(32), game(3),
            game(70), // Earlier than the observed one-hour rhythm
            game(8, actor = "role-B"), // Another character's pastime
            game(7, solo = false, withOwner = true), // Played with owner
            game(6, solo = false), // AI-vs-AI duel, not solo
            game(-4), // Future-dated corrupt record is not a witnessed game
        )
        val rhythm = AutonomousAffordanceContext.recentSoloPlayRhythm(actual, "role-A", now)
        assertTrue(rhythm.contains("过去60分钟本人已经实际独玩3段"))
        assertTrue(rhythm.contains("《方块小岛》3段"))
        assertTrue(rhythm.contains("可主动发真实游戏邀请"))
        assertTrue(rhythm.contains("不按次数强迫邀约"))
        val prompt = AutonomousAffordanceContext.render(
            digital = false, location = "", books = emptyList(), groups = emptyList(),
            recentSoloPlayRhythm = rhythm,
        )
        assertTrue(prompt.contains("【近期独玩节奏｜真实游戏记录】"))
        assertTrue(prompt.contains("独玩3段"))
        assertFalse(AutonomousAffordanceContext.render(
            digital = false, location = "", books = emptyList(), groups = emptyList(),
        ).contains("【近期独玩节奏"))
        assertEquals("", AutonomousAffordanceContext.recentSoloPlayRhythm(
            listOf(game(5)), "role-A", now))
        assertEquals("", AutonomousAffordanceContext.recentSoloPlayRhythm(
            actual, "missing-role", now))
    }

}
