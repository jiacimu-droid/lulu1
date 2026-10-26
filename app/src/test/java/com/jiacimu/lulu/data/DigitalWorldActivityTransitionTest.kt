package com.jiacimu.lulu.data

import android.content.Context
import com.jiacimu.lulu.LuluRepositories
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import java.time.Instant

@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE, sdk = [28])
class DigitalWorldActivityTransitionTest {
    private lateinit var context: Context
    private lateinit var role: String
    private lateinit var table: DigitalWorldItem
    private val now = Instant.parse("2026-10-26T12:00:00Z")

    @Before fun start() {
        MemoryTestEnvironment.release()
        detach()
        context = RuntimeEnvironment.getApplication()
        if (runCatching { androidx.work.WorkManager.getInstance(context) }.isFailure)
            androidx.work.WorkManager.initialize(context, androidx.work.Configuration.Builder().build())
        LuluRepositories.initialize(context)
        SharedExperienceTimeline.initialize(context)
        MigratedDomainStores.characters.initialize(context)
        DigitalLifeProfileStore.initialize(context)
        CharacterLifeStore.initialize(context)
        DigitalWorldStore.initialize(context)
        val character = MigratedDomainStores.characters.create("活动交接测试", "按真实结果生活")
        role = character.characterId
        DigitalLifeProfileStore.registerNewLife(role, character.displayName, "测试", now.minusSeconds(60))
        DigitalWorldStore.ensureHome(role, character.displayName, now)
        assertTrue(DigitalWorldStore.performAction(role, "go_home", JSONObject(), now).success)
        assertTrue(DigitalWorldStore.performAction(role, "build_home_item", JSONObject()
            .put("name", "木桌").put("itemType", "table").put("appearance", "一张木制餐桌"), now).success)
        table = DigitalWorldStore.itemsAtHome(role).first { it.name == "木桌" }
        DigitalWorldActivityStateStore.apply(role, null, "rest", "正在休息", now)
        assertEquals("active", JSONObject(activity()).getString("status"))
        assertEquals("rest", CharacterLifeStore.bodyState(role)?.optString("recoveryMode"))
    }

    @After fun finish() { MemoryTestEnvironment.release(); detach() }

    private fun detach() {
        listOf(DigitalWorldStore, DigitalWorldActivityStateStore, DigitalLifeProfileStore,
            DigitalWorldExpansionStore, DigitalWorldLifeEventStore, CharacterLifeStore).forEach {
            it.javaClass.getDeclaredField("prefs").apply { isAccessible = true }.set(it, null)
        }
    }

    private fun activity(): String = context.getSharedPreferences("digital_activity_state_v1", Context.MODE_PRIVATE)
        .getString("activity:$role", "{}").orEmpty()

    private fun act(id: String, at: Instant) = DigitalWorldStore.performAction(role, "use_home_item",
        JSONObject().put("itemId", table.id).put("activityId", id), at)

    @Test fun rejectedActionDoesNotEndRestOrCreateAWorldOutcome() {
        val before = activity()
        val body = CharacterLifeStore.bodyState(role).toString()
        val events = SharedExperienceTimeline.all(role).map { it.id }
        val worldEvents = DigitalWorldStore.state.value.events
        val result = act("have_snack", now.plusSeconds(60))
        assertFalse(result.success)
        assertTrue(result.summary.contains("随身没有"))
        assertEquals(before, activity())
        assertEquals(body, CharacterLifeStore.bodyState(role).toString())
        assertEquals(events, SharedExperienceTimeline.all(role).map { it.id })
        assertEquals(worldEvents, DigitalWorldStore.state.value.events)
    }

    @Test fun successfulTransitionReplacesActivityAndSettlesTheBodyAtItsRealTime() {
        val at = now.plusSeconds(60)
        assertTrue(act("organize_surface", at).success)
        val saved = JSONObject(activity())
        assertEquals("organize_surface", saved.getString("activityId"))
        assertEquals("completed", saved.getString("status"))
        val body = CharacterLifeStore.bodyState(role)!!
        assertEquals("", body.optString("recoveryMode"))
        assertEquals(at.toString(), body.getString("lastAt"))
        DigitalWorldActivityStateStore.javaClass.getDeclaredField("prefs").apply { isAccessible = true }
            .set(DigitalWorldActivityStateStore, null)
        DigitalWorldActivityStateStore.initialize(context)
        assertEquals(saved.toString(), activity())
    }

    @Test fun failedNewActionStillAllowsTheOldActivityToFinishAtItsOwnDueTime() {
        assertFalse(act("have_snack", now.plusSeconds(1_900)).success)
        val saved = JSONObject(activity())
        assertEquals("rest", saved.getString("activityId"))
        assertEquals("completed", saved.getString("status"))
        assertEquals(now.plusSeconds(1_800).toString(), saved.getString("endedAt"))
    }
}
