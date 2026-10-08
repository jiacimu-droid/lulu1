package com.jiacimu.lulu

import android.content.Context
import com.jiacimu.lulu.data.*
import com.jiacimu.lulu.games.*
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import java.time.Instant

@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE, sdk = [28])
class WorldExplorationContractsTest {
    @Test fun soloPlayNeverAttributesThePlayersRunToThePreviouslySelectedRole() {
        val context = RuntimeEnvironment.getApplication() as Context
        val store = LuluGameStore(context)
        store.selectCharacters(listOf("previous-role"))
        store.selectCharacters(emptyList())
        val id = store.recordExternalGame(LuluGameType.DeepSeaJourney, "深海回声", 100, 0, "自己完成潜航")
        val restored = LuluGameStore(context).state.value
        assertEquals("", restored.selectedCharacterId)
        assertFalse(restored.playWithCharacter)
        val record = restored.records.single { it.id == id }
        assertEquals("", record.characterId)
        assertFalse(record.playedWithCharacter)
    }

    @Test fun onlyTheRespondingRoleCanAgreeToFollowAndTheStateSurvivesSaving() {
        val session = MeetingSession("follow-contract", listOf("a", "b"), MeetingReality.DIGITAL_WORLD, "云眠原", Instant.EPOCH)
        val original = MeetingSceneSnapshot(session.location, participants = listOf(
            MeetingParticipantSceneState("user"), MeetingParticipantSceneState("a"),
            MeetingParticipantSceneState("b", explorationMode = "STAY")))
        MeetingExperienceStore.updateScene(session.id, original)
        val participants = JSONArray().put(JSONObject().put("participantId", "a").put("explorationMode", "FOLLOW_USER"))
            .put(JSONObject().put("participantId", "b").put("explorationMode", "FOLLOW_USER"))
            .put(JSONObject().put("participantId", "user").put("explorationMode", "FOLLOW_USER"))
        val parsed = meetingParseSceneSnapshotV2(JSONObject().put("participants", participants), session, "a")!!
        assertEquals("FOLLOW_USER", parsed.participants.single { it.participantId == "a" }.explorationMode)
        assertEquals("STAY", parsed.participants.single { it.participantId == "b" }.explorationMode)
        assertEquals("", parsed.participants.single { it.participantId == "user" }.explorationMode)
        assertEquals(parsed, parsed.toJson().toScene())
        val legacy = parsed.toJson()
        legacy.getJSONArray("participants").getJSONObject(1).remove("explorationMode")
        assertEquals("", legacy.toScene()!!.participants.single { it.participantId == "a" }.explorationMode)
        MeetingExperienceStore.updateScene(session.id, parsed)
        val invalid = JSONObject().put("participants", JSONArray().put(JSONObject().put("participantId", "a").put("explorationMode", "invented")))
        assertEquals("FOLLOW_USER", meetingParseSceneSnapshotV2(invalid, session, "a")!!.participants.single { it.participantId == "a" }.explorationMode)
        MeetingExperienceStore.updateScene(session.id, original)
        assertEquals("", MeetingExperienceStore.sceneFor(session).participants.single { it.participantId == "a" }.explorationMode)
    }

    @Test fun followingRoutesAroundFurnitureAndCannotPassThroughASealedWall() {
        val bounds = WorldRectangle(0f, 0f, 600f, 400f)
        val sofa = WorldObstacle("sofa", WorldRectangle(240f, 100f, 360f, 300f))
        val from = WorldVector(100f, 200f)
        val to = WorldVector(500f, 200f)
        val path = worldFollowPath(from, to, bounds, listOf(sofa), 20f)
        assertTrue(path.size > 1)
        assertEquals(to, path.last())
        (listOf(from) + path).zipWithNext().forEach { (a, b) ->
            (0..100).forEach { assertFalse(circleIntersects(a.lerp(b, it / 100f), 20f, sofa.bounds)) }
        }
        val wall = sofa.copy(bounds = WorldRectangle(240f, 0f, 360f, 400f))
        assertTrue(worldFollowPath(from, to, bounds, listOf(wall), 20f).isEmpty())
    }
}
