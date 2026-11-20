package com.jiacimu.lulu.games

import android.content.Context
import com.jiacimu.lulu.data.MigratedDomainStores
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE, sdk = [28])
class ArcadeNativeGameTest {
    private val context: Context get() = RuntimeEnvironment.getApplication()

    @Before fun resetGameSaves() {
        for (name in listOf(arcadeIslandSave, arcadeRelicSave, "lulu_block_island_v2", "relic_escape_v2")) {
            assertTrue(context.getSharedPreferences(name, Context.MODE_PRIVATE).edit().clear().commit())
        }
        MigratedDomainStores.initialize(context)
    }

    @Test fun coastPersistsItsFirstPersonWorld() {
        val store = LuluGameStore(context)
        val game = ArcadeNativeGame(context, store, "island", emptyList())
        val started = game.request(JSONObject().put("verb", "start"))
        val seed = started.getString("session")
        val stepped = game.request(JSONObject().put("verb", "act").put("session", seed)
            .put("action", "move").put("dx", 1).put("dy", 0))
        assertEquals(11, stepped.getJSONObject("world").getInt("x"))
        val reopened = ArcadeNativeGame(context, store, "island", emptyList())
        val persisted = checkNotNull(reopened.island())
        assertEquals(35, persisted.world.width)
        assertEquals(11, persisted.world.x)

        val resumed = reopened.request(JSONObject().put("verb", "start"))
        assertEquals(seed, resumed.getString("session"))
        assertEquals(11, resumed.getJSONObject("world").getInt("x"))
    }

    @Test fun ambushPersistsTheActualDamageWithoutGivingChestLoot() {
        val game = ArcadeNativeGame(context, LuluGameStore(context), "relic", emptyList())
        val started = game.request(JSONObject().put("verb", "start")
            .put("difficulty", "EASY").put("map", "ambush"))
        val session = started.getString("session")
        val opening = JSONObject().put("verb", "act").put("session", session)
            .put("action", "cinematic_step").put("x", 4).put("y", 4)
        repeat(3) { assertFalse(game.request(opening).optBoolean("chestInterrupted")) }
        val interrupted = game.request(opening)
        assertTrue(interrupted.getBoolean("chestInterrupted"))
        assertFalse(interrupted.has("receipt"))
        val hit = interrupted.getJSONArray("impacts").getJSONObject(0)
        assertEquals("player", hit.getString("target"))
        assertEquals("enemy", hit.getString("style"))
        assertEquals(1, hit.getInt("damage"))
        assertEquals(3, hit.getInt("x"))
        assertEquals(4, hit.getInt("y"))
        val reopened = game.request(JSONObject().put("verb", "view").put("session", session))
        assertFalse(reopened.has("impacts"))
        val saved = checkNotNull(RelicEscapeLedger(context, arcadeRelicSave).read().run)
        assertEquals(session, saved.id)
        assertEquals(9, saved.hero.hp)
        assertFalse(saved.chests.first().opened)
        assertTrue(saved.hero.bag.isEmpty())
    }

    @Test fun exactPlayerHitIsReturnedOnceAndItsDamageIsPersisted() {
        val base = ArcadeMaps.cloister(RelicEscapeWorld.create(42, 1, emptyList(), 0, 1), false)
        val placed = base.copy(mobs = listOf(RelicMob(17, 3, 5, 2, 0)))
        assertTrue(context.getSharedPreferences(arcadeRelicSave, Context.MODE_PRIVATE).edit()
            .putString("ledger", RelicSnapshot(run = placed).json().toString()).commit())
        val game = ArcadeNativeGame(context, LuluGameStore(context), "relic", emptyList())
        fun act() = game.request(JSONObject().put("verb", "act").put("session", placed.id)
            .put("action", "attack").put("x", 3).put("y", 5))

        val hit = act().getJSONArray("impacts").getJSONObject(0)
        assertEquals("mob:17", hit.getString("target"))
        assertEquals("blade", hit.getString("style"))
        assertEquals(2, hit.getInt("damage"))
        assertTrue(hit.getBoolean("defeated"))
        assertEquals(4, hit.getInt("sourceY"))
        assertEquals(5, hit.getInt("y"))
        val persisted = checkNotNull(game.relic())
        assertEquals(0, persisted.mobs.single().hp)
        assertTrue(persisted.impacts.isEmpty())
        assertFalse(act().has("impacts"))
    }

