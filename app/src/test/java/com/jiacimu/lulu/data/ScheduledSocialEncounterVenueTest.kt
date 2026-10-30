package com.jiacimu.lulu.data

import android.content.Context
import android.content.SharedPreferences
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
import java.util.UUID

/** Real calendar/world/timeline authority, without paid resident reply generation. */
@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE, sdk = [28])
class ScheduledSocialEncounterVenueTest {
    private val app get() = RuntimeEnvironment.getApplication() as Context
    private val base = Instant.now()
    private lateinit var actor: String
    private lateinit var native: DigitalWorldResident
    private lateinit var residentPrefs: SharedPreferences
    private var savedResidents = emptyList<DigitalWorldResident>()
    private var savedResidentJson: String? = null
    private val plans = mutableListOf<String>()
    private val meetings = mutableListOf<String>()
    private fun residentField() = DigitalWorldExpansionStore::class.java
        .getDeclaredField("residents").apply { isAccessible = true }

    @Before fun initialize() {
        MemoryTestEnvironment.release()
        if (runCatching { androidx.work.WorkManager.getInstance(app) }.isFailure)
            androidx.work.WorkManager.initialize(app, androidx.work.Configuration.Builder().build())
        LuluRepositories.initialize(app)
        MigratedDomainStores.initialize(app)
        SharedExperienceTimeline.initialize(app)
        DigitalLifeProfileStore.initialize(app)
        // Each Robolectric case has its own Application/backing preferences.
        DigitalWorldStore::class.java.getDeclaredField("prefs").apply { isAccessible = true }
            .set(DigitalWorldStore, null)
        DigitalWorldStore.initialize(app)
        DigitalWorldExpansionStore.initialize(app)
        @Suppress("UNCHECKED_CAST")
        savedResidents = residentField().get(DigitalWorldExpansionStore) as List<DigitalWorldResident>
        residentPrefs = DigitalWorldExpansionStore::class.java.getDeclaredField("prefs")
            .apply { isAccessible = true }.get(DigitalWorldExpansionStore) as SharedPreferences
        savedResidentJson = residentPrefs.getString("residents", null)
        residentField().set(DigitalWorldExpansionStore, emptyList<DigitalWorldResident>())
        assertTrue(residentPrefs.edit().putString("residents", "[]").commit())
        native = DigitalWorldExpansionStore.registerResident(DigitalWorldResident(
            name = "赴约-${UUID.randomUUID()}", identity = "庭院来客",
            homeLocationCode = DigitalWorldPublicPlaces.CAFE, createdAt = base.minusSeconds(3600)))
        val role = MigratedDomainStores.characters.create("赴约本人-${UUID.randomUUID()}")
        actor = role.characterId
        DigitalLifeProfileStore.registerNewLife(actor, role.displayName, "数字生命", base.minusSeconds(3600))
    }

    @After fun cleanup() {
        plans.forEach { RoleDayScheduleStore.remove(app, actor, it) }
        meetings.forEach(DigitalWorldStore::deleteMeeting)
        residentField().set(DigitalWorldExpansionStore, savedResidents)
        val edit = residentPrefs.edit()
        if (savedResidentJson == null) edit.remove("residents") else edit.putString("residents", savedResidentJson)
        assertTrue(edit.commit())
        MemoryTestEnvironment.release()
    }

    private fun args() = JSONObject().put("worldAction", "interact_resident")
        .put("activityId", "chat").put("itemId", native.id)

    private fun plan(selected: JSONObject = args(), linkResident: Boolean = true): RoleDayPlan = RoleDayScheduleStore.arrange(app, actor,
        "在咖啡角聊书-${UUID.randomUUID()}", "本人选择和这位居民相处", base.plusSeconds(120), base,
        activityAction = "digital_world_action", targetResidentId = if (linkResident) native.id else "",
        locationCode = DigitalWorldPublicPlaces.CAFE, activityArgs = selected).also { plans += it.id }

    private fun moveBoth(place: String, at: Instant) {
        assertTrue(DigitalWorldStore.performAction(actor, "visit_public_place",
            JSONObject().put("locationCode", place), at).success)
        assertNotNull(DigitalWorldExpansionStore.moveResident(native.id, place, at))
    }

    private fun encounter(place: String, at: Instant): String {
        val peer = ResidentCharacterBridge.characterId(native.id)
        val turns = listOf(
            MeetingTurn(UUID.randomUUID().toString(), actor, "本人", "", "上次那本书，你读完了吗？", at),
            MeetingTurn(UUID.randomUUID().toString(), peer, native.name, "", "读完了，我想聊聊结尾。", at.plusMillis(1)))
        val session = DigitalWorldStore.commitResidentEncounter(actor, native.id, place, turns, at)
        meetings += session.id
        assertEquals(place, session.locationCode)
        assertEquals(place, session.toJson().toMeeting()!!.locationCode)
        assertEquals("", session.toJson().apply { remove("locationCode") }.toMeeting()!!.locationCode)
        val id = "meeting-${session.id}-resident-summary-viewer-$actor"
        SharedExperienceTimeline.record(id, actor, "数字世界见面·${session.location}", "数字世界",
            "双方实际交流了这本书", at, sessionId = session.id, source = "meeting",
            evidenceKind = EventEvidenceKind.ToolResult, triggerExtraction = false)
        assertTrue(SharedExperienceTimeline.eventsByIds(actor, listOf(id)).isNotEmpty())
        return id
    }

