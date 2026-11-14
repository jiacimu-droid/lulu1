package com.jiacimu.lulu.data

import com.jiacimu.lulu.mapCompanionCharacters
import com.jiacimu.lulu.worldCompanionsAt
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import java.time.Instant
import java.time.ZoneOffset
import java.util.UUID

@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE, sdk = [28])
class NativeResidentLifeIntegrationTest {
    private var previousAutomaticBirths: Boolean? = null
    private var previousResidentCap: Int? = null

    @org.junit.After fun restoreResidentCreationPolicy() {
        val ctx = RuntimeEnvironment.getApplication() as android.content.Context
        val p = ctx.getSharedPreferences("lulu_resident_activity_policy_v1",
            android.content.Context.MODE_PRIVATE)
        val edit = p.edit()
        if (previousAutomaticBirths == null) edit.remove("resident_auto_generation")
        else edit.putBoolean("resident_auto_generation", previousAutomaticBirths!!)
        if (previousResidentCap == null) edit.remove("resident_population_cap")
        else edit.putInt("resident_population_cap", previousResidentCap!!)
        assertTrue(edit.commit())
    }

    @org.junit.Before fun initializeWorkerHost() {
        val app = RuntimeEnvironment.getApplication() as android.content.Context
        if (runCatching { androidx.work.WorkManager.getInstance(app) }.isFailure)
            androidx.work.WorkManager.initialize(app, androidx.work.Configuration.Builder().build())
        // Creating test citizens is an explicit fixture choice; production defaults
        // remain off until the owner enables automatic births.
        val p = app.getSharedPreferences("lulu_resident_activity_policy_v1",
            android.content.Context.MODE_PRIVATE)
        previousAutomaticBirths = if (p.contains("resident_auto_generation"))
            p.getBoolean("resident_auto_generation", false) else null
        previousResidentCap = if (p.contains("resident_population_cap"))
            p.getInt("resident_population_cap", ResidentDailyPolicy.MAX_RESIDENTS) else null
        assertTrue(ResidentDailyPolicy.setAutoGenerationEnabled(app, true))
        assertTrue(ResidentDailyPolicy.setMaxResidents(app, ResidentDailyPolicy.MAX_RESIDENTS))
    }

    private fun person(template: String, identity: String = "世界巡逻员") =
        DigitalWorldResident(id = UUID.randomUUID().toString(), name = template,
            identity = identity, homeLocationCode = "resident-home:testing",
            templateName = template)

    @Test fun ownerPopulationControlsRejectAllResidentRegistrationSources() {
        val context = RuntimeEnvironment.getApplication() as android.content.Context
        DigitalWorldExpansionStore.initialize(context)
        val prefs = context.getSharedPreferences("lulu_resident_activity_policy_v1",
            android.content.Context.MODE_PRIVATE)
        val resident = person("受人数上限保护" + UUID.randomUUID().toString().take(7))
            .copy(homeLocationCode = DigitalWorldPublicPlaces.COURTYARD)
        try {
            assertTrue(ResidentDailyPolicy.setMaxResidents(context, 0))
            assertThrows(IllegalArgumentException::class.java) {
                DigitalWorldExpansionStore.registerResident(resident)
            }
            assertTrue(ResidentDailyPolicy.setMaxResidents(context, ResidentDailyPolicy.MAX_RESIDENTS))
            assertTrue(ResidentDailyPolicy.setAutoGenerationEnabled(context, false))
            assertThrows(IllegalArgumentException::class.java) {
                DigitalWorldExpansionStore.registerResident(resident)
            }
            assertFalse(DigitalWorldExpansionStore.residents().any { it.id == resident.id })
        } finally {
            prefs.edit().clear().commit()
        }
    }

