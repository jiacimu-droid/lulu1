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

    @Test fun privateChatCompactWorldListsOnlyActuallyPresentResidents() {
        val context = RuntimeEnvironment.getApplication()
        MigratedDomainStores.characters.initialize(context)
        DigitalLifeProfileStore.initialize(context)
        DigitalWorldStore.initialize(context)
        DigitalWorldExpansionStore.initialize(context)
        val role = MigratedDomainStores.characters.create("NPC在场核实测试", "先看真实世界状态")
        val id = role.characterId
        DigitalLifeProfileStore.registerNewLife(id, role.displayName, "创造者")
        DigitalWorldStore.ensureHome(id, role.displayName)
        val location = DigitalWorldStore.locationOf(id)
        val resident = DigitalWorldExpansionStore.registerResident(
            DigitalWorldResident(id = "npc-grounded-context-" + id, name = "地点校验居民" + id.take(4),
                identity = "数字世界路标维护居民", homeLocationCode = DigitalWorldPublicPlaces.COURTYARD))
        try {
            val absent = PromptContextPolicy.compactWorld(id)
            assertFalse(absent.contains("resident:${resident.id}"))
            DigitalWorldExpansionStore.moveResident(resident.id, location)
            val present = PromptContextPolicy.compactWorld(id)
            assertTrue(present.contains("resident:${resident.id}"))
            assertTrue(present.contains("当前地点程序已登记在场的居民"))
            assertTrue(present.contains("不证明刚刚进门"))
            DigitalWorldExpansionStore.moveResident(resident.id, resident.homeLocationCode)
            val departed = PromptContextPolicy.compactWorld(id)
            assertFalse(departed.contains("resident:${resident.id}"))
        } finally {
            DigitalWorldExpansionStore.moveResident(resident.id, resident.homeLocationCode)
        }
    }

    @Test fun summaryOnlyAndOneSidedNpcDialogueAreNotCompletedInteractions() {
        val resident = DigitalWorldResident(id = "npc", name = "小禾", identity = "园艺爱好者", homeLocationCode = "resident-home:npc")
        val now = Instant.now()
        assertTrue(runCatching { DigitalWorldResidentInteractionRuntime.parseTurns(
            JSONObject().put("summary", "他们聊了一会儿"), "role", resident, now) }.isFailure)
        val invalid = JSONObject("""{"turns":[{"speakerId":"role","reason":"想打招呼","text":"你好"},{"speakerId":"role","reason":"继续聊天","text":"天气不错"}]}""")
        assertTrue(runCatching { DigitalWorldResidentInteractionRuntime.parseTurns(invalid, "role", resident, now) }.isFailure)
    }

    @Test fun deletingANativeExchangeWithdrawsBothSpeakersSummaryEmotionAndContinuingEpisode() {
        val context = RuntimeEnvironment.getApplication() as android.content.Context
        SharedExperienceTimeline.initialize(context)
        MigratedDomainStores.characters.initialize(context)
        DigitalWorldStore.initialize(context)
        DigitalLifeProfileStore.initialize(context)
        DigitalWorldExpansionStore.initialize(context)
        CharacterInnerLifeStore.initialize(context)
        val role = MigratedDomainStores.characters.create("居民事件删除测试", "喜欢文字")
        val id = role.characterId
        DigitalLifeProfileStore.registerNewLife(id, role.displayName, "测试创造者")
        DigitalWorldStore.ensureHome(id, role.displayName)
        val resident = DigitalWorldExpansionStore.registerResident(DigitalWorldResident(id = "episode-delete-npc",
            name = "删除测试居民", identity = "庭院居民", homeLocationCode = DigitalWorldPublicPlaces.COURTYARD))
        val location = DigitalWorldStore.locationOf(id)
        DigitalWorldExpansionStore.moveResident(resident.id, location)
        val now = Instant.now() // The receipt must follow this digital life's actual birth.
        val step = DigitalWorldResidentEpisodeStore.prepare(id, resident.id, "share_text",
            JSONObject().put("text", "窗外的风停了一下。"), now)
        val turns = listOf(
            MeetingTurn("native-delete-mine", id, role.displayName, "", step.contribution, now, exchangeId = "native-delete-exchange"),
            MeetingTurn("native-delete-theirs", "resident:${resident.id}", resident.name, "", "十分钟后再聊这句。", now,
                exchangeId = "native-delete-exchange"))
        val session = DigitalWorldStore.commitResidentEncounter(id, resident.id, location, turns, now)
        val sources = turns.map { turn ->
            val evidence = "meeting-${session.id}-resident-turn-${turn.id}-viewer-$id"
            SharedExperienceTimeline.record(evidence, id, "数字世界见面", turn.speakerName, turn.dialogue, now,
                triggerExtraction = false, source = "meeting", evidenceKind = EventEvidenceKind.Observation)
            evidence
        }
        val summary = "meeting-${session.id}-resident-summary-viewer-$id"
        SharedExperienceTimeline.record(summary, id, "数字世界见面", "居民", "双方实际约好稍后再谈", now,
            triggerExtraction = false, source = "meeting", evidenceKind = EventEvidenceKind.ToolResult)
        val wrapper = "tool-native-exchange-delete-$id"
        val receipt = JSONObject().put("tool", "digital_world_action").put("status", "succeeded")
            .put("fingerprint", "digital_world_action:" + JSONObject().put("worldAction", "interact_resident"))
            .put("result", JSONObject().put("success", true).put("summary", "已和居民交流")
                .put("evidenceId", summary).toString())
        SharedExperienceTimeline.record(wrapper, id, "实际工具执行", "执行器", receipt.toString(), now,
            triggerExtraction = false, source = "tool-router", evidenceKind = EventEvidenceKind.ToolResult)
        DigitalWorldResidentEpisodeStore.commit(step, JSONObject().put("choice", "waiting")
            .put("residentText", turns.last().dialogue).put("resultText", "十分钟后再聊"), summary, now)
        CharacterInnerLifeStore.observe(id, sources.last(), turns.last().dialogue,
            JSONObject().put("emotion", JSONObject().put("feeling", "有点期待").put("cause", "约好再谈")), now = now)
        assertTrue(DigitalWorldResidentEpisodeStore.contextFor(id, now).isNotBlank())
        assertEquals(2, DigitalWorldStore.deleteMeetingExchange(session.id, turns.last().id))
        assertTrue(DigitalWorldStore.state.value.meetings.first { it.id == session.id }.turns.isEmpty())
        assertTrue(SharedExperienceTimeline.eventsByIds(id, sources + listOf(summary, wrapper)).isEmpty())
        assertEquals("", DigitalWorldResidentEpisodeStore.contextFor(id, now))
        assertNull(CharacterInnerLifeStore.snapshot(id).optJSONObject("emotion"))
        assertFalse(DigitalWorldResidentEpisodeStore.isReady(id, step.id, now.plusSeconds(700)))
    }

    @Test fun nativeConversationIsSavedAsEndedMeetingWithoutUserOrLocationChangesAndSurvivesReload() {
        val context = RuntimeEnvironment.getApplication()
        if (runCatching { androidx.work.WorkManager.getInstance(context) }.isFailure) {
            androidx.work.WorkManager.initialize(context, androidx.work.Configuration.Builder().build())
        }
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
