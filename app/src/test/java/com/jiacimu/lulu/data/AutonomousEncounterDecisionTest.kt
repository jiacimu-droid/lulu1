package com.jiacimu.lulu.data

import kotlinx.coroutines.runBlocking
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE, sdk = [28])
class AutonomousEncounterDecisionTest {
    private fun engage() = JSONObject().put("choice", "engage").put("reason", "想说一句")
        .put("beats", JSONArray().put(JSONObject().put("phase", 1)
            .put("segments", JSONArray().put(JSONObject().put("type", "dialogue").put("text", "你好呀")))))

    @Test fun validChoiceDoesNotBuyCorrectionAndOwnerCannotBeForged() = runBlocking {
        val input = engage().put("speakerId", "someone-else")
        var calls = 0
        val result = AutonomousEncounterDecision.resolve(input.toString()) { calls++; error("unexpected") }
        assertEquals(0, calls)
        assertNotNull(result.decision)
        val beat = AutonomousEncounterScript.approvedBeats("owner", result.decision!!).single()
        assertEquals("owner", beat.speakerId)
        assertEquals("owner", beat.turn.getString("speakerId"))
    }

    @Test fun malformedPhaseGetsOneCorrectionWithoutInventingAQuietChoice() = runBlocking {
        val raw = engage().apply { getJSONArray("beats").getJSONObject(0).put("phase", 2.5) }.toString()
        var calls = 0
        val result = AutonomousEncounterDecision.resolve(raw) { error ->
            calls++
            assertTrue(error.contains("phase"))
            engage().toString()
        }
        assertEquals(1, calls)
        assertEquals(raw, result.original)
        assertEquals("engage", result.decision!!.getString("choice"))
        assertEquals(1, AutonomousEncounterScript.approvedBeats("a", result.decision!!).size)
    }

    @Test fun secondInvalidOutputRemainsFailureWithBothOriginalsAndNoThirdRequest() = runBlocking {
        val raw = engage().put("steps", "invalid").toString()
        var calls = 0
        val result = AutonomousEncounterDecision.resolve(raw) { calls++; "not JSON" }
        assertEquals(1, calls)
        assertNull(result.decision)
        assertEquals(raw, result.original)
        assertEquals("not JSON", result.corrected)
        assertNotNull(result.error)
    }

    @Test fun validQuietCanKeepPrivateFeelingsAndIndependentSocialSteps() {
        val quiet = JSONObject().put("choice", "quiet").put("reason", "想安静")
            .put("innerThought", "今天能碰见他就很好")
            .put("steps", JSONArray().put(JSONObject().put("tool", "send_friend_request")
                .put("args", JSONObject().put("targetCharacterId", "b"))))
        assertNull(AutonomousEncounterDecision.contractError(quiet))
        quiet.put("beats", engage().getJSONArray("beats"))
        assertNotNull(AutonomousEncounterDecision.contractError(quiet))
    }

    @Test fun nestedInvalidStepsAndDuplicatePhasesAreNotSilentlyDropped() {
        val badArgs = engage().put("steps", JSONArray().put(JSONObject()
            .put("tool", "send_character_message").put("args", "bad")))
        assertTrue(AutonomousEncounterDecision.contractError(badArgs)!!.contains("args"))
        val duplicate = engage()
        duplicate.getJSONArray("beats").put(duplicate.getJSONArray("beats").getJSONObject(0))
        assertTrue(AutonomousEncounterDecision.contractError(duplicate)!!.contains("phase"))
    }
}
