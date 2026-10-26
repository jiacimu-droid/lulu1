package com.jiacimu.lulu.data

import org.json.JSONObject

/** A desire earns a concrete decision, never an automatically inferred tool call. */
internal object CharacterActionFollowThroughPolicy {
    fun pendingChoices(innerLife: JSONObject?, intention: JSONObject?, afterglow: JSONObject?,
                       thought: String, ownActivity: JSONObject? = null): List<String> {
        val freshImpulses = mutableListOf<String>()
        val persistentWishes = mutableListOf<String>()
        innerLife?.optJSONArray("thoughts")?.let { items ->
            for (i in 0 until items.length()) {
                val item = items.optJSONObject(i) ?: continue
                freshImpulses += item.optString("impulse")
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
                    if (!CharacterMotiveProgress.isDue(item)) continue
                    val plan = CharacterMotiveProgress.brief(item)
                    if (aim.isNotBlank() && last == null) {
                        persistentWishes += if (plan.isBlank()) aim else "$aim；$plan；仅供本人再考虑"
                    } else if (aim.isNotBlank() && failed && consecutiveFailures < 3) {
                        val outcome = last?.optString("summary").orEmpty().trim().take(110)
                        persistentWishes += "未完成的愿望：$aim；上次尝试失败：$outcome；$plan；请自己选择调整办法、暂缓或放弃，不要当作已经完成"
                    } else if (aim.isNotBlank() && last?.optBoolean("success") == true &&
                        item.optString("nextStep").isNotBlank() &&
                        item.optString("reviewAfter").isNotBlank()) {
                        persistentWishes += "已有一次实际进展的愿望：$aim；$plan；一次成功不代表整件事做完，请自己决定是否继续"
                    }
                }
            }
        }
        val otherImpulses = mutableListOf<String>()
        otherImpulses += afterglow?.optString("impulse").orEmpty()
        if (ownActivity?.optString("stage") in setOf("considering", "decided")) {
            val action = ownActivity?.optString("action").orEmpty()
            val why = ownActivity?.optString("whyNow").orEmpty()
            if (action.isNotBlank() && why.isNotBlank()) otherImpulses += "想做=$action；缘由=$why"
        }
        otherImpulses += thought
        // A burst of fresh thoughts used to consume all five follow-through
        // slots before any saved wish reached the same-character review.
        // Keep room for both; this changes only what the model can consider,
        // never which action it chooses. Existing failure and waiting guards
        // still decide which wishes may enter this *optional* review.
        val choices = (freshImpulses.take(1) + persistentWishes.take(2) +
            otherImpulses + freshImpulses.drop(1) + persistentWishes.drop(2))
            .map(String::trim).filter(String::isNotBlank).distinct().take(5)
        return choices + listOfNotNull(CharacterIntentionContinuation.cue(
            intention?.takeUnless { it.optString("disposition") in setOf("pause", "release") }
        ))
    }

    /**
     * Optional same-character action review must see the SAME persisted
     * attention worklist as the paid primary decision. It may additionally
     * see the just-authored impulse and the legacy intention, but it must not
     * rank a second, incompatible queue of old motives/share/curiosity.
     * Merely showing a cue cannot choose or execute an action.
     */
    fun unifiedReviewChoices(
        immediate: List<String>, visibleUnfinished: List<String>,
        legacyIntention: String? = null, limit: Int = 8,
    ): List<String> {
        if (limit <= 0) return emptyList()
        return (immediate.take(1) + visibleUnfinished +
            listOfNotNull(legacyIntention) + immediate.drop(1))
            .map(String::trim).filter(String::isNotBlank).distinct().take(limit)
    }

    fun shouldReview(silent: Boolean, choices: List<String>, key: String, previousKey: String,
                     nowMs: Long, previousAtMs: Long): Boolean =
        silent && choices.isNotEmpty() &&
            (key != previousKey || nowMs - previousAtMs >= 5 * 60_000L)
}
