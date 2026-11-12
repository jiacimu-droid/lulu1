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
        for (key in listOf("meaning", "uncertainty", "responseAim", "tension", "commonGroundUpdate")) {
            source.optString(key).trim().takeIf(String::isNotBlank)
                ?.let { out.put(key, it) }
        }
        val possibilities = source.optJSONArray("possibleReadings")
        if (possibilities != null) {
            val valid = JSONArray()
            for (i in 0 until minOf(possibilities.length(), 3)) {
                val candidate = possibilities.optString(i).trim()
                if (candidate.isNotBlank() && candidate !in (0 until valid.length()).map(valid::optString)) {
                    valid.put(candidate)
                }
            }
            if (valid.length() > 0) out.put("possibleReadings", valid)
        }
        // These are the role's concise, concurrent wishes (A/B), not a
        // transcript of model reasoning. Persist them with the SAME causal
        // event so the next turn can distinguish a wish from a completed act.
        source.optJSONArray("privateIdeas")?.let { candidates ->
            val ideas = JSONArray()
            for (i in 0 until candidates.length()) {
                val idea = CharacterMomentSnapshot.privateIdeaText(candidates.opt(i))
                if (idea.isNotBlank() && !(0 until ideas.length()).any {
                        CharacterHeartVoicePolicy.sameMeaning(ideas.optString(it), idea)
                    }) ideas.put(idea)
            }
            if (ideas.length() > 0) out.put("privateIdeas", ideas)
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
            // Select psychological content before the existing four-event view.
            // Newest first means a complete newer appraisal is not crowded out by
            // an older one; each event remains an atomic working-set candidate.
            .filter(::hasPrivateContent)
            .sortedByDescending { Instant.parse(it.getString("at")) }
            .take(4)
        if (recent.isEmpty()) return ""
        return buildString {
            appendLine("【主观历史的使用边界】")
            appendLine("以下是本人当时对真实事件的理解、取舍和念头，不代表用户事实，也不代表现在仍然纠结；新证据可以修正它们。不要把多个猜测合并成对用户的断言，也不必把犹豫朗读给对方。")
            recent.forEach { entry ->
                appendLine("【过去主观考虑｜${entry.optString("at")}｜只是心理历史，不代表当前仍然纠结】")
                val appraisal = entry.optJSONObject("appraisal")
                val possibilities = appraisal?.optJSONArray("possibleReadings")
                val ideas = appraisal?.optJSONArray("privateIdeas")
                val basis = entry.optJSONObject("innerThoughtBasis")
                val thought = entry.optString("innerThought")
                append("- 当时选择=").append(entry.optString("selectedAction").take(30))
                entry.optString("evidenceId").takeIf(String::isNotBlank)?.let {
                    append("；原始事件=").append(it)
                }
                appraisal?.optString("responseAim")?.takeIf(String::isNotBlank)?.let {
                    append("；当时希望达到：").append(it)
                }
                appraisal?.optString("interactionMove")?.takeIf(String::isNotBlank)?.let {
                    append("；当时互动取向：").append(it)
                }
                appraisal?.optString("meaning")?.takeIf(String::isNotBlank)?.let {
                    append("；当时理解：").append(it)
                }
                appraisal?.optString("commonGroundUpdate")?.takeIf(String::isNotBlank)?.let {
                    append("；当时修正的语境：").append(it)
                }
                appraisal?.optString("uncertainty")?.takeIf(String::isNotBlank)?.let {
                    append("；当时未确定：").append(it)
                }
                if (ideas != null && ideas.length() > 0) {
                    append("；当时并存的想法：")
                    append((0 until ideas.length()).mapNotNull { i ->
                        CharacterMomentSnapshot.privateIdeaText(ideas.opt(i))
                            .takeIf(String::isNotBlank)
                    }.joinToString("／"))
                }
                if (possibilities != null && possibilities.length() > 0) {
                    append("；当时想到的不同可能：")
                    append((0 until possibilities.length()).map(possibilities::optString).joinToString("／"))
                }
                appraisal?.optString("tension")?.takeIf(String::isNotBlank)?.let {
                    append("；当时纠结：").append(it)
                }
                basis?.optString("focus")?.takeIf(String::isNotBlank)?.let {
                    append("；当时关注点：").append(it)
                }
                basis?.optString("change")?.takeIf(String::isNotBlank)?.let {
                    append("；当时察觉的变化：").append(it)
                }
                basis?.optString("conflict")?.takeIf(String::isNotBlank)?.let {
                    append("；当时冲突：").append(it)
                }
                basis?.optString("unsaidWhy")?.takeIf(String::isNotBlank)?.let {
                    append("；当时没说出的缘由：").append(it)
                }
                basis?.optString("spontaneousTurn")?.takeIf(String::isNotBlank)?.let {
                    append("；当时自然冒出的转念：").append(it)
                }
                if (thought.isNotBlank()) append("；没说出口：").append(thought)
                appendLine()
            }
        }.trim()
    }

    private fun hasPrivateContent(entry: JSONObject): Boolean {
        val appraisal = entry.optJSONObject("appraisal")
        val basis = entry.optJSONObject("innerThoughtBasis")
        return listOf("meaning", "responseAim", "interactionMove", "uncertainty",
            "commonGroundUpdate", "tension")
            .any { appraisal?.optString(it)?.isNotBlank() == true } ||
            (appraisal?.optJSONArray("possibleReadings")?.length() ?: 0) > 0 ||
            (appraisal?.optJSONArray("privateIdeas")?.length() ?: 0) > 0 ||
            listOf("focus", "change", "conflict", "unsaidWhy", "spontaneousTurn")
                .any { basis?.optString(it)?.isNotBlank() == true } ||
            entry.optString("innerThought").isNotBlank()
    }
}
