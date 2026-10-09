package com.jiacimu.lulu

import com.jiacimu.lulu.data.MeetingParticipantSceneState
import com.jiacimu.lulu.data.MeetingSceneSnapshot
import org.junit.Assert.*
import org.junit.Test
import java.time.Instant

class MeetingPhysicalStateReducerTest {
    private val original = MeetingSceneSnapshot("云眠原", participants = listOf(
        MeetingParticipantSceneState("user"),
        MeetingParticipantSceneState("role-a"),
        MeetingParticipantSceneState("role-b"),
    ))

    @Test fun touchIsRecordedForOnlyTheActualPartner() {
        val held = MeetingPhysicalStateReducer.apply(original, "role-a", "HOLD_HANDS", Instant.EPOCH)
        assertEquals(listOf("handholding:user"), held.participants[1].contact)
        assertEquals("FOLLOW_USER", held.participants[1].explorationMode)
        assertTrue(held.participants[2].contact.isEmpty())
        assertTrue(original.participants[1].contact.isEmpty())
        val released = MeetingPhysicalStateReducer.apply(held, "role-a", "RELEASE_HANDS", Instant.EPOCH)
        assertTrue(released.participants[1].contact.isEmpty())
    }

    @Test fun departureEndsContactAndFollowingWithoutInventingOtherChanges() {
        val held = MeetingPhysicalStateReducer.apply(original, "role-a", "HOLD_HANDS", Instant.EPOCH)
        val departed = MeetingPhysicalStateReducer.userDeparted(held, Instant.EPOCH)
        assertEquals("STAY", departed.participants[1].explorationMode)
        assertTrue(departed.participants[1].contact.isEmpty())
        assertEquals("已离开当前场景", departed.participants[0].posture)
        assertEquals(original.participants[2], departed.participants[2])
    }
}
