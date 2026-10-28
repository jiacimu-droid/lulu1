package com.jiacimu.lulu.data

import org.json.JSONArray
import org.json.JSONObject

/**
 * One role-owned decision may author several PUBLIC beats. The scheduler only
 * interleaves those approved beats; it never invents anybody's next reply.
 * Later speakers see prior proposals, but earlier ones do not magically know
 * later characters' unspoken thoughts.
 */
internal object AutonomousEncounterScript {
    const val MAX_PHASES = 5
    const val MAX_PARTICIPANTS = 4

    data class Beat(val speakerId: String, val phase: Int, val turn: JSONObject)

    fun approvedBeats(speakerId: String, choice: JSONObject): List<Beat> {
        if (choice.optString("choice") != "engage") return emptyList()
        val result = mutableListOf<Beat>()
        val input = choice.optJSONArray("beats")
        if (input != null) {
            val seen = mutableSetOf<Int>()
            for (index in 0 until minOf(input.length(), MAX_PHASES)) {
                val item = input.optJSONObject(index) ?: continue
                val phase = item.optInt("phase", 0)
                if (phase !in 1..MAX_PHASES || !seen.add(phase)) continue
                if (item.optJSONArray("segments")?.length() == 0) continue
                result += Beat(speakerId, phase, JSONObject()
                    .put("speakerId", speakerId)
                    .put("segments", item.optJSONArray("segments") ?: JSONArray())
                    .put("reason", choice.optString("reason"))
                    .put("appraisal", choice.optJSONObject("appraisal"))
                    .put("innerLife", choice.optJSONObject("innerLife")))
            }
        } else if ((choice.optJSONArray("segments")?.length() ?: 0) > 0) {
            // Older models may still return the original one-beat format.
            result += Beat(speakerId, 1, JSONObject(choice.toString()).put("speakerId", speakerId))
        }
        return result
    }

    fun order(participants: List<String>, proposals: Map<String, List<Beat>>): List<Beat> {
        val roster = participants.distinct().take(MAX_PARTICIPANTS)
        return (1..MAX_PHASES).flatMap { phase ->
            roster.mapNotNull { id ->
                proposals[id].orEmpty().firstOrNull { it.phase == phase && it.speakerId == id }
            }
        }
    }
}
