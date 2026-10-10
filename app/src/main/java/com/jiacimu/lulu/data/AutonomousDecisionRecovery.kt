package com.jiacimu.lulu.data

import org.json.JSONObject

/**
 * Very narrow recovery of a model's default 'nothing to do' response.
 * Never overrides genuine silence, and never synthesizes external actions.
 * The model must itself supply a specific alternative plus an explicit reason.
 */
internal object AutonomousDecisionRecovery {
    private val noIncomingMessage = Regex(
        "没有(新消息|用户新消息|人找|人来找|用户消息|人说话|人联系)|没人(找|说话|联系)|等(她|他|用户|消息)|没(有)?事(情)?(可|要|能)做"
    )
    private val ownLifeActions = setOf("solo_game", "reading", "digital_world", "journal")
    fun choose(json: JSONObject): JSONObject {
        if (!json.optString("action").equals("silent", true)) return json
        if (!noIncomingMessage.containsMatchIn(json.optString("reason"))) return json
        val alt = json.optJSONObject("selfInitiatedAlternative") ?: return json
        if (!alt.optBoolean("personallyWanted") || alt.optString("whyNow").trim().length < 5) return json
        val action = alt.optString("action").lowercase()
        if (action !in ownLifeActions) return json
        val validParameters = when (action) {
            "reading" -> alt.optString("readingBookId").isNotBlank()
            "solo_game" -> alt.optString("gameId") == "memory_match"
            "digital_world" -> alt.optString("worldAction").isNotBlank()
            "journal" -> alt.optString("journalContent").isNotBlank()
            else -> false
        }
        if (!validParameters) return json
        // Preserve the original inner motive even when the model puts action args in its
        // alternative. Without this, an actual exploration could lose its curiosity thread.
        val choice = JSONObject(alt.toString()).put("reason", alt.optString("whyNow"))
        listOf("curiosity", "innerLife", "intention", "motiveId").forEach { key ->
            if (!choice.has(key) && json.has(key)) choice.put(key, json.opt(key))
        }
        return choice
    }
}
