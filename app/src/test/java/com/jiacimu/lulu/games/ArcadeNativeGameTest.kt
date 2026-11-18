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
        for (name in listOf("lulu_block_island_v2", "lulu_expedition_homestead_v1", "relic_escape_v2")) {
            assertTrue(context.getSharedPreferences(name, Context.MODE_PRIVATE).edit().clear().commit())
        }
        MigratedDomainStores.initialize(context)
    }

    @Test fun coastPersistsItsFirstPersonWorldInTheOriginalIslandSave() {
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
        val saved = checkNotNull(RelicEscapeLedger(context).read().run)
        assertEquals(session, saved.id)
        assertEquals(9, saved.hero.hp)
        assertFalse(saved.chests.first().opened)
        assertTrue(saved.hero.bag.isEmpty())
    }
}