    @Test fun gameEditionStartsFreshOnAnExistingInstallation() {
        val store = LuluGameStore(context)
        val regularIsland = BlockIslandRules.newWorld(18, emptyList())
        val regularPrefs = context.getSharedPreferences("lulu_block_island_v2", Context.MODE_PRIVATE)
        assertTrue(regularPrefs.edit().putString("world", regularIsland.toJson()).commit())
        val regularRelic = RelicEscapeLedger(context).begin(emptyList(), 0)
        val lobby = JSONObject().put("verb", "lobby")
        val start = JSONObject().put("verb", "start")
        val island = ArcadeNativeGame(context, store, "island", emptyList())
        val relic = ArcadeNativeGame(context, store, "relic", emptyList())

        assertTrue(island.request(lobby).isNull("session"))
        assertTrue(relic.request(lobby).isNull("session"))
        assertEquals(35, island.request(start).getJSONObject("world").getInt("width"))
        assertNotEquals(regularRelic.run?.id, relic.request(start).getString("session"))
        assertEquals(regularIsland.toJson(), regularPrefs.getString("world", null))
        assertEquals(regularRelic, RelicEscapeLedger(context).read())
    }

    @Test fun aRelicWithdrawalWaitsForThreeClockTicksEvenWhenThePlayerActs() {
        val base = RelicEscapeWorld.create(42, 1, emptyList(), 1, 1, difficulty = RelicDifficulty.EASY)
        val map = ArcadeMaps.cloister(base, false)
        val atGate = map.copy(hero = map.hero.copy(x = map.exitX, y = map.exitY), mobs = emptyList())
        assertTrue(context.getSharedPreferences(arcadeRelicSave, Context.MODE_PRIVATE).edit()
            .putString("ledger", RelicSnapshot(run = atGate).json().toString()).commit())
        val game = ArcadeNativeGame(context, LuluGameStore(context), "relic", emptyList())
        fun act(action: String) = game.request(JSONObject().put("verb", "act").put("action", action)
            .put("session", atGate.id).put("timedExtraction", true))

        assertEquals("EXTRACT", act("extract").getString("phase"))
        assertEquals(0, act("defend").getInt("extraction"))
        assertEquals(1, act("extract_tick").getInt("extraction"))
        assertEquals(2, act("extract_tick").getInt("extraction"))
        assertEquals("SUCCESS", act("extract_tick").getString("phase"))
        assertEquals(RelicPhase.SUCCESS, RelicEscapeLedger(context, arcadeRelicSave).read().run?.phase)
    }

    @Test fun swimmingIntoTheBayAndClimbingBackOntoLandArePersistedMoves() {
        val game = ArcadeNativeGame(context, LuluGameStore(context), "island", emptyList())
        val started = game.request(JSONObject().put("verb", "start"))
        val session = started.getString("session")
        val saved = checkNotNull(game.island())
        game.saveIsland(saved.copy(world = saved.world.copy(x = 8, y = 15)))
        fun step(dx: Int) = game.request(JSONObject().put("verb", "act").put("session", session)
            .put("action", "move").put("dx", dx).put("dy", 0))
        val water = step(1)
        assertTrue(water.getBoolean("swimming"))
        assertEquals(9, game.island()?.world?.x)
        val land = step(1)
        assertFalse(land.getBoolean("swimming"))
        assertEquals(10, game.island()?.world?.x)
    }
}
