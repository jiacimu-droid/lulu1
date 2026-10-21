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

    @After fun cleanup() {
        context.getSharedPreferences(prefsName, Context.MODE_PRIVATE).edit().clear().commit()
        DigitalWorldLifeEventStore.javaClass.getDeclaredField("prefs")
            .apply { isAccessible = true }.set(DigitalWorldLifeEventStore, null)
    }

    @Test fun longLivedPestIsTheSameOldEventNotANewSightingOnEveryDecision() {
        MigratedDomainStores.characters.initialize(context)
        DigitalLifeProfileStore.initialize(context)
        DigitalWorldStore.initialize(context)
        val role = MigratedDomainStores.characters.create("事件感知去重测试", "有自己的生活")
        DigitalLifeProfileStore.registerNewLife(role.characterId, role.displayName, "创造者")
        DigitalWorldStore.ensureHome(role.characterId, role.displayName)
        val location = DigitalWorldStore.locationOf(role.characterId)
        val id = "same-cockroach-not-new-every-hour"
        val row = JSONObject().put("id", id).put("kind", "roach")
            .put("locationCode", location).put("status", "active")
            .put("stage", 1).put("anchorItemId", "").put("anchorItemName", "")
            .put("summary", "此前确实发现了一只蟑螂，尚未驱离")
            .put("lastActorCharacterId", role.characterId)
            .put("createdAt", Instant.parse("2026-10-19T10:00:00Z").toString())
            .put("updatedAt", Instant.parse("2026-10-20T12:00:00Z").toString())
        context.getSharedPreferences(prefsName, Context.MODE_PRIVATE)
            .edit().putString("incidents_v1", JSONArray().put(row).toString()).commit()
        DigitalWorldLifeEventStore.javaClass.getDeclaredField("prefs")
            .apply { isAccessible = true }.set(DigitalWorldLifeEventStore, null)
        DigitalWorldLifeEventStore.initialize(context)
        val first = DigitalWorldLifeEventStore.contextFor(role.characterId)
        val second = DigitalWorldLifeEventStore.contextFor(role.characterId)
        assertEquals(first, second)
        assertTrue(first.contains(id))
        assertTrue(first.contains("不是本轮重新发生"))
        assertTrue(first.contains("首次观察="))
        assertTrue(first.contains("旧事件"))
        assertFalse(first.contains("本轮新发现"))
    }
}
