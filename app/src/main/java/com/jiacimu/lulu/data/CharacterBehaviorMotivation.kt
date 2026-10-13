package com.jiacimu.lulu.data

import org.json.JSONObject

/**
 * One short, character-authored motivation contract for every interaction surface.
 *
 * A choice can be as small as "有点闷，想出去转转". It does NOT require a new
 * emotion, a long explanation, or an innerThought visible to the user.
 * Never invent a reason retrospectively from an executor's successful result.
 */
internal object CharacterBehaviorMotivation {
    data class Basis(
        val reason: String,
        val source: String,
        val understanding: String,
        val feeling: String,
        val wanted: String,
    ) {
        fun trace(): JSONObject = JSONObject()
            .put("reason", reason)
            .put("source", source)
            .put("understanding", understanding)
            .put("feeling", feeling)
            .put("wanted", wanted)
    }

    fun resolve(
        action: String,
        reason: String = "",
        appraisal: JSONObject? = null,
        innerLife: JSONObject? = null,
        speechIntent: String = "",
    ): Basis? {
        fun clean(text: String): String = text.replace(Regex("\\s+"), " ").trim().take(240)
        val emotion = innerLife?.optJSONObject("emotion")
        val ownIdea = appraisal?.optJSONObject("ownActivity")
        val playful = appraisal?.optJSONObject("spontaneousBid")
        val wanted = clean(appraisal?.optString("responseAim").orEmpty())
        val impulse = clean(emotion?.optString("impulse").orEmpty())
        val activityWhy = clean(ownIdea?.optString("whyNow").orEmpty())
        val playfulWhy = clean(playful?.optString("wantedMove").orEmpty())
        val written = clean(reason)
        val speech = if (action.lowercase() in setOf("reply", "group_reply", "call_reply"))
            clean(speechIntent) else ""
        val candidates = listOf(
            "chosen_reason" to written,
            "own_activity" to activityWhy,
            "interaction_aim" to wanted,
            "spontaneous_wish" to playfulWhy,
            "felt_impulse" to impulse,
            "spoken_intent" to speech,
        )
        val selected = candidates.firstOrNull { (_, text) ->
            text.isNotBlank() && text.lowercase() !in setOf("null", "none", "n/a", "无", "不知道", "未说明")
        } ?: return null
        return Basis(
            reason = selected.second,
            source = selected.first,
            understanding = clean(appraisal?.optString("meaning").orEmpty()),
            feeling = clean(emotion?.optString("feeling").orEmpty()),
            wanted = wanted.ifBlank { activityWhy.ifBlank { playfulWhy.ifBlank { impulse.ifBlank { speech } } } },
        )
    }

    /** An action without any subjective reason must wait; silence may be undecided. */
    fun permitsExternalAction(
        action: String,
        reason: String = "",
        appraisal: JSONObject? = null,
        innerLife: JSONObject? = null,
    ): Boolean = action.equals("silent", ignoreCase = true) ||
        resolve(action, reason, appraisal, innerLife) != null
}
