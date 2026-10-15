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
        chosenUtterance: String = "",
    ): Basis? {
        fun clean(text: String): String = text.replace(Regex("\\s+"), " ").trim().take(240)
        val actionName = canonicalAction(action)
        val conversational = actionName in setOf(
            "reply", "call_reply", "group_reply", "message", "group_message",
            "send_private_message", "send_group_message", "send_private_sticker",
            "send_group_sticker", "kaomoji", "group_kaomoji", "silent",
        )
        val emotion = innerLife?.optJSONObject("emotion")
        val ownIdea = appraisal?.optJSONObject("ownActivity")
        val playful = appraisal?.optJSONObject("spontaneousBid")
        val wanted = if (conversational) clean(appraisal?.optString("responseAim").orEmpty()) else ""
        // An emotion can contain a different urge (e.g. to rest while deciding to
        // publish). Only an explicitly matched impulse can authorize this action.
        val impulse = if (canonicalAction(emotion?.optString("action").orEmpty()) == actionName)
            clean(emotion?.optString("impulse").orEmpty()) else ""
        val ownAction = canonicalAction(ownIdea?.optString("action").orEmpty())
        val activityWhy = if (ownAction == actionName)
            clean(ownIdea?.optString("whyNow").orEmpty()) else ""
        val playfulWhy = if (conversational) clean(playful?.optString("wantedMove").orEmpty()) else ""
        val written = clean(reason)
        val speech = if (actionName in setOf("reply", "group_reply", "call_reply"))
            clean(speechIntent) else ""
        // A role may choose to say one actual, nonempty message without narrating
        // a second private reason field. That concrete same-turn utterance is
        // already an authored conversational choice, not an inferred emotion.
        // Never use this fallback to authorize tool calls, unsolicited phone
        // calls, world changes, posts or fictional / empty messages.
        val chosenWords = if (actionName in setOf(
                "message", "group_message", "send_private_message", "send_group_message",
                "kaomoji", "group_kaomoji",
            )) clean(chosenUtterance) else ""
        val voicedChoice = chosenWords.takeIf {
            it.isNotEmpty() && it.lowercase() !in
                setOf("null", "none", "n/a", "无", "不知道", "未说明", "...")
        }?.let { "本轮自己选择说：$it" }.orEmpty()
        val candidates = listOf(
            "chosen_reason" to written,
            "own_activity" to activityWhy,
            "interaction_aim" to wanted,
            "spontaneous_wish" to playfulWhy,
            "felt_impulse" to impulse,
            "spoken_intent" to speech,
            "chosen_utterance" to voicedChoice,
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

    private fun canonicalAction(action: String): String = when (val name = action.trim().lowercase()) {
        "reading", "read_book" -> "reading"
        "solo_game", "play_solo_game" -> "solo_game"
        "digital_world", "digital_world_action" -> "digital_world"
        "moment", "publish_moment" -> "moment"
        "journal", "write_journal" -> "journal"
        "sticker", "send_private_sticker" -> "send_private_sticker"
        "group_sticker", "send_group_sticker" -> "send_group_sticker"
        else -> name
    }

    /** An action without any subjective reason must wait; silence may be undecided. */
    fun permitsExternalAction(
        action: String,
        reason: String = "",
        appraisal: JSONObject? = null,
        innerLife: JSONObject? = null,
        chosenUtterance: String = "",
    ): Boolean = action.equals("silent", ignoreCase = true) ||
        resolve(action, reason, appraisal, innerLife, chosenUtterance = chosenUtterance) != null
}
