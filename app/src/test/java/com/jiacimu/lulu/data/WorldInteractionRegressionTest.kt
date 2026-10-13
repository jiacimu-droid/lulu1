package com.jiacimu.lulu.data

import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Before
import org.junit.After
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import java.time.Instant

@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE, sdk = [28])
class WorldInteractionRegressionTest {
    @Before @After fun detachPreferences() {
        listOf(DigitalWorldStore, DigitalLifeProfileStore, DigitalWorldExpansionStore).forEach { store ->
            store.javaClass.getDeclaredField("prefs").apply { isAccessible = true }.set(store, null)
        }
    }

    @Test fun discoveredPlaceHasOneMapEntryAndOneLazyRowKey() {
        DigitalWorldExpansionStore.initialize(RuntimeEnvironment.getApplication())
        val place = DigitalWorldDiscoveredPlace(code = "shared:discovered:test-map", label = "测试平台",
            subtitle = "", purpose = "观察云海", connectedTo = listOf(DigitalWorldPublicPlaces.COURTYARD),
            createdByCharacterId = "role")
        DigitalWorldExpansionStore.registerPlace(place)
        val entries = DigitalWorldPublicPlaces.explorationPlaces()
        assertEquals(1, entries.count { it.code == place.code })
        val keys = entries.chunked(2).map { it.joinToString("|") { place -> place.code } }
        assertEquals(keys.size, keys.toSet().size)
    }

    @Test fun summaryOnlyAndOneSidedNpcDialogueAreNotCompletedInteractions() {
        val resident = DigitalWorldResident(id = "npc", name = "小禾", identity = "园艺爱好者", homeLocationCode = "resident-home:npc")
        val now = Instant.now()
        assertTrue(runCatching { DigitalWorldResidentInteractionRuntime.parseTurns(
            JSONObject().put("summary", "他们聊了一会儿"), "role", resident, now) }.isFailure)
        val invalid = JSONObject("""{"turns":[{"speakerId":"role","reason":"想打招呼","text":"你好"},{"speakerId":"role","reason":"继续聊天","text":"天气不错"}]}""")
        assertTrue(runCatching { DigitalWorldResidentInteractionRuntime.parseTurns(invalid, "role", resident, now) }.isFailure)
    }

    @Test fun nativeConversationIsSavedAsEndedMeetingWithoutUserOrLocationChangesAndSurvivesReload() {
        val context = RuntimeEnvironment.getApplication()
        MigratedDomainStores.characters.initialize(context)
        DigitalWorldStore.initialize(context)
        DigitalLifeProfileStore.initialize(context)
        DigitalWorldExpansionStore.initialize(context)
        val role = MigratedDomainStores.characters.create("世界测试角色", "喜欢闲聊")
        DigitalLifeProfileStore.registerNewLife(role.characterId, role.displayName, "测试创造者")
        DigitalWorldStore.ensureHome(role.characterId, role.displayName)
        val resident = DigitalWorldExpansionStore.registerResident(DigitalWorldResident(id = "native-record-test",
            name = "青禾", identity = "庭院居民", homeLocationCode = DigitalWorldPublicPlaces.COURTYARD))
        val location = DigitalWorldStore.locationOf(role.characterId)
        DigitalWorldExpansionStore.moveResident(resident.id, location)
        val json = JSONObject().put("turns", org.json.JSONArray().put(JSONObject()
            .put("speakerId", role.characterId).put("reason", "想认识眼前的人").put("text", "你好，你也喜欢这里吗？"))
            .put(JSONObject().put("speakerId", "resident:${resident.id}").put("reason", "有人问我，想聊聊")
                .put("text", "喜欢，待在云边会让我放松。")))
        val turns = DigitalWorldResidentInteractionRuntime.parseTurns(json, role.characterId, resident, Instant.now())
        val record = DigitalWorldStore.commitResidentEncounter(role.characterId, resident.id, location, turns)
        assertEquals(listOf(role.characterId), record.participantIds)
        assertNotNull(record.endedAt)
        assertEquals(2, record.turns.size)
        assertTrue(record.turns.none { it.speakerId == null })
        assertEquals(location, DigitalWorldStore.locationOf(role.characterId))
        DigitalWorldStore.javaClass.getDeclaredField("prefs").apply { isAccessible = true }.set(DigitalWorldStore, null)
        DigitalWorldStore.initialize(context)
        assertEquals(record, DigitalWorldStore.state.value.meetings.first { it.id == record.id })
        DigitalWorldExpansionStore.moveResident(resident.id, resident.homeLocationCode)
        val count = DigitalWorldStore.state.value.meetings.size
        assertTrue(runCatching { DigitalWorldStore.commitResidentEncounter(role.characterId, resident.id, location, turns) }.isFailure)
        assertEquals(count, DigitalWorldStore.state.value.meetings.size)
    }
}