    @Test fun cafeWorkerHasARealHomeAndBusinessShift() {
        val employee = person("岑灯", "咖啡店店员")
        assertEquals(DigitalWorldPublicPlaces.CAFE,
            ResidentDailyRoutine.plan(employee, Instant.parse("2026-10-23T10:00:00Z"), ZoneOffset.UTC).location)
        assertEquals(employee.homeLocationCode,
            ResidentDailyRoutine.plan(employee, Instant.parse("2026-10-23T22:00:00Z"), ZoneOffset.UTC).location)
    }

    @Test fun independentResidentsHaveJobsLunchBreaksAndSleep() {
        val worker = person("照野")
        val morning = ResidentDailyRoutine.plan(worker, Instant.parse("2026-10-23T09:00:00Z"), ZoneOffset.UTC)
        val midday = ResidentDailyRoutine.plan(worker, Instant.parse("2026-10-23T12:50:00Z"), ZoneOffset.UTC)
        val home = ResidentDailyRoutine.plan(worker, Instant.parse("2026-10-23T22:30:00Z"), ZoneOffset.UTC)
        assertEquals(DigitalWorldPublicPlaces.COURTYARD, morning.location)
        // Without this resident's saved leisure choice, the world clock may
        // assign duty and rest slots but cannot invent a cafe visit.
        assertEquals(worker.homeLocationCode, midday.location)
        val chosenCafe = ResidentDailyRoutine.planWithDaily(worker,
            Instant.parse("2026-10-23T12:50:00Z"), ZoneOffset.UTC,
            org.json.JSONObject().put("lunch", "cafe"))
        assertEquals("lunch", chosenCafe.slot)
        assertEquals(DigitalWorldPublicPlaces.CAFE, chosenCafe.location)
        assertEquals(worker.homeLocationCode, home.location)
        assertNotEquals(morning.slot, midday.slot)
        assertTrue(ResidentDailyRoutine.plan(worker,
            Instant.parse("2026-10-23T18:30:00Z"), ZoneOffset.UTC).intendedActivity.isEmpty())
    }

    @Test fun persistedCitizenBecomesFullCharacterAndKeepsEditedPersona() {
        val context = RuntimeEnvironment.getApplication() as android.content.Context
        SharedExperienceTimeline.initialize(context)
        MigratedDomainStores.initialize(context)
        DigitalLifeProfileStore.initialize(context)
        DigitalWorldExpansionStore.initialize(context)
        DigitalWorldStore.initialize(context)
        val native = DigitalWorldExpansionStore.registerResident(
            person("职员" + UUID.randomUUID().toString().take(5), "公共设施巡视员").copy(
                homeLocationCode = DigitalWorldPublicPlaces.COURTYARD,
                temperament = "有礼且不喜欢被打断",
                preferences = "读旧地图",
            ))
        val id = ResidentCharacterBridge.characterId(native.id)
        assertTrue(ResidentCharacterBridge.sync(context) >= 1)
        val account = MigratedDomainStores.characters.get(id)
        assertEquals(native.name, account.displayName)
        assertTrue(account.persona.contains("不喜欢被打断"))
        assertTrue(DigitalLifeProfileStore.isEnabled(id))
        assertTrue(CharacterIdentityStore.get(id).contains("原住民"))
        assertEquals(native.homeLocationCode, DigitalWorldStore.homeLocation(id))
        assertNotNull(CharacterLifeStore.bodyState(id))
        val chat = MigratedDomainStores.chat.ensureConversation(id, native.name)
        assertEquals(id, chat.characterId)
        MigratedDomainStores.characters.update(account.copy(persona = "主人认真改过的独立性格"))
        ResidentCharacterBridge.sync(context)
        assertEquals("主人认真改过的独立性格", MigratedDomainStores.characters.get(id).persona)
    }

