package com.jiacimu.lulu.data

import org.json.JSONObject

/**
 * The character model owns natural-language interpretation. The executor only
 * accepts a structured decision for the CURRENT user turn; no keyword lists,
 * history matching or old silent observations can authorize renewed playback.
 */
internal object CallMediaSemanticBoundary {
    fun musicChoice(modelDecision: JSONObject?, hasNewUserTurn: Boolean): String {
        if (!hasNewUserTurn) return "unchanged"
        return when (modelDecision?.optString("music")) {
            "stop" -> "stop"
            "play" -> "play"
            else -> "unchanged"
        }
    }

    fun confirmedAwake(modelDecision: JSONObject?, hasNewUserTurn: Boolean): Boolean =
        hasNewUserTurn && modelDecision?.optString("wake") == "confirmed"
}
