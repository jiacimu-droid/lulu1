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
    /** A second *choice*, never a forced action: only recover when silence is
     * repeatedly justified by the absence of other people, not personal solitude. */
    fun needsIndependentLifeReview(
        silent: Boolean,
        reason: String,
        recentActions: List<String>,
        hasPendingUserMessage: Boolean,
    ): Boolean {
        if (!silent || hasPendingUserMessage) return false
        val silentCount = recentActions.takeLast(4).takeLastWhile {
            it.equals("silent", true)
        }.size
        if (silentCount < 2) return false
        val explanation = reason.trim()
        // One genuine choice to rest is not a bug. On several consecutive
        // awake windows, reconsider even a habitual "I'll just rest" answer:
        // the fresh model decision may STILL be silent.
        if (silentCount >= 3) return true
        if (Regex("想独处|需要独处|享受安静|专心|想休息|困了|真的不想")
                .containsMatchIn(explanation)) return false
        return explanation.isBlank() || noIncomingMessage.containsMatchIn(explanation) ||
            Regex("没什么可做|没啥可做|就这样待着|先不做什么|暂时没想法").containsMatchIn(explanation)
    }


    /** Salvage only completed speech chosen by the model, never partial tools or private thoughts. */
    fun completedPrivateMessage(raw: String): JSONObject? {
        val clean = raw.substringAfter('{', "").let { "{$it" }.trim()
        if (!clean.startsWith("{")) return null
        // Silence or a tool action must not be converted to an unsolicited message.
        val actionMatch = Regex(""""action"\s*:\s*"(message|kaomoji|reply|private_message)"""")
            .find(clean) ?: return null
        val directText = Regex(""""text"\s*:\s*"((?:[^"\\]|\\.)*)"""").find(clean)
        val bubbleField = if (directText == null)
            Regex(""""bubbles"\s*:\s*\[""").find(clean) else null
        val speechStart = directText?.range?.first ?: bubbleField?.range?.first ?: return null
        if (speechStart > 8_000 || actionMatch.range.first > speechStart) return null
        val prefix = clean.substring(0, speechStart)
        // Reject a decision embedded inside a thought, tool args, or nested object.
        if (prefix.drop(1).contains('{')) return null
        if (listOf("innerLife", "innerThought", "appraisal", "alternatives",
                "args", "tool", "journal", "worldAction")
            .any { prefix.contains("\"$it\"") }) return null
        val text = if (directText != null) {
            runCatching { JSONObject("{\"text\":\"" + directText.groupValues[1] + "\"}")
                .optString("text").trim() }.getOrNull().orEmpty()
        } else {
            // A completed bubbles array is already authored speech even if the
            // model later ran out of tokens writing optional innerLife fields.
            completedBubbleArray(clean, clean.indexOf('[', bubbleField!!.range.first))
        }
        if (text.isBlank()) return null
        return JSONObject().put("action", actionMatch.groupValues[1])
            .put("text", text.take(1_500))
            .put("reason", "保留已经完整生成的回复文字，不执行未完成的额外字段")
    }

    private fun completedBubbleArray(raw: String, start: Int): String {
        if (start < 0) return ""
        var depth = 0
        var inString = false
        var escaped = false
        for (i in start until raw.length) {
            val c = raw[i]
            if (inString) {
                if (escaped) { escaped = false; continue }
                when (c) {
                    '\\' -> escaped = true
                    '"' -> inString = false
                }
                continue
            }
            when (c) {
                '"' -> inString = true
                '[' -> depth++
                ']' -> {
                    depth--
                    if (depth == 0) {
                        val array = runCatching {
                            org.json.JSONArray(raw.substring(start, i + 1))
                        }.getOrNull() ?: return ""
                        return (0 until minOf(8, array.length())).mapNotNull { index ->
                            val bubble = array.opt(index)
                            val words = if (bubble is JSONObject) bubble.optString("text")
                                else if (bubble is String) bubble else ""
                            words.trim().takeIf(String::isNotBlank)
                        }.joinToString("\n")
                    }
                }
            }
        }
        return ""
    }
    fun choose(json: JSONObject): JSONObject {
        if (!json.optString("action").equals("silent", true)) return json
        if (Regex("想独处|需要独处|想休息|好好休息|困了|真的不想").containsMatchIn(json.optString("reason"))) return json
        val alt = json.optJSONObject("selfInitiatedAlternative") ?: return json
        if (!alt.optBoolean("personallyWanted") ||
            !CharacterSelfActivityRuntime.hasRealReason(alt.optString("whyNow"))) return json
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
        listOf("curiosity", "relationshipInitiative", "innerLife", "intention", "motiveId",
            "onlineDecision", "appraisal", "afterglow", "innerThought", "innerThoughtBasis").forEach { key ->
            if (!choice.has(key) && json.has(key)) choice.put(key, json.opt(key))
        }
        return choice
    }
}
