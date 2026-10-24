package com.jiacimu.lulu.data

import com.jiacimu.lulu.LuluRepositories
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Before
import org.junit.After
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE, sdk = [28])
class ActionFailureDiagnosticsTest {
    @Before @After fun reset() {
        MemoryTestEnvironment.release()
        listOf(DigitalWorldStore, DigitalLifeProfileStore).forEach { store ->
            store.javaClass.getDeclaredField("prefs").apply { isAccessible = true }.set(store, null)
        }
    }

    @Test fun failedWorldActionReachesTheSharedErrorLogAndAnIdempotentRetryDoesNotDuplicateIt() = runBlocking {
        val context = RuntimeEnvironment.getApplication()
        if (runCatching { androidx.work.WorkManager.getInstance(context) }.isFailure)
            androidx.work.WorkManager.initialize(context, androidx.work.Configuration.Builder().build())
        LuluRepositories.initialize(context)
        MigratedDomainStores.characters.initialize(context)
        DigitalWorldStore.initialize(context)
        DigitalLifeProfileStore.initialize(context)
        val role = MigratedDomainStores.characters.create("动作测试", "喜欢观察")
        DigitalLifeProfileStore.registerNewLife(role.characterId, role.displayName, "测试创造者")
        val args = JSONObject().put("worldAction", "unavailable_action")
        val result = JSONObject(ToolRouter.execute(context, role.characterId, "digital_world_action", args, requestId = "failed-world"))
        assertFalse(result.getBoolean("success"))
        assertTrue(result.getString("summary").contains("未知数字世界动作"))
        val errors = LuluRepositories.performance.errorRecords.first()
        assertEquals(1, errors.count { it.message.contains("failed-world") })
        assertTrue(errors.any { it.title.contains(role.displayName) && it.message.contains("未知数字世界动作") })
        ToolRouter.execute(context, role.characterId, "digital_world_action", args, requestId = "failed-world")
        assertEquals(1, LuluRepositories.performance.errorRecords.first().count { it.message.contains("failed-world") })
    }

    @Test fun chosenActivityCanTravelToItsUniqueRealLocationBeforeExecution() = runBlocking {
        val context = RuntimeEnvironment.getApplication()
        if (runCatching { androidx.work.WorkManager.getInstance(context) }.isFailure)
            androidx.work.WorkManager.initialize(context, androidx.work.Configuration.Builder().build())
        LuluRepositories.initialize(context)
        MigratedDomainStores.characters.initialize(context)
        DigitalWorldStore.initialize(context)
        DigitalLifeProfileStore.initialize(context)
        val role = MigratedDomainStores.characters.create("活动路线测试", "喜欢散步")
        DigitalLifeProfileStore.registerNewLife(role.characterId, role.displayName, "测试创造者")
        val args = JSONObject().put("worldAction", "use_location").put("activityId", "cloud_walk")
        val result = JSONObject(ToolRouter.execute(context, role.characterId,
            "digital_world_action", args, requestId = "unique-location-activity"))
        assertTrue(result.toString(), result.getBoolean("success"))
        assertEquals(DigitalWorldStore.CLOUD_MEADOW, DigitalWorldStore.locationOf(role.characterId))
        assertEquals(listOf("visit_cloud_meadow", "use_location"),
            DigitalWorldStore.state.value.events.filter { it.characterId == role.characterId }
                .takeLast(2).map { it.kind })
        val eventsBefore = DigitalWorldStore.state.value.events.size
        val invalid = JSONObject(ToolRouter.execute(context, role.characterId,
            "digital_world_action", JSONObject().put("worldAction", "use_location")
                .put("activityId", "nonexistent-activity"), requestId = "invalid-activity"))
        assertFalse(invalid.getBoolean("success"))
        assertTrue(invalid.toString().contains("nonexistent-activity"))
        assertEquals(eventsBefore, DigitalWorldStore.state.value.events.size)
        ToolRouter.execute(context, role.characterId, "digital_world_action", args,
            requestId = "unique-location-activity")
        assertEquals(eventsBefore, DigitalWorldStore.state.value.events.size)
    }

    @Test fun formattedUseLocationExecutesTheRealActivityWithoutBypassingLocationValidation() {
        val context = RuntimeEnvironment.getApplication()
        if (runCatching { androidx.work.WorkManager.getInstance(context) }.isFailure)
            androidx.work.WorkManager.initialize(context, androidx.work.Configuration.Builder().build())
        LuluRepositories.initialize(context)
        MigratedDomainStores.characters.initialize(context)
        DigitalWorldStore.initialize(context)
        DigitalLifeProfileStore.initialize(context)
        val role = MigratedDomainStores.characters.create("地点活动测试", "喜欢观察")
        DigitalLifeProfileStore.registerNewLife(role.characterId, role.displayName, "测试创造者")
        val result = DigitalWorldStore.performAction(role.characterId, "use\u200B_\nlocation",
            JSONObject().put("activityId", "home_pace"))
        assertTrue(result.summary, result.success)
        assertEquals("use_location", DigitalWorldStore.state.value.events.last().kind)
        val invalid = DigitalWorldStore.performAction(role.characterId, "use_location", JSONObject().put("activityId", "cloud_walk"))
        assertFalse(invalid.success)
        assertTrue(invalid.summary.contains("当前位置不支持"))
    }
}
