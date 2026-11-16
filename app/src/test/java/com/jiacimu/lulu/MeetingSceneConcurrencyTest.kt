package com.jiacimu.lulu

import com.jiacimu.lulu.data.MeetingExperienceStore
import com.jiacimu.lulu.data.MeetingParticipantSceneState
import com.jiacimu.lulu.data.MeetingReality
import com.jiacimu.lulu.data.MeetingSceneSnapshot
import com.jiacimu.lulu.data.MeetingSession
import org.junit.Assert.*
import org.junit.Test
import java.time.Instant
import java.util.UUID

class MeetingSceneConcurrencyTest {
    @Test fun modelReplyFromBeforeTouchMustNotUndoActualHandReleaseOrArrival() {
        val session = MeetingSession(UUID.randomUUID().toString(), listOf("role-a"),
            MeetingReality.DIGITAL_WORLD, "云眠原", Instant.EPOCH)
        val before = MeetingSceneSnapshot(session.location, participants = listOf(
            MeetingParticipantSceneState("user"),
            MeetingParticipantSceneState("role-a", destinationItemId = "bed-old",
                destinationActivityId = "sit_on_bed", contact = listOf("handholding:user")),
        ), updatedAt = Instant.EPOCH)
        MeetingExperienceStore.updateScene(session.id, before)
        val actual = before.copy(participants = listOf(
            MeetingParticipantSceneState("user"),
            MeetingParticipantSceneState("role-a", destinationItemId = "",
                destinationActivityId = "", position = "床边", contact = emptyList()),
        ), updatedAt = Instant.EPOCH.plusSeconds(5))
        MeetingExperienceStore.updateScene(session.id, actual)
        val oldModelProposal = before.copy(participants = listOf(
            before.participants[0],
            before.participants[1].copy(destinationItemId = "bed-old",
                destinationActivityId = "sit_on_bed", contact = listOf("handholding:user")),
        ))
        val merged = meetingAuthoritativeSceneV2(session, oldModelProposal,
            Instant.EPOCH.plusSeconds(6))
        val role = merged.participants.single { it.participantId == "role-a" }
        assertEquals("", role.destinationItemId)
        assertEquals("", role.destinationActivityId)
        assertTrue(role.contact.isEmpty())
        assertEquals("床边", role.position)
    }

    @Test fun liveUncontestedModelDecisionStillCanStartActualNavigation() {
        val session = MeetingSession(UUID.randomUUID().toString(), listOf("role-a"),
            MeetingReality.DIGITAL_WORLD, "云眠原", Instant.EPOCH)
        val current = MeetingSceneSnapshot(session.location, participants = listOf(
            MeetingParticipantSceneState("user"), MeetingParticipantSceneState("role-a"),
        ), updatedAt = Instant.EPOCH)
        MeetingExperienceStore.updateScene(session.id, current)
        val proposed = current.copy(participants = listOf(
            current.participants[0], current.participants[1].copy(destinationItemId = "bed-01",
                destinationActivityId = "sit_on_bed"),
        ))
        val applied = meetingAuthoritativeSceneV2(session, proposed, Instant.EPOCH.plusSeconds(3))
        assertEquals("bed-01", applied.participants[1].destinationItemId)
        assertEquals("sit_on_bed", applied.participants[1].destinationActivityId)
    }
}
