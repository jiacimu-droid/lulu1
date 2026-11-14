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

    @Test fun socialInboxConsiderationIsOldestFirstAndNeverLosesUnseenLetters() {
        val messages = (1..5).map { i ->
            ResidentDirectMessage("mail-$i", "sender", "receiver",
                "消息$i", now.plusSeconds(i.toLong()), "character")
        }
        assertEquals(listOf("mail-1", "mail-2"),
            ResidentSocialMailbox.unconsidered(messages, "", 2).map { it.id })
        assertEquals(listOf("mail-3", "mail-4"),
            ResidentSocialMailbox.unconsidered(messages, "mail-2", 2).map { it.id })
        assertEquals(listOf("mail-5"),
            ResidentSocialMailbox.unconsidered(messages, "mail-4", 2).map { it.id })
    }

    @Test fun cafeNeedsExplicitDailyRoleChoiceAndCannotBuyFromAnInvalidPlan() {
        val daily = org.json.JSONObject()
            .put("cafeOrderLunch", "tea")
            .put("cafeOrderEvening", "none")
        assertNull(ResidentDailyRoutine.chosenCafeOrder(null, "lunch"))
        assertEquals("cafe_consume_tea", ResidentDailyRoutine.chosenCafeOrder(daily, "lunch"))
        // The actor may choose any real menu item, not only the two formerly
        // hard-coded food/drink defaults in the clock worker.
        val coffee = org.json.JSONObject().put("cafeOrderLunch", "coffee")
        assertEquals("cafe_consume_coffee",
            ResidentDailyRoutine.chosenCafeOrder(coffee, "lunch"))
        val cake = org.json.JSONObject().put("cafeOrderLunch", "cake")
        assertEquals("cafe_consume_cake", ResidentDailyRoutine.chosenCafeOrder(cake, "lunch"))
        assertNull(ResidentDailyRoutine.chosenCafeOrder(daily, "evening"))
        assertNull(ResidentDailyRoutine.chosenCafeOrder(daily, "morning"))
        assertNull(ResidentDailyRoutine.chosenCafeOrder(
            org.json.JSONObject().put("cafeOrderLunch", "sandwich-and-tea"), "lunch"))
        val valid = org.json.JSONObject().put("lunch", "cafe").put("evening", "home")
            .put("cafeOrderLunch", "sandwich")
        assertNull(ResidentDailyDecision.dayContractError(valid, now))
        assertTrue(ResidentDailyDecision.dayContractError(
            valid.put("cafeOrderLunch", "surprise"), now)?.contains("cafeOrderLunch") == true)
    }

    @Test fun dailyPlannerCannotCreateFantasyWorldLocations() {
        assertEquals(DigitalWorldPublicPlaces.CAFE,
            ResidentDailyDecision.publicPlace("cafe", "resident-home:r"))
        assertEquals("resident-home:r",
            ResidentDailyDecision.publicPlace("home", "resident-home:r"))
        assertNull(ResidentDailyDecision.allowedLeisureLocation("real-world-user-house"))
        assertNull(ResidentDailyDecision.allowedLeisureLocation("secret-phone"))
    }

    @Test fun cafeWorkerCanTakeChosenBreaksWithoutFakingOutings() {
        val worker = DigitalWorldResident(
            id = "barista-plan", name = "岑灯", templateName = "岑灯",
            identity = "咖啡角店员", homeLocationCode = "resident-home:barista-plan",
        )
        val chosen = org.json.JSONObject()
            .put("lunch", "courtyard").put("evening", "games")
        val zone = ZoneOffset.UTC
        fun planAt(hourMinute: String, daily: org.json.JSONObject? = chosen): ResidentDailyRoutine.Plan =
            ResidentDailyRoutine.planWithDaily(
                worker, Instant.parse("2026-11-02T${hourMinute}:00Z"), zone, daily)
        assertEquals(DigitalWorldPublicPlaces.CAFE, planAt("10:00").location)
        assertEquals(DigitalWorldPublicPlaces.COURTYARD, planAt("12:40").location)
        assertEquals("lunch", planAt("12:40").slot)
        assertEquals(DigitalWorldPublicPlaces.GAME_HALL, planAt("18:40").location)
        assertEquals("evening", planAt("18:40").slot)
        assertEquals(DigitalWorldPublicPlaces.CAFE, planAt("20:00").location)
        assertEquals(worker.homeLocationCode, planAt("12:40", null).location)
        assertEquals(worker.homeLocationCode, planAt("18:40", null).location)
    }

    @Test fun dailyLeisureChoicesRecognizeOnlySavedNewPublicPlaces() {
        val app = RuntimeEnvironment.getApplication()
        DigitalWorldExpansionStore.initialize(app)
        val code = "shared:discovered:" + UUID.randomUUID()
        val place = DigitalWorldDiscoveredPlace(
            code = code, label = "自由逛逛-" + UUID.randomUUID().toString().take(8),
            subtitle = "散步", purpose = "相遇与散步",
            connectedTo = listOf(DigitalWorldPublicPlaces.COURTYARD),
            createdByCharacterId = "test",
        )
        DigitalWorldExpansionStore.registerPlace(place)
        assertEquals(code, ResidentDailyDecision.allowedLeisureLocation(code))
        assertEquals(code, ResidentDailyDecision.publicPlace(code, "resident-home:test"))
        assertNull(ResidentDailyDecision.allowedLeisureLocation("shared:discovered:not-created"))
        val chosen = org.json.JSONObject().put("lunch", code).put("evening", "home")
        assertNull(ResidentDailyDecision.dayContractError(chosen, now))
    }

    @Test fun confirmedFriendsCanStartTheirFirstPrivateMessageWithoutProximity() {
        val context = RuntimeEnvironment.getApplication()
        MigratedDomainStores.initialize(context)
        DigitalLifeProfileStore.initialize(context)
        DigitalWorldStore.initialize(context)
        SharedExperienceTimeline.initialize(context)
        ResidentSocialMailbox.initialize(context)
        MomentFriendAccess.initialize(context)
        val from = MigratedDomainStores.characters.create("首次私信甲-" + UUID.randomUUID())
        val to = MigratedDomainStores.characters.create("首次私信乙-" + UUID.randomUUID())
        DigitalLifeProfileStore.registerNewLife(from.characterId, from.displayName, "数字生命")
        DigitalLifeProfileStore.registerNewLife(to.characterId, to.displayName, "数字生命")
        assertFalse(ResidentSocialMailbox.canContact(from.characterId, to.characterId))
        assertTrue(MomentFriendAccess.setFriends(from.characterId, to.characterId, true))
        assertTrue(ResidentSocialMailbox.canContact(from.characterId, to.characterId))
        assertTrue(ResidentSocialMailbox.visibleContacts(from.characterId).contains(to.characterId))
        assertEquals("第一次聊天", ResidentSocialMailbox.send(
            from.characterId, to.characterId, "第一次聊天").content)
    }

    @Test fun confirmedCompanionAndFemaleCafeResidentCanExchangeMailWhileApart() {
        val context = RuntimeEnvironment.getApplication()
        MigratedDomainStores.initialize(context)
        DigitalLifeProfileStore.initialize(context)
        DigitalWorldStore.initialize(context)
        SharedExperienceTimeline.initialize(context)
        ResidentSocialMailbox.initialize(context)
        MomentFriendAccess.initialize(context)

        val role = MigratedDomainStores.characters.create("朋友主角-" + UUID.randomUUID())
        val residentId = "resident:cafe-" + UUID.randomUUID()
        MigratedDomainStores.characters.update(CharacterSettings(
            characterId = residentId, displayName = "岑灯", persona = "女性咖啡店员"))
        DigitalLifeProfileStore.registerNewLife(role.characterId, role.displayName, "数字生命")
        DigitalLifeProfileStore.registerNewLife(residentId, "岑灯", "数字世界原住民")

        assertFalse(ResidentSocialMailbox.canContact(role.characterId, residentId))
        try {
            assertTrue(MomentFriendAccess.setFriends(role.characterId, residentId, true))
            assertTrue(ResidentSocialMailbox.canContact(role.characterId, residentId))
            assertTrue(ResidentSocialMailbox.canContact(residentId, role.characterId))
            assertTrue(ResidentSocialMailbox.visibleContacts(role.characterId).contains(residentId))
            assertTrue(ResidentSocialMailbox.visibleContacts(residentId).contains(role.characterId))
            // No location or same-day encounter is required once friendship is confirmed.
            val hello = ResidentSocialMailbox.send(role.characterId, residentId,
                "今天到咖啡角没遇见你，最近怎么样？", requestPeerReview = false)
            val reply = ResidentSocialMailbox.send(residentId, role.characterId,
                "我今天换了班，改天见。", requestPeerReview = false)
            assertEquals(listOf(hello.id, reply.id),
                ResidentSocialMailbox.historyForInspection(role.characterId)
                    .filter { it.id == hello.id || it.id == reply.id }.map { it.id })
            assertEquals(listOf(hello.id, reply.id),
                ResidentSocialMailbox.historyForInspection(residentId)
                    .filter { it.id == hello.id || it.id == reply.id }.map { it.id })
        } finally {
            MomentFriendAccess.setFriends(role.characterId, residentId, false)
        }
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
        assertEquals(listOf(msg.id),
            ResidentSocialMailbox.pendingIncoming(second.characterId).map { it.id })
        assertFalse(ResidentSocialMailbox.acknowledgeIncoming(first.characterId, msg.id))
        assertTrue(ResidentSocialMailbox.acknowledgeIncoming(second.characterId, msg.id))
        assertTrue(ResidentSocialMailbox.pendingIncoming(second.characterId).isEmpty())
        val later = ResidentSocialMailbox.send(first.characterId, second.characterId, "哪天有空再聊")
        assertEquals(listOf(later.id),
            ResidentSocialMailbox.pendingIncoming(second.characterId).map { it.id })
        val outsider = MigratedDomainStores.characters.create("第三人-" + UUID.randomUUID())
        assertFalse(ResidentSocialMailbox.inbox(outsider.characterId).any { it.id == msg.id })
        assertTrue(ResidentSocialMailbox.canContact(first.characterId, second.characterId))
    }
}