    @Test fun meetingMapKeepsResidentHomesSeparateFromOwnerCompanionHomes() {
        val companionId = "world-companion"
        val nativeId = "resident:street-neighbor"
        val realityId = "reality-only"
        val companion = CharacterSettings(characterId = companionId, displayName = "世界角色")
        val native = CharacterSettings(characterId = nativeId, displayName = "街区居民")
        val reality = CharacterSettings(characterId = realityId, displayName = "现实角色")
        val profiles = mapOf(
            companionId to DigitalLifeProfile(companionId, CharacterLifeForm.DIGITAL),
            nativeId to DigitalLifeProfile(nativeId, CharacterLifeForm.DIGITAL),
            realityId to DigitalLifeProfile(realityId, CharacterLifeForm.REAL_WORLD),
        )
        // Residents deliberately have digital character brains, but not a second
        // companion-home card or map shortcut. Real-world roles have no digital home.
        val companions = mapCompanionCharacters(listOf(native, reality, companion), profiles)
        assertEquals(listOf(companionId), companions.map { it.characterId })
        val companionHomes = companions.map { DigitalWorldStore.homeLocation(it.characterId) }
        val residentHomes = listOf(DigitalWorldStore.homeLocation(nativeId))
        assertEquals(listOf("home:world-companion"), companionHomes)
        assertEquals(listOf("resident-home:street-neighbor"), residentHomes)
        assertEquals(2, (companionHomes + residentHomes).distinct().size)

        // Resident records share the digital-world state but must never be
        // painted or notified as an extra owner-companion in any map/scene entry.
        val arrived = "shared:arrival"
        val world = DigitalWorldState(characterLocations = mapOf(
            companionId to arrived, nativeId to arrived, realityId to arrived,
        ))
        assertEquals(listOf(realityId, companionId), worldCompanionsAt(
            listOf(native, reality, companion), world, arrived,
        ).map { it.characterId })
        assertEquals(listOf(companionId), worldCompanionsAt(
            companions, world, arrived,
        ).map { it.characterId })
        assertTrue(worldCompanionsAt(listOf(native), world, arrived).isEmpty())
    }

