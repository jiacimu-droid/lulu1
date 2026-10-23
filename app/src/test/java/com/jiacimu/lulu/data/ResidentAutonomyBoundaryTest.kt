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
class ResidentAutonomyBoundaryTest {
    private val now = Instant.parse("2026-10-23T13:00:00Z")

    @Test fun oneClaimPerNativeCitizenPerCalendarDayEvenIfWorkerRestarts() {
        val context = RuntimeEnvironment.getApplication()
        val id = UUID.randomUUID().toString()
        assertTrue(ResidentDailyDecision.claim(context, id, now))
        assertFalse(ResidentDailyDecision.claim(context, id, now))
        assertFalse(ResidentDailyDecision.claim(context, id, now.plusSeconds(10)))
        assertTrue(ResidentDailyDecision.claim(context, id, now.plusSeconds(86_400)))
        assertFalse(ResidentDailyDecision.claim(context, id, now.plusSeconds(86_400)))
    }

    @Test fun worldHasSharedPublicCalendarNotFabricatedParticipants() {
        val friday = DigitalWorldPublicBulletin.forDate(now, ZoneOffset.UTC)
        assertNotNull(friday)
        assertEquals(DigitalWorldPublicPlaces.COURTYARD, friday!!.location)
        assertTrue(friday.notice.contains("必须有人参加"))
        assertEquals(friday.eventId, DigitalWorldPublicBulletin.forDate(now.plusSeconds(100), ZoneOffset.UTC)?.eventId)
        val tuesday = DigitalWorldPublicBulletin.forDate(Instant.parse("2026-10-20T13:00:00Z"), ZoneOffset.UTC)
        assertNull(tuesday)
    }

    @Test fun dailyPlannerCannotCreateFantasyWorldLocations() {
        assertEquals(DigitalWorldPublicPlaces.CAFE,
            ResidentDailyDecision.publicPlace("cafe", "resident-home:r"))
        assertEquals("resident-home:r",
            ResidentDailyDecision.publicPlace("home", "resident-home:r"))
        assertNull(ResidentDailyDecision.allowedLeisureLocation("real-world-user-house"))
        assertNull(ResidentDailyDecision.allowedLeisureLocation("secret-phone"))
    }

    @Test fun privateSocialContactsRequireRealWorldProximityOrHistoricalExchange() {
        val context = RuntimeEnvironment.getApplication()
        MigratedDomainStores.initialize(context)
        DigitalLifeProfileStore.initialize(context)
        DigitalWorldStore.initialize(context)
        SharedExperienceTimeline.initialize(context)
        ResidentSocialMailbox.initialize(context)
        val first = MigratedDomainStores.characters.create("居民社交测试甲-" + UUID.randomUUID())
        val second = MigratedDomainStores.characters.create("居民社交测试乙-" + UUID.randomUUID())
        DigitalLifeProfileStore.registerNewLife(first.characterId, first.displayName, "原住民")
        DigitalLifeProfileStore.registerNewLife(second.characterId, second.displayName, "原住民")
        assertFalse(ResidentSocialMailbox.canContact(first.characterId, second.characterId))
        val place = DigitalWorldPublicPlaces.COURTYARD
        assertTrue(DigitalWorldStore.performAction(first.characterId, "visit_public_place",
            org.json.JSONObject().put("locationCode", place)).success)
        assertTrue(DigitalWorldStore.performAction(second.characterId, "visit_public_place",
            org.json.JSONObject().put("locationCode", place)).success)
        assertTrue(ResidentSocialMailbox.canContact(first.characterId, second.characterId))
        val msg = ResidentSocialMailbox.send(first.characterId, second.characterId, "你好，刚才在庭院看见你了")
        assertEquals("character", msg.author)
        assertTrue(ResidentSocialMailbox.inbox(first.characterId).any { it.id == msg.id })
        assertTrue(ResidentSocialMailbox.inbox(second.characterId).any { it.id == msg.id })
        val outsider = MigratedDomainStores.characters.create("第三人-" + UUID.randomUUID())
        assertFalse(ResidentSocialMailbox.inbox(outsider.characterId).any { it.id == msg.id })
        assertTrue(ResidentSocialMailbox.canContact(first.characterId, second.characterId))
    }
}
