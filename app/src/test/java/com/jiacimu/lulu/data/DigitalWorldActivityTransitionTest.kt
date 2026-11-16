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

    @Test fun sceneOccursBeforeTheCharacterElectsToNoticeIt() {
        val at = now.plusSeconds(100)
        assertTrue(DigitalWorldStore.performAction(role, "visit_public_place",
            JSONObject().put("locationCode", DigitalWorldPublicPlaces.CAFE), at).success)
        val sceneId = "cafe-scene-${java.util.UUID.randomUUID()}"
        val sceneDescription = "咖啡角窗边原有的柔光忽然暗了一瞬，过一会儿又亮回来"
        val saved = org.json.JSONArray().put(JSONObject()
            .put("id", sceneId).put("kind", "seat_light")
            .put("locationCode", DigitalWorldPublicPlaces.CAFE)
            .put("anchorItemId", "").put("anchorItemName", "")
            .put("status", "resolved").put("stage", 0)
            .put("summary", sceneDescription)
            .put("lastActorCharacterId", "")
            .put("createdAt", at.toString()).put("updatedAt", at.toString()))
        val prefs = context.getSharedPreferences("lulu_digital_world_life_events", Context.MODE_PRIVATE)
        assertTrue(prefs.edit().putString("incidents_v1", saved.toString()).commit())
        DigitalWorldLifeEventStore.javaClass.getDeclaredField("prefs")
            .apply { isAccessible = true }.set(DigitalWorldLifeEventStore, null)
        DigitalWorldLifeEventStore.initialize(context)

        val options = DigitalWorldLifeEventStore.contextFor(role, at.plusSeconds(1))
        assertTrue(options.contains(sceneId))
        assertTrue(options.contains("尚非本人亲眼留意"))
        assertFalse(SharedExperienceTimeline.all(role).any { it.content.contains(sceneDescription) })
        val chosen = DigitalWorldStore.performAction(role, "observe_scene",
            JSONObject().put("incidentId", sceneId), at.plusSeconds(2))
        assertTrue(chosen.summary, chosen.success)
        assertEquals("world-observe-$role-$sceneId", chosen.evidenceId)
        assertTrue(chosen.summary.contains(sceneDescription))
        val receipt = SharedExperienceTimeline.eventsByIds(role, listOf(chosen.evidenceId)).single()
        assertEquals(EventEvidenceKind.Observation, receipt.evidenceKind)
        assertEquals("digital-world", receipt.source)
        assertTrue(CharacterEverydaySocialContext.render(role, at.plusSeconds(4), emptyList())
            .contains(sceneDescription))
        assertFalse(DigitalWorldStore.performAction(role, "observe_scene",
            JSONObject().put("incidentId", sceneId), at.plusSeconds(5)).success)
        assertFalse(DigitalWorldLifeEventStore.contextFor(role, at.plusSeconds(6)).contains(sceneId))
        assertFalse(CharacterLifeFollowThroughStore.auditSnapshot(role)
            .any { it.optString("id") == chosen.evidenceId })
    }

    @Test fun offsiteAndStaleSceneCannotBecomeSomeoneElsesFirsthandStory() {
        val at = now.plusSeconds(150)
        val sceneId = "courtyard-scene-${java.util.UUID.randomUUID()}"
        val saved = org.json.JSONArray().put(JSONObject()
            .put("id", sceneId).put("kind", "season_pixels")
            .put("locationCode", DigitalWorldPublicPlaces.COURTYARD)
            .put("anchorItemId", "").put("anchorItemName", "")
            .put("status", "resolved").put("stage", 0)
            .put("summary", "庭院上方飘下几片浅金色叶子")
            .put("createdAt", at.toString()).put("updatedAt", at.toString()))
        val prefs = context.getSharedPreferences("lulu_digital_world_life_events", Context.MODE_PRIVATE)
        assertTrue(prefs.edit().putString("incidents_v1", saved.toString()).commit())
        DigitalWorldLifeEventStore.javaClass.getDeclaredField("prefs")
            .apply { isAccessible = true }.set(DigitalWorldLifeEventStore, null)
        DigitalWorldLifeEventStore.initialize(context)
        assertFalse(DigitalWorldStore.performAction(role, "observe_scene",
            JSONObject().put("incidentId", sceneId), at.plusSeconds(1)).success)
        assertTrue(DigitalWorldStore.performAction(role, "visit_public_place",
            JSONObject().put("locationCode", DigitalWorldPublicPlaces.COURTYARD), at.plusSeconds(2)).success)
        assertFalse(DigitalWorldStore.performAction(role, "observe_scene",
            JSONObject().put("incidentId", sceneId), at.plusSeconds(601)).success)
        assertFalse(SharedExperienceTimeline.all(role).any {
            it.content.contains("庭院上方飘下几片浅金色叶子")
        })
    }

    @Test fun ordinaryWorldAmbienceRemainsLivedEvidenceWithoutCreatingShareChores() {
        CharacterLifeFollowThroughStore.initialize(context)
        val at = now.plusSeconds(300)
        fun worldTick(kind: String, status: String = "resolved"): DigitalWorldLifeTick =
            DigitalWorldLifeTick(
                incidentId = "living-${java.util.UUID.randomUUID()}",
                locationCode = DigitalWorldPublicPlaces.CAFE,
                locationName = "浮光咖啡角",
                kind = kind,
                anchorItemId = "",
                anchorItemName = "",
                status = status,
                stage = 0,
                summary = "角色真正见过的一次${kind}小场景",
                occurredAt = at,
            )

        val ordinary = worldTick("warm_mist")
        assertFalse(DigitalWorldLifeEventStore.warrantsLifeFollowThrough(ordinary))
        val ordinaryId = DigitalWorldLifeEventStore.recordObservedTick(role, ordinary)
        assertTrue(SharedExperienceTimeline.eventsByIds(role, listOf(ordinaryId)).any {
            it.evidenceKind == EventEvidenceKind.Observation &&
                it.source == "digital-world" && it.content == ordinary.summary
        })
        assertFalse(CharacterLifeFollowThroughStore.auditSnapshot(role).any {
            it.optString("id") == ordinaryId
        })
        assertTrue(CharacterEverydaySocialContext.render(role, at.plusSeconds(1), emptyList())
            .contains(ordinary.summary))

        // A real bodily surprise remains a possible personal follow-up, never
        // a pre-authorized post. An unresolved incident is also significant.
        val sneeze = worldTick("digital_sneeze")
        assertTrue(DigitalWorldLifeEventStore.warrantsLifeFollowThrough(sneeze))
        val sneezeId = DigitalWorldLifeEventStore.recordObservedTick(role, sneeze, at.plusSeconds(1))
        assertTrue(CharacterLifeFollowThroughStore.auditSnapshot(role).any {
            it.optString("id") == sneezeId
        })
        val incident = worldTick("surface_ripple", status = "active")
        assertTrue(DigitalWorldLifeEventStore.warrantsLifeFollowThrough(incident))
        val incidentId = DigitalWorldLifeEventStore.recordObservedTick(role, incident, at.plusSeconds(2))
        assertTrue(CharacterLifeFollowThroughStore.auditSnapshot(role).any {
            it.optString("id") == incidentId && it.optBoolean("significant")
        })
    }

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