    @Test fun repeatedRealTimeSlotDoesNotWriteSecondDutyTransition() {
        val context = RuntimeEnvironment.getApplication() as android.content.Context
        SharedExperienceTimeline.initialize(context)
        MigratedDomainStores.initialize(context)
        DigitalLifeProfileStore.initialize(context)
        DigitalWorldExpansionStore.initialize(context)
        DigitalWorldStore.initialize(context)
        val native = DigitalWorldExpansionStore.registerResident(
            person("日程" + UUID.randomUUID().toString().take(5), "园景照料员").copy(
                homeLocationCode = DigitalWorldPublicPlaces.COURTYARD))
        val now = Instant.now()
        ResidentDailyRoutine.refresh(context, now)
        val first = DigitalWorldExpansionStore.residents().first { it.id == native.id }
        assertTrue(first.dutyKey.isNotBlank())
        val bodyBefore = CharacterLifeStore.bodyState(ResidentCharacterBridge.characterId(native.id))?.toString()
        ResidentDailyRoutine.refresh(context, now)
        assertEquals(first.dutyKey, DigitalWorldExpansionStore.residents().first { it.id == native.id }.dutyKey)
        assertEquals(bodyBefore, CharacterLifeStore.bodyState(ResidentCharacterBridge.characterId(native.id))?.toString())
    }
    @Test fun retiringNativeResidentStopsActiveLifeWithoutDeletingSharedMeetingHistory() {
        val context = RuntimeEnvironment.getApplication() as android.content.Context
        SharedExperienceTimeline.initialize(context)
        MigratedDomainStores.initialize(context)
        DigitalLifeProfileStore.initialize(context)
        DigitalWorldExpansionStore.initialize(context)
        DigitalWorldStore.initialize(context)
        val name = "可退出居民" + UUID.randomUUID().toString().take(6)
        val native = DigitalWorldExpansionStore.registerResident(
            person(name, "街区手工艺人").copy(
                homeLocationCode = DigitalWorldPublicPlaces.COURTYARD,
                gender = "女", temperament = "有原则，也会因过分较真与人争执",
                preferences = "修补旧衣、收集小铜扣",
                background = "独自经营一间小修补铺，习惯为老物件留下原主的故事。"))
        val id = ResidentCharacterBridge.characterId(native.id)
        ResidentCharacterBridge.sync(context)
        assertTrue(MigratedDomainStores.characters.settings.value.containsKey(id))
        assertTrue(MigratedDomainStores.characters.get(id).persona.contains("性别身份：女"))
        val meeting = DigitalWorldStore.startMeeting(listOf(id), "世界入口")
        val archivedConversation = MigratedDomainStores.chat.ensureConversation(id, native.name)
        MigratedDomainStores.chat.sendUserMessage(archivedConversation.id, "曾经共同讨论过的事")
        assertTrue(com.jiacimu.lulu.ownerVisibleQqConversations(
            MigratedDomainStores.chat.conversations.value,
            MigratedDomainStores.characters.settings.value.keys,
        ).any { it.id == archivedConversation.id })
        val eventId = "resident-retirement-record-${native.id}"
        SharedExperienceTimeline.record(eventId, id, "数字世界", native.name, "曾在入口见面",
            triggerExtraction = false, source = "resident-duty",
            evidenceKind = EventEvidenceKind.Observation)
        assertTrue(DigitalWorldStore.state.value.meetings.any { it.id == meeting.id })
        assertTrue(DigitalWorldExpansionStore.removeResident(native.id))
        assertFalse(DigitalWorldExpansionStore.removeResident(native.id))
        assertTrue(DigitalWorldExpansionStore.isRetiredTemplate(name))
        assertFalse(DigitalWorldExpansionStore.residents().any { it.id == native.id })
        assertFalse(MigratedDomainStores.characters.settings.value.containsKey(id))
        // Removing a resident hides its orphaned private card, not the archived
        // message or other people's shared witness records.
        assertFalse(com.jiacimu.lulu.ownerVisibleQqConversations(
            MigratedDomainStores.chat.conversations.value,
            MigratedDomainStores.characters.settings.value.keys,
        ).any { it.id == archivedConversation.id })
        assertEquals("曾经共同讨论过的事",
            MigratedDomainStores.chat.messages(archivedConversation.id).value.last().content)
        assertFalse(DigitalLifeProfileStore.isEnabled(id))
        assertFalse(DigitalWorldStore.state.value.characterLocations.containsKey(id))
        assertTrue(DigitalWorldStore.state.value.meetings
            .any { it.id == meeting.id && id in it.participantIds })
        assertNotNull(SharedExperienceTimeline.eventsByIds(id, listOf(eventId)).firstOrNull())
        assertThrows(IllegalArgumentException::class.java) {
            DigitalWorldExpansionStore.registerResident(native.copy(id = UUID.randomUUID().toString()))
        }
    }

    @Test fun nativeResidentEditsKeepGenderAndLifeHistoryAcrossBridgeSync() {
        val context = RuntimeEnvironment.getApplication() as android.content.Context
        SharedExperienceTimeline.initialize(context)
        MigratedDomainStores.initialize(context)
        DigitalLifeProfileStore.initialize(context)
        DigitalWorldExpansionStore.initialize(context)
        val native = DigitalWorldExpansionStore.registerResident(
            person("性别档案" + UUID.randomUUID().toString().take(6)).copy(
                homeLocationCode = DigitalWorldPublicPlaces.COURTYARD,
                gender = "男", background = "独自研究旧街的路标。"))
        ResidentCharacterBridge.sync(context)
        val revised = DigitalWorldExpansionStore.updateResidentProfile(
            residentId = native.id, name = native.name, identity = native.identity,
            temperament = "外向而有自己的界限", preferences = "地形图",
            background = "小时候在旧街迷过路，后来自己绘制路线。", gender = "非二元")
        assertEquals("非二元", revised.gender)
        ResidentCharacterBridge.sync(context)
        val character = MigratedDomainStores.characters.get(ResidentCharacterBridge.characterId(native.id))
        assertTrue(character.persona.contains("性别身份：非二元"))
        assertTrue(character.persona.contains("绘制路线"))
    }

