package com.jiacimu.lulu

import com.jiacimu.lulu.data.MeetingExperienceStore
import com.jiacimu.lulu.data.MeetingParticipantSceneState
import com.jiacimu.lulu.data.MeetingReality
import com.jiacimu.lulu.data.MeetingSceneSnapshot
import com.jiacimu.lulu.data.MeetingSession
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.time.Instant
import java.util.UUID

@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE, sdk = [28])
class MeetingSceneConsentTest {
    @Test fun narrationCannotCreateOrEraseRealHandholding() {
        val session = MeetingSession(UUID.randomUUID().toString(), listOf("role-a"),
            MeetingReality.DIGITAL_WORLD, "云眠原", Instant.EPOCH)
        val scene = MeetingSceneSnapshot(session.location, participants = listOf(
            MeetingParticipantSceneState("user"), MeetingParticipantSceneState("role-a")))
        MeetingExperienceStore.updateScene(session.id, scene)
        val attempt = JSONObject().put("participants",
            JSONArray().put(JSONObject()
                .put("participantId", "role-a")
                .put("contact", JSONArray().put("handholding:user"))))
        val imagined = meetingParseSceneSnapshotV2(attempt, session, "role-a")!!
        assertTrue(imagined.participants[1].contact.isEmpty())

        val accepted = MeetingPhysicalStateReducer.apply(
            scene, "role-a", "ACCEPT_HAND_OFFER", Instant.EPOCH.plusSeconds(1))
        MeetingExperienceStore.updateScene(session.id, accepted)
        val attemptErase = JSONObject().put("participants",
            JSONArray().put(JSONObject().put("participantId", "role-a")
                .put("contact", JSONArray())))
        val parsed = meetingParseSceneSnapshotV2(attemptErase, session, "role-a")!!
        assertEquals(listOf("handholding:user"), parsed.participants[1].contact)
        assertEquals("FOLLOW_USER", parsed.participants[1].explorationMode)
    }
}
