package com.jiacimu.lulu.games

import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE, sdk = [28])
class YachtMatchContinuityTest {
    @Test fun twelfthRoundAndHeldDiceSurviveSerializationWithoutDeclaringWinner() {
        val scores = mapOf(
            "user" to YachtCategory.entries.take(12).associateWith { 4 },
            "role:river" to YachtCategory.entries.take(12).associateWith { 5 },
        )
        val before = YachtMatchSnapshot(
            companionIds = listOf("river"),
            scores = scores,
            currentPlayerIndex = 0,
            dice = listOf(1, 2, 3, 3, 6),
            held = setOf(2, 3),
            rolls = 2,
            matchLog = listOf("第十二轮还没结束"),
        )
        val after = YachtMatchSnapshot.fromJson(before.toJson())!!
        assertEquals(before, after)
        assertFalse(after.completed)
        assertEquals(12, after.scores["user"]!!.size)
        assertEquals(2, after.rolls)
        assertEquals(setOf(2, 3), after.held)
        assertTrue(after.contextFor("river").contains("12/13轮"))
        assertTrue(after.contextFor("river").contains("尚未"))
        assertEquals("", after.contextFor("different-role"))
    }

    @Test fun storageKeepsSameMatchIdAndCurrentScoreWhenLeavingTheScreen() {
        val context = RuntimeEnvironment.getApplication() as android.content.Context
        context.getSharedPreferences("lulu_games", 0).edit().clear().commit()
        val firstStore = LuluGameStore(context)
        firstStore.selectCharacters(listOf("river"))
        val initial = firstStore.openYachtMatch()
        val changed = initial.copy(
            scores = mapOf("user" to YachtCategory.entries.take(12).associateWith { 3 }),
            dice = listOf(4, 4, 4, 2, 1),
            held = setOf(0, 1, 2),
            rolls = 1,
        )
        firstStore.saveYachtMatch(changed)
        // A newly constructed Store is equivalent to returning from another app page.
        val reopened = LuluGameStore(context)
        assertEquals(initial.id, reopened.openYachtMatch().id)
        assertEquals(12, reopened.openYachtMatch().scores["user"]!!.size)
        assertEquals(1, reopened.openYachtMatch().rolls)
        assertEquals(setOf(0, 1, 2), reopened.openYachtMatch().held)
        assertTrue(reopened.activeGameContext("river").contains("游戏"))
        assertEquals("", reopened.activeGameContext("not-in-party"))
    }

    @Test fun scoresRenderFromCompetitiveSheetAndHistoricalSoloTurnResults() {
        val scores = JSONObject().put("user", JSONObject().put("name", "你").put("total", 42)
            .put("categories", JSONObject().put("Ones", 3).put("Yacht", 0)))
            .put("role:river", JSONObject().put("name", "江渡").put("total", 61)
                .put("categories", JSONObject().put("Ones", 4).put("Yacht", 50)))
        val multiplayer = JSONObject().put("game", "yacht_dice")
            .put("scoreSheets", scores).toString()
        val board = parseYachtScoreSheets(multiplayer)
        assertEquals(listOf("你", "江渡"), board.map { it.name })
        assertEquals(42, board[0].total)
        assertEquals(50, board[1].categories[YachtCategory.Yacht])

        val oldSolo = JSONObject().put("game", "yacht_dice").put("total", 37)
            .put("turns", org.json.JSONArray().put(JSONObject()
                .put("category", "Chance").put("points", 21))).toString()
        val solo = parseYachtScoreSheets(oldSolo).single()
        assertEquals(37, solo.total)
        assertEquals(21, solo.categories[YachtCategory.Chance])
        assertFalse(solo.categories.containsKey(YachtCategory.Yacht))
    }

    @Test fun finalRecordIsIdempotentAndIncompleteMatchNotAutoSettled() {
        val context = RuntimeEnvironment.getApplication() as android.content.Context
        context.getSharedPreferences("lulu_games", 0).edit().clear().commit()
        val store = LuluGameStore(context)
        val match = store.openYachtMatch()
        assertFalse(match.completed)
        assertTrue(store.state.value.records.isEmpty())
        val id = store.recordExternalGame(
            type = LuluGameType.YachtDice,
            title = "快艇骰子",
            score = 77,
            reward = 0,
            summary = "第十三轮完赛",
            characterIdOverride = "",
            playedWithCharacterOverride = false,
            recordIdOverride = match.id,
        )
        store.recordExternalGame(
            type = LuluGameType.YachtDice,
            title = "快艇骰子",
            score = 77,
            reward = 0,
            summary = "第十三轮完赛",
            characterIdOverride = "",
            playedWithCharacterOverride = false,
            recordIdOverride = match.id,
        )
        assertEquals(match.id, id)
        assertEquals(1, store.state.value.records.size)
    }
    @Test fun onlyCharacterOwnedSoloPlayOffersProcessReplay() {
        fun record(shared: Boolean = false, autonomous: Boolean = false, summary: String = "结果已结算") =
            LuluGameRecord(
                type = LuluGameType.YachtDice,
                title = "快艇骰子",
                score = 99,
                rewardCoins = 0,
                characterId = "jiangdu",
                playedWithCharacter = shared,
                characterSolo = autonomous,
                summary = summary,
            )

        assertFalse(record(shared = true).canWatchCharacterSoloProcess())
        assertFalse(record(shared = true, autonomous = true).canWatchCharacterSoloProcess())
        // A human's own single-player game is not evidence of a character's actions.
        assertFalse(record().canWatchCharacterSoloProcess())
        assertTrue(record(autonomous = true).canWatchCharacterSoloProcess())
        // Old autonomous Yacht records predate the explicit provenance flag.
        assertTrue(record(summary = "独自完成一局快艇骰子：13轮").canWatchCharacterSoloProcess())
        assertFalse(record(summary = "你自己玩完了快艇骰子").canWatchCharacterSoloProcess())
    }

    @Test fun characterSoloProvenancePersistsAcrossAppReload() {
        val context = RuntimeEnvironment.getApplication() as android.content.Context
        context.getSharedPreferences("lulu_games", 0).edit().clear().commit()
        val store = LuluGameStore(context)
        val role = "role-" + java.util.UUID.randomUUID()
        val result = store.playAutonomousGame(characterId = role, gameId = "yacht_dice")
        assertNotNull(result)
        val stored = LuluGameStore(context).state.value.records.single { it.id == result!!.recordId }
        assertTrue(stored.characterSolo)
        assertFalse(stored.playedWithCharacter)
        assertTrue(stored.canWatchCharacterSoloProcess())
    }


}
