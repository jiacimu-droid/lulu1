package com.jiacimu.lulu.data

import org.junit.Assert.*
import org.junit.Test
import java.time.Instant

/**
 * Multi-person scene planning must consume the INTERSECTION of witnessed
 * events, never a union of role-owned diaries, private chats or impressions.
 * No model call is required to test that source boundary.
 */
class AutonomousEncounterPrivacyTest {
    private val now = Instant.parse("2026-10-28T05:00:00Z")

    private fun meeting(
        id: String,
        members: List<String>,
        spoken: String,
        end: Instant? = now,
        reality: MeetingReality = MeetingReality.DIGITAL_WORLD,
        withTurn: Boolean = true,
    ) = MeetingSession(
        id = id,
        participantIds = members,
        reality = reality,
        location = "shared:cloud_meadow",
        startedAt = now.minusSeconds(1800),
        endedAt = end,
        turns = if (withTurn) listOf(MeetingTurn(
            id = "$id-turn",
            speakerId = members.first(),
            speakerName = "发言者",
            sceneText = "在草地旁招了招手",
            dialogue = spoken,
            occurredAt = now.minusSeconds(400),
        )) else emptyList(),
    )

    @Test fun privateMeetingCannotLeakIntoOtherParticipantScene() {
        val aBSecret = meeting("a-b", listOf("a", "b"), "甲私下告诉乙的家庭秘密")
        val aCSecret = meeting("a-c", listOf("a", "c"), "甲私下告诉丙的秘密")
        val shared = meeting("a-b-c", listOf("a", "b", "c"), "三人都听见的笑话")
        val view = AutonomousSocialRuntime.witnessedSharedEncounterContext(
            listOf("a", "b", "c"), listOf(aBSecret, aCSecret, shared))
        assertTrue(view.contains("三人都听见的笑话"))
        assertFalse(view.contains("家庭秘密"))
        assertFalse(view.contains("甲私下告诉丙的秘密"))
        assertFalse(view.contains("a-b，"))
        assertFalse(view.contains("a-c，"))
        // Another pair has a different witness set and may know their own chat.
        val pair = AutonomousSocialRuntime.witnessedSharedEncounterContext(
            listOf("a", "b"), listOf(aBSecret, aCSecret, shared))
        assertTrue(pair.contains("甲私下告诉乙的家庭秘密"))
        assertFalse(pair.contains("甲私下告诉丙的秘密"))
    }

    @Test fun canceledDraftAndSimulatedMeetingAreNotMemoriesAndOldContextIsBounded() {
        val draft = meeting("draft", listOf("a","b","c"), "没有发生的交流", end = null)
        val simulated = meeting("simulated", listOf("a","b","c"),
            "仅在模拟剧场说过的话", reality = MeetingReality.REALISTIC_SIMULATION)
        val blank = meeting("empty", listOf("a","b","c"), "", withTurn = false)
        val real = (1..5).map { index ->
            meeting("real-$index", listOf("a","b","c"),
                "已完成的共同会面-$index", end = now.plusSeconds(index.toLong()))
        }
        val view = AutonomousSocialRuntime.witnessedSharedEncounterContext(
            listOf("a","b","c"), listOf(draft, simulated, blank) + real)
        assertFalse(view.contains("没有发生的交流"))
        assertFalse(view.contains("仅在模拟剧场说过的话"))
        assertFalse(view.contains("已完成的共同会面-1"))
        assertTrue(view.contains("已完成的共同会面-3"))
        assertTrue(view.contains("已完成的共同会面-5"))
        assertEquals("", AutonomousSocialRuntime.witnessedSharedEncounterContext(
            listOf("a","b","c"), listOf(draft,simulated,blank)))
        assertEquals("", AutonomousSocialRuntime.witnessedSharedEncounterContext(
            listOf("a"), real))
    }

    @Test fun literarySourcesIncludeCommittedConversationAndOnlyCurrentViewersEvidence() {
        val finished = meeting("social-42", listOf("person-a", "person-b"),
            "我有个主意，要不要下次一起去玩游戏？")
        val tick = DigitalWorldLifeTick(
            incidentId = "event-17", locationCode = "cafe",
            locationName = "咖啡角", kind = "seat_light",
            anchorItemId = "", anchorItemName = "", status = "resolved",
            stage = 0, summary = "咖啡厅座位旁的光线变化", occurredAt = now)
        val a = AutonomousSocialRuntime.witnessedEpisodeSources(
            finished, "person-a", tick, now)
        val b = AutonomousSocialRuntime.witnessedEpisodeSources(
            finished, "person-b", tick, now)
        assertEquals(4, a.size)
        assertTrue(a.contains("meeting-social-42-autonomous-turn-social-42-turn-viewer-person-a"))
        assertTrue(a.contains("world-fact-event-17-person-a-${now.toEpochMilli()}"))
        assertEquals("meeting-social-42-autonomous-summary-viewer-person-a", a.last())
        assertEquals("meeting-social-42-autonomous-summary-viewer-person-b", b.last())
        assertTrue(a.all { it.endsWith("person-a") || it.contains("-person-a-") })
        assertTrue(b.all { it.endsWith("person-b") || it.contains("-person-b-") })
        assertTrue(a.none { it.contains("person-b") })
    }


    @Test fun socialWorldOpportunitiesRequireRealCoLocatedPeople() {
        val places = listOf(
            DigitalWorldPublicPlaces.CAFE,
            DigitalWorldPublicPlaces.GAME_HALL,
            DigitalWorldPublicPlaces.COURTYARD,
            DigitalWorldStore.CLOUD_MEADOW,
        )
        places.forEach { place ->
            val alone = DigitalWorldLifeEventStore.socialOpportunityKinds(place, false)
            val together = DigitalWorldLifeEventStore.socialOpportunityKinds(place, true)
            assertTrue("No social world moment when alone at $place", alone.isEmpty())
            assertTrue("Social moment available with witnesses at $place", together.isNotEmpty())
        }
        assertTrue(DigitalWorldLifeEventStore.socialOpportunityKinds("unknown", true).isEmpty())
    }

}