    @Test fun wrongVenueCannotStartOrCompleteButRescheduledRealVisitSurvivesLeavingBeforeRecovery() {
        val original = plan()
        val due = original.dueAt.plusSeconds(1)
        moveBoth(DigitalWorldPublicPlaces.COURTYARD, due)
        // Even a model argument claiming the cafe cannot change the actual world position.
        val claimed = args().put("locationCode", DigitalWorldPublicPlaces.CAFE)
        assertFalse(RoleDayScheduleStore.canBegin(app, actor, original.id, "digital_world_action", claimed, due))
        val elsewhere = encounter(DigitalWorldPublicPlaces.COURTYARD, due)
        assertFalse(RoleDayScheduleStore.completeFromReceipt(app, actor, original.id,
            "digital_world_action", elsewhere, "庭院中的真实交流", due, claimed))
        assertEquals("planned", RoleDayScheduleStore.plans(app, actor).single { it.id == original.id }.status)
        val revised = RoleDayScheduleStore.reschedule(app, actor, original.id,
            due.plusSeconds(600), "双方改到稍后", due, expectedDueAt = original.dueAt)
        val later = revised.dueAt.plusSeconds(1)
        moveBoth(DigitalWorldPublicPlaces.CAFE, later)
        assertFalse(RoleDayScheduleStore.canBegin(app, actor, revised.id, "digital_world_action",
            args(), later, expectedDueAt = original.dueAt))
        assertTrue(RoleDayScheduleStore.canBegin(app, actor, revised.id, "digital_world_action",
            args(), later, expectedDueAt = revised.dueAt))
        val actual = encounter(DigitalWorldPublicPlaces.CAFE, later)
        assertFalse(RoleDayScheduleStore.completeFromReceipt(app, actor, revised.id,
            "digital_world_action", elsewhere, "旧地点回执不能完成新安排", later, args()))
        moveBoth(DigitalWorldPublicPlaces.COURTYARD, later.plusSeconds(10))
        assertTrue(RoleDayScheduleStore.completeFromReceipt(app, actor, revised.id,
            "digital_world_action", actual, "实际在咖啡角交流", later.plusSeconds(20), args()))
        val restored = RoleDayScheduleStore.plans(app, actor).single { it.id == revised.id }
        assertEquals("completed", restored.status)
        assertEquals(actual, restored.resultEvidenceId)
    }

    @Test fun executorNormalizedActionIdCannotBypassVenueForAnArgsOnlyResidentTarget() {
        val formatted = args().put("worldAction", " INTERACT_RESIDENT\u200b ")
        val appointment = plan(formatted, linkResident = false)
        val due = appointment.dueAt.plusSeconds(1)
        moveBoth(DigitalWorldPublicPlaces.COURTYARD, due)
        assertFalse(RoleDayScheduleStore.canBegin(app, actor, appointment.id,
            "digital_world_action", formatted, due))
        val elsewhere = encounter(DigitalWorldPublicPlaces.COURTYARD, due)
        assertFalse(RoleDayScheduleStore.completeFromReceipt(app, actor, appointment.id,
            "digital_world_action", elsewhere, "动作格式不改变约定地点", due, formatted))
    }

    @Test fun legacyDisplayNameCannotInventAStableVenueAfterRealStoreReload() {
        val appointment = plan()
        val due = appointment.dueAt.plusSeconds(1)
        moveBoth(DigitalWorldPublicPlaces.CAFE, due)
        val actual = encounter(DigitalWorldPublicPlaces.CAFE, due)
        val prefsField = DigitalWorldStore::class.java.getDeclaredField("prefs").apply { isAccessible = true }
        val prefs = prefsField.get(DigitalWorldStore) as SharedPreferences
        val saved = JSONObject(prefs.getString("world_v1", "{}"))
        val sessions = saved.getJSONArray("meetings")
        for (i in 0 until sessions.length()) sessions.getJSONObject(i).remove("locationCode")
        assertTrue(prefs.edit().putString("world_v1", saved.toString()).commit())
        prefsField.set(DigitalWorldStore, null)
        DigitalWorldStore.initialize(app)
        val restored = DigitalWorldStore.state.value.meetings.single { it.id == meetings.single() }
        assertEquals("", restored.locationCode)
        assertEquals(DigitalWorldPublicPlaces.label(DigitalWorldPublicPlaces.CAFE).orEmpty(), restored.location)
        assertEquals(2, restored.turns.size)
        assertFalse(RoleDayScheduleStore.completeFromReceipt(app, actor, appointment.id,
            "digital_world_action", actual, "旧名称不能倒推真实地点身份", due, args()))
        assertEquals("planned", RoleDayScheduleStore.plans(app, actor).single { it.id == appointment.id }.status)
    }

    @Test fun missingWithdrawnOrAnotherPersonsSourceCannotProveTheVenue() {
        val appointment = plan()
        val due = appointment.dueAt.plusSeconds(1)
        moveBoth(DigitalWorldPublicPlaces.CAFE, due)
        assertFalse(RoleDayScheduleStore.completeFromReceipt(app, actor, appointment.id,
            "digital_world_action", "missing-source", "不能只信摘要", due, args()))
        val actual = encounter(DigitalWorldPublicPlaces.CAFE, due)
        assertFalse(RoleDayScheduleStore.completeFromReceipt(app, "another-person", appointment.id,
            "digital_world_action", actual, "不能借别人的赴约", due, args()))
        SharedExperienceTimeline.deleteEvent(actual)
        assertFalse(RoleDayScheduleStore.completeFromReceipt(app, actor, appointment.id,
            "digital_world_action", actual, "撤回后不能补结案", due.plusSeconds(1), args()))
        assertEquals("planned", RoleDayScheduleStore.plans(app, actor).single { it.id == appointment.id }.status)
    }
}
