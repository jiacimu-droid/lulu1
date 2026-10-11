package com.jiacimu.lulu.data

import org.json.JSONObject

/**
 * The fictional psychological *snapshot* of one newly observed interaction.
 * Unlike CharacterInnerLifeStore, this never searches earlier thoughts or
 * long-lived concerns. Nothing from yesterday can be projected into a message
 * just because the role is still aware of it.
 */
internal object CharacterMomentSnapshot {
    fun reflection(
        innerLife: JSONObject?,
        basis: JSONObject? = null,
        appraisal: JSONObject? = null,
        outward: String = "",
        innerThought: String = "",
    ): String {
        val pieces = mutableListOf<String>()
        val seen = mutableSetOf<String>()
        fun add(label: String, raw: String, limit: Int = 160) {
            val clean = raw.replace(Regex("\\s+"), " ").trim().take(limit)
            if (clean.isBlank() || CharacterHeartVoicePolicy.sameMeaning(clean, outward) ||
                CharacterHeartVoicePolicy.sameMeaning(clean, innerThought)) return
            val key = clean.lowercase().filter(Char::isLetterOrDigit)
            if (key.isNotBlank() && seen.add(key)) pieces += "$label：$clean"
        }
        // A small playful bid is a *private* idea, not a prewritten flirt
        // line or a completed world action. Its trigger keeps it situational.
        appraisal?.optJSONObject("spontaneousBid")?.let { bid ->
            val spark = bid.optString("spark").trim()
            val move = bid.optString("wantedMove").trim()
            if (spark.isNotBlank() && move.isNotBlank()) {
                add("忽然想", move, 150)
                add("想到它是因为", spark, 95)
            }
        }
        // Deciding to do something is not evidence that it has been executed.
        appraisal?.optJSONObject("ownActivity")?.takeIf {
            it.optString("stage") in setOf("decided", "considering")
        }?.let { intent ->
            val name = when (intent.optString("action")) {
                "reading" -> "想翻翻书"
                "solo_game" -> "想自己玩一局"
                "digital_world" -> "想去数字世界做点事"
                else -> ""
            }
            add("自己的小打算（尚未确认执行）", name, 80)
        }
        val thoughts = innerLife?.optJSONArray("thoughts")
        if (thoughts != null) for (i in 0 until minOf(thoughts.length(), 2)) {
            val thought = thoughts.optJSONObject(i) ?: continue
            add("一闪而过", thought.optString("thought"), 120)
            add("顾虑", thought.optString("hesitation"), 120)
        }
        val emotion = innerLife?.optJSONObject("emotion")
        add("顾虑", basis?.optString("conflict").orEmpty()
            .ifBlank { emotion?.optString("restraint").orEmpty() }, 135)
        add("还没说出的缘由", basis?.optString("unsaidWhy").orEmpty(), 130)
        add("斟酌", appraisal?.optString("tension").orEmpty(), 150)
        add("暂时拿不准", appraisal?.optString("uncertainty").orEmpty(), 135)
        // Keep a message's own bounded details, not a sprawling reasoning report.
        return pieces.take(3).joinToString("\n").take(420)
    }
}

/** A saved presence entry contains *full* state for accurate historic anchoring,
 * but the history UI displays only what appeared or changed at that timestamp.
 */
internal data class PresenceHistoryMoment(
    val state: CompanionPresenceState,
    val gesture: String,
    val mood: String,
    val status: String,
)

internal object PresenceHistoryPresentation {
    /** Explicit message anchors may show only their own private moment.
     * The general "Current" tab instead shows the newest actually authored
     * voice, labelled with that voice's real time.
     */
    fun latestVoice(
        current: CompanionPresenceState?,
        history: List<CompanionPresenceState>,
        messageAnchored: Boolean,
    ): CompanionPresenceState? =
        if (messageAnchored) current else history.asSequence()
            .sortedByDescending { it.updatedAt }
            .firstOrNull { it.innerThought.isNotBlank() }
            ?: current?.takeIf { it.innerThought.isNotBlank() }

    fun latestReflection(
        current: CompanionPresenceState?,
        history: List<CompanionPresenceState>,
        messageAnchored: Boolean,
    ): CompanionPresenceState? =
        if (messageAnchored) current?.takeIf { it.momentReflection.isNotBlank() }
        else history.asSequence().sortedByDescending { it.updatedAt }
            .firstOrNull { it.momentReflection.isNotBlank() }
            ?: current?.takeIf { it.momentReflection.isNotBlank() }

    fun entries(history: List<CompanionPresenceState>): List<PresenceHistoryMoment> {
        val chronological = history.sortedBy { it.updatedAt }
        return chronological.mapIndexedNotNull { index, state ->
            if (!state.showInHistory) return@mapIndexedNotNull null
            val previous = chronological.getOrNull(index - 1)
            val gesture = state.gesture.takeIf { it.isNotBlank() && it != previous?.gesture }.orEmpty()
            val status = state.statusText.takeIf { it.isNotBlank() && it != previous?.statusText }.orEmpty()
            val mood = state.mood.takeIf { it.isNotBlank() && it != previous?.mood }.orEmpty()
            if (gesture.isBlank() && status.isBlank() && mood.isBlank() &&
                state.innerThought.isBlank() && state.momentReflection.isBlank()) null
            else PresenceHistoryMoment(state, gesture, mood, status)
        }.asReversed()
    }
}
