package com.jiacimu.lulu.data

import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class AutonomousEncounterDirectorTest {
    private val people = listOf("a", "b")
    private fun cue(speaker: String, phase: Int, direction: String = "回应面前的人") =
        JSONObject().put("speakerId", speaker).put("phase", phase).put("direction", direction)
    @Test fun directorCanReturnToEarlierActorAfterTheOtherPersonActuallyChooses() {
        val json = JSONObject().put("angle", "同一件事上的不同兴趣")
            .put("turns", JSONArray().put(cue("a", 1)).put(cue("b", 1))
                .put(cue("a", 2)).put(cue("b", 2)))
        val plan = AutonomousEncounterDirector.parse(json, people)!!
        assertEquals(listOf("a", "b", "a", "b"), plan.turns.map { it.speakerId })
        val public = mutableListOf<AutonomousEncounterScript.Beat>()
        plan.turns.forEachIndexed { index, cue ->
            val context = AutonomousEncounterDirector.publicContext(public, mapOf("a" to "甲", "b" to "乙"))
            if (index == 0) assertEquals("", context)
            if (index == 2) assertTrue(context.contains("乙：我想试试另一种玩法"))
            val text = if (index == 1) "我想试试另一种玩法" else "本人公开回应$index"
            public += AutonomousEncounterScript.Beat(cue.speakerId, cue.phase,
                JSONObject().put("segments", JSONArray().put(JSONObject().put("text", text)))
                    .put("innerThought", "绝不传给另一人的私人想法"))
        }
        assertFalse(AutonomousEncounterDirector.publicContext(public, emptyMap()).contains("私人想法"))
    }
    @Test fun unsupportedRosterDuplicateStageAndBackwardsTimeCannotBecomeAPlan() {
        val bad = listOf(
            JSONArray().put(cue("stranger", 1)),
            JSONArray().put(cue("a", 1)).put(cue("a", 1)),
            JSONArray().put(cue("a", 2)).put(cue("b", 1)),
            JSONArray().put(cue("a", 6)),
            JSONArray().put(cue("a", 1, "")))
        bad.forEach { assertNull(AutonomousEncounterDirector.parse(JSONObject().put("turns", it), people)) }
        assertNotNull(AutonomousEncounterDirector.contractError(JSONObject().put("moment", JSONObject()
            .put("kind", "social_stumble").put("actorId", "a").put("otherId", "outsider")), people))
    }
    @Test fun legacyIdeaDoesNotInventAnExecutionOrder() {
        val plan = AutonomousEncounterDirector.parse(JSONObject().put("angle", "已有话题"), people)!!
        assertNotNull(AutonomousEncounterDirector.contractError(JSONObject().put("angle", "已有话题"), people, requireTurns = true))
        assertTrue(plan.turns.isEmpty())
        assertEquals("已有话题", plan.idea)
    }
}
