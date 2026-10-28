package com.jiacimu.lulu.data

import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE, sdk = [28])
class AutonomousEncounterScriptTest {
    private fun actor(id: String, phaseCount: Int): List<AutonomousEncounterScript.Beat> {
        val choices = JSONArray()
        for (phase in 1..phaseCount) {
            choices.put(JSONObject().put("phase", phase)
                .put("speakerId", "forged-person") // Model-supplied owner is ignored.
                .put("segments", JSONArray().put(JSONObject()
                    .put("type", "dialogue").put("text", "$id : phase $phase"))))
        }
        return AutonomousEncounterScript.approvedBeats(id,
            JSONObject().put("choice", "engage").put("reason", "自己想表达")
                .put("beats", choices))
    }

    @Test fun multiSpeakerSceneHasManyTurnsWithoutMoreRoleDecisions() {
        for (count in 2..4) {
            val people = (0 until count).map { "person-$it" }
            val proposals = people.associateWith { actor(it, 4) }
            val scene = AutonomousEncounterScript.order(people, proposals)
            assertEquals(count * 4, scene.size)
            scene.forEachIndexed { index, beat ->
                assertEquals((index / count) + 1, beat.phase)
                assertEquals(people[index % count], beat.speakerId)
                assertEquals(beat.speakerId, beat.turn.getString("speakerId"))
                assertFalse(beat.turn.toString().contains("forged-person"))
            }
        }
    }

    @Test fun quietAndUnapprovedStagesCannotForgePublicSpeech() {
        val quiet = JSONObject().put("choice", "quiet")
            .put("beats", JSONArray().put(JSONObject().put("phase", 1)
                .put("segments", JSONArray().put(JSONObject().put("type", "dialogue")
                    .put("text", "不该出现")))))
        assertTrue(AutonomousEncounterScript.approvedBeats("a", quiet).isEmpty())
        val duplicateAndInvalid = JSONObject().put("choice", "engage")
            .put("beats", JSONArray()
                .put(JSONObject().put("phase", 0).put("segments", JSONArray().put(JSONObject().put("text", "无效"))))
                .put(JSONObject().put("phase", 2).put("segments", JSONArray().put(JSONObject().put("text", "第一次"))))
                .put(JSONObject().put("phase", 2).put("segments", JSONArray().put(JSONObject().put("text", "重复"))))
                .put(JSONObject().put("phase", 6).put("segments", JSONArray().put(JSONObject().put("text", "越界")))))
        val approved = AutonomousEncounterScript.approvedBeats("a", duplicateAndInvalid)
        assertEquals(1, approved.size)
        assertEquals(2, approved.single().phase)
        assertEquals("第一次", approved.single().turn.getJSONArray("segments")
            .getJSONObject(0).getString("text"))
    }

    @Test fun olderSingleTurnFormatStillHasOneApprovedBeat() {
        val old = JSONObject().put("choice", "engage")
            .put("segments", JSONArray().put(JSONObject().put("type", "action")
                .put("text", "朝对方挥了挥手")))
        val beats = AutonomousEncounterScript.approvedBeats("a", old)
        assertEquals(1, beats.size)
        assertEquals("a", beats.single().speakerId)
    }
}
