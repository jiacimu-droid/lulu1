package com.jiacimu.lulu.data

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
    @org.junit.Before fun initializeWorkerHost() {
        val app = RuntimeEnvironment.getApplication() as android.content.Context
        if (runCatching { androidx.work.WorkManager.getInstance(app) }.isFailure)
            androidx.work.WorkManager.initialize(app, androidx.work.Configuration.Builder().build())
    }

    private fun person(template: String, identity: String = "世界巡逻员") =
        DigitalWorldResident(id = UUID.randomUUID().toString(), name = template,
            identity = identity, homeLocationCode = "resident-home:testing",
            templateName = template)

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
        assertEquals(DigitalWorldPublicPlaces.CAFE, midday.location)
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
}
