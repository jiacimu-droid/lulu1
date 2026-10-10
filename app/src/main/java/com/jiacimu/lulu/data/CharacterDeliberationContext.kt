package com.jiacimu.lulu.data

import org.json.JSONArray
import org.json.JSONObject
import java.time.Duration
import java.time.Instant

/**
 * A bounded trace of the character's fictional, privately authored alternatives.
 * This is not a reasoning transcript of the language model. It records short,
 * model-supplied subjective conclusions attached to real events, never user facts.
 */
internal object CharacterDeliberationContext {
    fun conciseAppraisal(appraisal: JSONObject?): JSONObject? {
        val source = appraisal ?: return null
        val out = JSONObject()
        for (key in listOf("meaning", "uncertainty", "responseAim", "tension")) {
            source.optString(key).trim().take(180).takeIf(String::isNotBlank)
                ?.let { out.put(key, it) }
        }
        val possibilities = source.optJSONArray("possibleReadings")
        if (possibilities != null) {
            val valid = JSONArray()
            for (i in 0 until minOf(possibilities.length(), 3)) {
                val candidate = possibilities.optString(i).trim().take(140)
                if (candidate.isNotBlank() && candidate !in (0 until valid.length()).map(valid::optString)) {
                    valid.put(candidate)
                }
            }
            if (valid.length() > 0) out.put("possibleReadings", valid)
        }
        return out.takeIf { it.length() > 0 }
    }

    fun summary(transitions: JSONArray?, now: Instant = Instant.now()): String {
        if (transitions == null) return ""
        val recent = (0 until transitions.length()).mapNotNull(transitions::optJSONObject)
            .filter {
                val time = runCatching { Instant.parse(it.optString("at")) }.getOrNull()
                time != null && !time.isAfter(now) && Duration.between(time, now) <= Duration.ofHours(12)
            }
            .takeLast(4)
        if (recent.isEmpty()) return ""
        return buildString {
            appendLine("【近期曾经的主观考虑｜只是心理历史，不代表现在还在纠结】")
            recent.forEach { entry ->
                val appraisal = entry.optJSONObject("appraisal")
                val possibilities = appraisal?.optJSONArray("possibleReadings")
                val tension = appraisal?.optString("tension").orEmpty()
                val basis = entry.optJSONObject("innerThoughtBasis")
                val conflict = basis?.optString("conflict").orEmpty()
                val thought = entry.optString("innerThought")
                val meaningful = (possibilities?.length() ?: 0) > 0 ||
                    tension.isNotBlank() || conflict.isNotBlank() || thought.isNotBlank()
                if (!meaningful) return@forEach
                append("- ").append(entry.optString("selectedAction").take(30))
                if (possibilities != null && possibilities.length() > 0) {
                    append("；当时想到的不同可能：")
                    append((0 until minOf(possibilities.length(), 3))
                        .map(possibilities::optString).joinToString("／").take(240))
                }
                if (tension.isNotBlank()) append("；当时纠结：").append(tension.take(180))
                else if (conflict.isNotBlank()) append("；当时纠结：").append(conflict.take(180))
                if (thought.isNotBlank()) append("；没说出口：").append(thought.take(155))
                appendLine()
            }
            appendLine("以上只是你过去确实产生过的念头；时间已流逝，依据和愿望都可能改变。"
                + "不需要把犹豫朗读给对方，只有真的想回应或做事才采取行动；"
                + "不要把多个猜测合并成对用户的断言。")
        }.trim()
    }
}