    @Test fun dailySceneKeepsExplicitFemaleIdentityAndFullAuthoredTailWithoutPrivateMind() {
        val context = RuntimeEnvironment.getApplication() as android.content.Context
        com.jiacimu.lulu.LuluRepositories.initialize(context)
        MigratedDomainStores.initialize(context)
        DigitalWorldExpansionStore.initialize(context)
        DigitalWorldStore.initialize(context)
        SharedExperienceTimeline.initialize(context)
        val native = DigitalWorldExpansionStore.registerResident(
            person("日常场景店员" + UUID.randomUUID().toString().take(5), "女性店员").copy(
                gender = "女性", homeLocationCode = DigitalWorldPublicPlaces.COURTYARD))
        val id = ResidentCharacterBridge.characterId(native.id)
        try {
            ResidentCharacterBridge.sync(context)
            val role = MigratedDomainStores.characters.get(id)
            val authored = "长期审美与行事方式。".repeat(50) + "尾部设定：女性，表达直接。"
            MigratedDomainStores.characters.update(role.copy(persona = authored))
            CharacterInnerLifeStore.initialize(context)
            CharacterInnerLifeStore.recordInnerVoice(id, "private-source", "不该给公共作者的私密心声")
            val peer = WorldSceneParticipantProfile.context(listOf(id), forNarrator = false)
            val narrator = WorldSceneParticipantProfile.context(listOf(id), forNarrator = true)
            assertTrue(peer.contains("明确性别=女性"))
            assertTrue(peer.contains("女性店员"))
            assertFalse(peer.contains("尾部设定"))
            assertTrue(narrator.contains(authored))
            assertFalse(narrator.contains("不该给公共作者"))
            assertFalse(peer.contains("不该给公共作者"))
        } finally {
            DigitalWorldExpansionStore.removeResident(native.id)
        }
    }

    @Test fun residentCanScheduleFollowUpButCannotBypassDailyAgendaQuota() {
        val context = RuntimeEnvironment.getApplication() as android.content.Context
        com.jiacimu.lulu.LuluRepositories.initialize(context)
        val id = "resident:follow-up-" + UUID.randomUUID()
        val before = ResidentDailyPolicy.maxAgendaItems(context)
        val now = Instant.now()
        try {
            assertTrue(ResidentDailyPolicy.setMaxAgendaItems(context, 1))
            assertTrue(CapabilityRegistry.allows(context, id,
                CapabilityRegistry.find("plan_personal_activity")!!, false))
            val first = RoleDayScheduleStore.arrange(context, id, "继续交流", "想听听对方的后续看法",
                now.plusSeconds(600), now, activityAction = "digital_world_action")
            assertEquals(first.id, RoleDayScheduleStore.arrange(context, id, "继续交流", "想听听对方的后续看法",
                now.plusSeconds(600), now, activityAction = "digital_world_action").id)
            assertThrows(IllegalArgumentException::class.java) {
                RoleDayScheduleStore.arrange(context, id, "另一件事", "另有一件想继续的事",
                    now.plusSeconds(1200), now, activityAction = "digital_world_action")
            }
            assertThrows(IllegalArgumentException::class.java) {
                RoleDayScheduleStore.arrange(context, id, "现实屏幕", "不能越权安排现实手机",
                    now.plusSeconds(1800), now, activityAction = "capture_screen")
            }
            RoleDayScheduleStore.cancel(context, id, first.id, "测试结束", now)
        } finally {
            ResidentDailyPolicy.setMaxAgendaItems(context, before)
        }
    }

}
