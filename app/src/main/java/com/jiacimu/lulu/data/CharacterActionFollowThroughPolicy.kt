package com.jiacimu.lulu.data

import org.json.JSONObject

/** A desire earns a concrete decision, never an automatically inferred tool call. */
internal object CharacterActionFollowThroughPolicy {
    fun pendingChoices(innerLife: JSONObject?, intention: JSONObject?, afterglow: JSONObject?,
                       thought: String, ownActivity: JSONObject? = null): List<String> {
        val choices = mutableListOf<String>()
        innerLife?.optJSONArray("thoughts")?.let { items ->
            for (i in 0 until items.length()) {
                val item = items.optJSONObject(i) ?: continue
                choices += item.optString("impulse")
            }
        }
        innerLife?.optJSONArray("motives")?.let { items ->
            for (i in 0 until items.length()) {
                val item = items.optJSONObject(i) ?: continue
                if (item.optString("op") !in setOf("pause", "release") &&
                    item.optString("status", "active") == "active" &&
                    (item.optJSONArray("outcomes")?.length() ?: 0) == 0) choices += item.optString("aim")
            }
        }
        if (intention?.optString("disposition") !in setOf("release", "pause"))
            choices += intention?.optString("aim").orEmpty()
        choices += afterglow?.optString("impulse").orEmpty()
        if (ownActivity?.optString("stage") in setOf("considering", "decided")) {
            val action = ownActivity?.optString("action").orEmpty()
            val why = ownActivity?.optString("whyNow").orEmpty()
            if (action.isNotBlank() && why.isNotBlank()) choices += "想做=$action；缘由=$why"
        }
        // Legacy heart voices are a cue for a model review, never a regex→action mapping.
        if (Regex("想|打算|准备|要去|希望|不如|试试").containsMatchIn(thought)) choices += thought
        return choices.map(String::trim).filter {
            it.length >= 4 && !CharacterStaleGoalPolicy.isGenericOrientation(it) &&
                !Regex("^(继续)?(等她|等他|等用户|等待|不催|保持安静|先休息|想休息|想独处)").containsMatchIn(it)
        }.distinct().take(6)
    }

    fun shouldReview(silent: Boolean, choices: List<String>, key: String, previousKey: String,
                     nowMs: Long, previousAtMs: Long): Boolean =
        silent && choices.isNotEmpty() &&
            (key != previousKey || nowMs - previousAtMs >= 5 * 60_000L)
}
