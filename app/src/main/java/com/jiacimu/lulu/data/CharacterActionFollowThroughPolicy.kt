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
                if (CharacterMotiveState.isActive(item)) {
                    val aim = item.optString("aim").trim()
                    val outcomes = item.optJSONArray("outcomes")
                    val last = outcomes?.optJSONObject(outcomes.length() - 1)
                    // A failed tool receipt never resolves a still-active goal.
                    // But a successful attempt is not a fresh untried impulse:
                    // the full motive remains in normal continuity context.
                    val failed = item.optString("lastAttemptOutcome") == "needs_review" ||
                        (last?.has("success") == true && !last.optBoolean("success"))
                    val consecutiveFailures = (0 until (outcomes?.length() ?: 0))
                        .reversed()
                        .takeWhile { index ->
                            outcomes?.optJSONObject(index)?.let { receipt ->
                                receipt.has("success") && !receipt.optBoolean("success")
                            } == true
                        }.count()
                    if (aim.isNotBlank() && last == null) {
                        choices += aim
                    } else if (aim.isNotBlank() && failed && consecutiveFailures < 3) {
                        val outcome = last?.optString("summary").orEmpty().trim().take(110)
                        choices += "未完成的愿望：$aim；上次尝试失败：$outcome；请自己选择调整办法、暂缓或放弃，不要当作已经完成"
                    }
                }
            }
        }
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
        }.distinct().take(5) + listOfNotNull(CharacterIntentionContinuation.cue(intention?.takeUnless { it.optString("disposition") in setOf("pause", "release") }))
    }

    fun shouldReview(silent: Boolean, choices: List<String>, key: String, previousKey: String,
                     nowMs: Long, previousAtMs: Long): Boolean =
        silent && choices.isNotEmpty() &&
            (key != previousKey || nowMs - previousAtMs >= 5 * 60_000L)
}
