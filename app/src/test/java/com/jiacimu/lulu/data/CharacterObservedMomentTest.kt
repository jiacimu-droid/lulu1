package com.jiacimu.lulu.data

import com.jiacimu.lulu.ai.ModelReply
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import java.time.Instant
import java.util.UUID

@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE, sdk = [28])
class CharacterObservedMomentTest {
    @Test fun aSilentSceneCanCarryAConcernIntoTheSameRolesLaterDecisions() {
        val context = RuntimeEnvironment.getApplication()
        val role = "scene-actor-${UUID.randomUUID()}"
        val otherRole = "other-actor-${UUID.randomUUID()}"
        SharedExperienceTimeline.initialize(context)
        CharacterInnerLifeStore.initialize(context)
        val eventId = "real-game-scene-${UUID.randomUUID()}"
        val now = Instant.now()
        SharedExperienceTimeline.record(
            eventId, role, "游戏", "现场观察", "对局已经结束", now,
            triggerExtraction = false, evidenceKind = EventEvidenceKind.Observation,
        )
        val reply = CharacterObservedMoment.detach(role, ModelReply("""{
            "action":"wait",
            "privateMoment":{"appraisal":{"pendingConcerns":[
                {"topicKey":"game_tone","focus":"我还在意刚才说话的语气","whyItMatters":"是我先开了这个玩笑"}
            ]}}
        }"""))
        CharacterObservedMoment.record(otherRole, reply, eventId, "游戏", now = now)
        assertNull(CharacterInnerLifeStore.snapshot(otherRole).optJSONArray("openConcerns"))
        CharacterObservedMoment.record(role, reply, eventId, "游戏", now = now)
        CharacterObservedMoment.record(role, reply, eventId, "游戏", now = now.plusSeconds(1))

        val saved = CharacterInnerLifeStore.snapshot(role).getJSONArray("openConcerns")
        assertEquals(1, saved.length())
        assertTrue(CharacterOpenConcernRuntime.contextForRole(role, saved, now)
            .contains("我还在意刚才说话的语气"))
        assertFalse(reply.text.contains("game_tone"))
    }

    @Test fun aSilentGameDecisionCarriesPrivateStateWithoutExposingItToTheSceneParser() {
        val input = ModelReply(
            """{"type":"wait","text":"","privateMoment":{"innerThought":"我想再看看线索。","innerThoughtBasis":{"focus":"新线索","change":"刚才的猜测站不住了"}}}""",
        )
        val output = CharacterObservedMoment.detach("actor-A", input)
        assertEquals("wait", JSONObject(output.text).getString("type"))
        assertFalse(output.text.contains("我想再看看线索"))
        assertEquals("我想再看看线索。", output.privateMoment?.getString("innerThought"))
        assertEquals("actor-A", output.privateMomentOwner)
    }

    @Test fun invalidOrTruncatedPrivateOutputCannotBecomeSpeechOrPrivateState() {
        val invalid = ModelReply("""{"text":"公开","privateMoment":{"innerThought":"秘密" """)
        val output = CharacterObservedMoment.detach("actor-A", invalid)
        assertEquals("", output.text)
        assertNull(output.privateMoment)

        val complete = ModelReply(
            """{"text":"公开","privateMoment":{"innerThought":"私有"}}""", finishReason = "length",
        )
        val truncated = CharacterObservedMoment.detach("actor-A", complete)
        assertEquals("公开", JSONObject(truncated.text).getString("text"))
        assertNull(truncated.privateMoment)
    }

    @Test fun aCompleteSceneResponseDoesNotNeedToInventAMoodOrThought() {
        val input = ModelReply("""{"action":"keep","indices":[0,2],"say":""}""")
        assertEquals(input, CharacterObservedMoment.detach("actor-B", input))
    }
}
