package com.jiacimu.lulu.data

import org.json.JSONArray
import org.json.JSONObject

/** Format validation only. No interpretation of character intent or replacement choices. */
internal object AutonomousEncounterDecision {
    data class Outcome(val decision: JSONObject?, val original: String, val corrected: String?, val error: String?)

    suspend fun resolve(raw: String, repair: suspend (String) -> String): Outcome {
        val first = ModelStructuredOutput.objectOrNull(raw)
        val error = contractError(first) ?: return Outcome(first, raw, null, null)
        val corrected = repair(error)
        val second = ModelStructuredOutput.objectOrNull(corrected)
        val remaining = contractError(second)
        return Outcome(second.takeIf { remaining == null }, raw, corrected, remaining)
    }

    fun contractError(value: JSONObject?): String? {
        if (value == null) return "输出不是完整 JSON 对象"
        val choice = value.optString("choice")
        if (choice !in setOf("engage", "quiet")) return "choice 须为 engage 或 quiet"
        if (value.optString("reason").isBlank()) return "reason 缺失，须由本人填写"
        fun segments(array: JSONArray?): String? {
            if (array == null || array.length() == 0) return "segments 须为非空数组"
            if (array.length() > 8) return "segments 最多8项"
            for (i in 0 until array.length()) {
                val segment = array.optJSONObject(i) ?: return "segments[$i] 须为对象"
                if (segment.optString("type") !in setOf("dialogue", "action")) return "segments[$i].type 无效"
                val text = segment.opt("text")
                if (text !is String || text.isBlank() || text.length > 900) return "segments[$i].text 须为1至900字符"
            }
            return null
        }
        if (choice == "engage") {
            if (value.has("beats")) {
                val beats = value.optJSONArray("beats") ?: return "beats 须为数组"
                if (beats.length() !in 1..AutonomousEncounterScript.MAX_PHASES) return "beats 须为1至5项"
                val seen = hashSetOf<Int>()
                for (i in 0 until beats.length()) {
                    val beat = beats.optJSONObject(i) ?: return "beats[$i] 须为对象"
                    val phase = beat.opt("phase")
                    if (phase !is Number || phase.toDouble() != phase.toInt().toDouble() ||
                        phase.toInt() !in 1..AutonomousEncounterScript.MAX_PHASES || !seen.add(phase.toInt()))
                        return "beats[$i].phase 须为不重复的1至5整数"
                    segments(beat.optJSONArray("segments"))?.let { return "beats[$i].$it" }
                }
            } else segments(value.optJSONArray("segments"))?.let { return it }
        } else {
            for (field in listOf("beats", "segments")) if (value.has(field)) {
                val array = value.optJSONArray(field) ?: return "$field 须为数组"
                if (array.length() != 0) return "quiet 的 $field 须为空，或由本人重新选择 engage"
            }
        }
        if (value.has("steps")) {
            val steps = value.optJSONArray("steps") ?: return "steps 须为数组"
            if (steps.length() > 3) return "steps 最多3项"
            for (i in 0 until steps.length()) {
                val step = steps.optJSONObject(i) ?: return "steps[$i] 须为对象"
                if (step.optString("tool") !in setOf("send_friend_request", "send_character_message",
                        "create_group_chat", "send_group_message", "plan_personal_activity")) return "steps[$i].tool 不在本场景契约内"
                if (step.optJSONObject("args") == null) return "steps[$i].args 须为对象"
                if (step.has("requiresPreviousSuccess") && step.opt("requiresPreviousSuccess") !is Boolean)
                    return "steps[$i].requiresPreviousSuccess 须为布尔值"
            }
        }
        return null
    }
}
