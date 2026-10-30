package com.jiacimu.lulu

import org.json.JSONObject

/** Structural coverage only. The same character chooses every actual clip. */
internal object Live2DSpokenMotionContract {
    fun errors(text: String, chosen: JSONObject?, motions: List<Pair<String, Int>>): List<String> {
        if (text.isBlank() || motions.isEmpty()) return emptyList()
        if (chosen?.has("speech") == true) return Live2DDialoguePlan.errors(text, chosen, motions)
        val parts = CallReplyStream().finish(text)
        val beats = chosen?.optJSONArray("beats")
        val anchors = buildList {
            if (beats != null) for (i in 0 until minOf(beats.length(), 16)) {
                val beat = beats.optJSONObject(i) ?: continue
                val motion = beat.optJSONObject("motion") ?: continue
                if ((motion.optString("group") to motion.optInt("index", -1)) !in motions) continue
                // Only sentence starts can cover a sentence, not a later gesture.
                if (beat.optInt("atMs", 0) != 0) continue
                beat.optString("anchor").takeIf { it.isNotBlank() && it.length <= 60 }?.let(::add)
            }
        }
        val covered = Live2DSpeechAnchorPlan.bindExistingSegments(parts, anchors).values.toMutableSet()
        chosen?.optJSONObject("motion")?.let { motion ->
            if ((motion.optString("group") to motion.optInt("index", -1)) in motions) covered.add(0)
        }
        return parts.indices.filter { index ->
            VoicePerformance.plain(parts[index]).any(Char::isLetterOrDigit) && index !in covered
        }.map { index -> "第${index + 1}个发声片段缺少有效句首 Motion3：${parts[index]}" }
    }

    data class Resolution(val chosen: JSONObject?, val errors: List<String>, val repaired: Boolean)

    /** A bounded contract-only correction, never a new dialogue/tool decision.
     * The second attempt is only for a complete but invalid first correction,
     * and receives the exact rejected proposal and its new validation errors. */
    suspend fun resolve(text: String, chosen: JSONObject?, motions: List<Pair<String, Int>>,
        repair: suspend (List<String>, JSONObject?) -> JSONObject?): Resolution {
        var missing = errors(text, chosen, motions)
        if (missing.isEmpty()) return Resolution(chosen, emptyList(), false)
        var proposal = chosen
        repeat(2) {
            val corrected = repair(missing, proposal) ?: return Resolution(
                chosen?.takeIf { original -> !original.has("speech") ||
                    Live2DDialoguePlan.parts(original)?.joinToString("") { it.text } == text },
                missing, false)
            val remaining = errors(text, corrected, motions)
            if (remaining.isEmpty()) return Resolution(corrected, emptyList(), true)
            proposal = corrected
            missing = remaining
        }
        // Do not publish a guessed replacement Motion3. A failed correction
        // remains observable while the ordinary spoken answer survives.
        return Resolution(chosen?.takeIf { original -> !original.has("speech") ||
            Live2DDialoguePlan.parts(original)?.joinToString("") { it.text } == text },
            missing, false)
    }
}
