package com.jiacimu.lulu.data

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import java.time.Instant

@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE, sdk = [28])
class DigitalWorldPersistentIncidentContextTest {
    private val context = RuntimeEnvironment.getApplication()
    private val prefsName = "lulu_digital_world_life_events"

    private fun resetInstance() {
        DigitalWorldLifeEventStore.javaClass.getDeclaredField("prefs")
            .apply { isAccessible = true }.set(DigitalWorldLifeEventStore, null)
    }

    @After fun cleanup() {
        context.getSharedPreferences(prefsName, Context.MODE_PRIVATE).edit().clear().commit()
        resetInstance()
    }

    private fun incident(kind: String, id: String, location: String, actor: String): JSONObject =
        JSONObject().put("id", id).put("kind", kind)
            .put("locationCode", location).put("status", "active")
            .put("stage", 1).put("anchorItemId", "").put("anchorItemName", "")
            .put("summary", "真实旧事件：$id")
            .put("lastActorCharacterId", actor)
            .put("createdAt", Instant.parse("2026-10-19T10:00:00Z").toString())
            .put("updatedAt", Instant.parse("2026-10-20T12:00:00Z").toString())

    @Test fun retiredPestAndDustEventsArePurgedWithoutDeletingOtherPersistentEvents() {
        MigratedDomainStores.characters.initialize(context)
        DigitalLifeProfileStore.initialize(context)
        DigitalWorldStore.initialize(context)
        val role = MigratedDomainStores.characters.create("事件迁移测试", "有自己的生活")
        DigitalLifeProfileStore.registerNewLife(role.characterId, role.displayName, "创造者")
        DigitalWorldStore.ensureHome(role.characterId, role.displayName)
        val location = DigitalWorldStore.locationOf(role.characterId)
        val keptId = "legitimate-existing-environment-event"
        val serialized = JSONArray()
            .put(incident("roach", "retired-cockroach", location, role.characterId))
            .put(incident("dust_layer", "retired-dust", location, role.characterId))
            .put(incident("surface_ripple", keptId, location, role.characterId))
        val prefs = context.getSharedPreferences(prefsName, Context.MODE_PRIVATE)
        assertTrue(prefs.edit().putString("incidents_v1", serialized.toString()).commit())
        resetInstance()
        DigitalWorldLifeEventStore.initialize(context)

        val first = DigitalWorldLifeEventStore.contextFor(role.characterId)
        val second = DigitalWorldLifeEventStore.contextFor(role.characterId)
        assertEquals(first, second)
        assertTrue(first.contains(keptId))
        assertTrue(first.contains("不是本轮重新发生"))
        assertTrue(first.contains("首次观察="))
        assertTrue(first.contains("旧事件"))
        assertFalse(first.contains("本轮新发现"))
        assertFalse(first.contains("retired-cockroach"))
        assertFalse(first.contains("retired-dust"))

        // Purging is durable, not merely a presentation filter. An app
        // restart must never resurrect removed nuisance events.
        val saved = JSONArray(prefs.getString("incidents_v1", "[]"))
        assertEquals(1, saved.length())
        assertEquals(keptId, saved.getJSONObject(0).getString("id"))
        resetInstance()
        DigitalWorldLifeEventStore.initialize(context)
        assertEquals(first, DigitalWorldLifeEventStore.contextFor(role.characterId))
    }
}
