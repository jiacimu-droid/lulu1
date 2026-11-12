package com.jiacimu.lulu.data

import org.json.JSONObject

/**
 * The fictional psychological *snapshot* of one newly observed interaction.
 * Unlike CharacterInnerLifeStore, this never searches earlier thoughts or
 * long-lived concerns. Nothing from yesterday can be projected into a message
 * just because the role is still aware of it.
 */
internal object CharacterMomentSnapshot {
    /**
     * Private-idea entries may be authored as text or as {thought,nextAction}.
     * A nextAction belongs to the real action handoff, NEVER to this read-only
     * caption. Older APKs also saved JSONObject.toString() as a caption, sometimes
     * cut in the middle of the following field. Recover only a COMPLETE quoted
     * thought; do not invent the missing tail or display internal JSON.
     */
    internal fun privateIdeaText(value: Any?): String = when (value) {
        is JSONObject -> value.optString("thought").trim()
        is String -> {
            val raw = value.trim()
            if (!raw.startsWith("{")) raw else {
                val parsed = runCatching { JSONObject(raw) }.getOrNull()
                parsed?.optString("thought")?.trim() ?: completedLegacyThought(raw)
            }
        }
        else -> ""
    }

    private fun completedLegacyThought(raw: String): String {
        val key = "\"thought\""
        val at = raw.indexOf(key)
        if (at < 0) return ""
        val colon = raw.indexOf(':', at + key.length)
        if (colon < 0 || raw.substring(at + key.length, colon).isNotBlank()) return ""
        val quoted = raw.substring(colon + 1).trimStart()
        if (!quoted.startsWith('"')) return ""
        var escaped = false
        for (i in 1 until quoted.length) {
            when {
                escaped -> escaped = false
                quoted[i] == '\\' -> escaped = true
                quoted[i] == '"' -> return runCatching {
                    JSONObject("{\"thought\":" + quoted.substring(0, i + 1) + "}")
                        .optString("thought").trim()
                }.getOrDefault("")
            }
        }
        return ""
    }

    /** Read-only compatibility for old presence/history rows. Saved evidence is unchanged. */
    internal fun visibleReflection(raw: String): String = raw.lineSequence()
        .map(::privateIdeaText)
        .filter(String::isNotBlank)
        .joinToString("\n")

    /**
     * Tiny impulses, not a second inner monologue or an explanation of what
     * the character "should" do. The heart-voice is a separate field.
     *
     * Prioritize several chosen, concise wishes. A conflict may remain in the
     * inner-life store without being promoted into a permanent current worry.
     */
    fun reflection(
        innerLife: JSONObject?,
        basis: JSONObject? = null,
        appraisal: JSONObject? = null,
        outward: String = "",
        innerThought: String = "",
    ): String {
        val ideas = mutableListOf<String>()
        val fingerprints = mutableSetOf<String>()
        fun add(raw: String) {
            val cleaned = raw.replace(Regex("\\s+"), " ").trim()
                .removePrefix("我想").let { if (raw.trim().startsWith("我想")) "想$it" else it }
                .trim(' ', '。', '，', '、', '：', ':')
                .trimEnd('，', ',', '、', '：', ':')
            // A concise wish can overlap the full inner monologue without
            // being redundant: A/B are the options, voice is their felt process.
            // Only omit thoughts that merely repeat an outward utterance.
            if (cleaned.isBlank() || CharacterHeartVoicePolicy.sameMeaning(cleaned, outward) ||
                ideas.any { CharacterHeartVoicePolicy.sameMeaning(it, cleaned) }) return
            // Do not repeat near-identical desires under different captions.
            val key = cleaned.lowercase().filter(Char::isLetterOrDigit)
            if (key.isNotBlank() && fingerprints.add(key)) ideas += cleaned
        }
        // Model-facing structured private impulses have priority. A few natural
        // fragments are preferable to a polished explanation of the relationship.
        appraisal?.optJSONArray("privateIdeas")?.let { entries ->
            for (i in 0 until entries.length()) add(privateIdeaText(entries.opt(i)))
        }
        appraisal?.optJSONObject("spontaneousBid")?.let { bid ->
            add(bid.optString("wantedMove"))
        }
        appraisal?.optJSONObject("ownActivity")?.let { intent ->
            val name = when (intent.optString("action")) {
                "reading" -> "想翻翻书"
                "solo_game" -> "想自己玩一局"
                "game_invite" -> "想邀对方一起玩"
                "digital_world" -> "想去数字世界转转"
                else -> ""
            }
            if (intent.optString("stage") in setOf("considering", "decided")) add(name)
        }
        val thoughts = innerLife?.optJSONArray("thoughts")
        if (thoughts != null) for (i in 0 until minOf(thoughts.length(), 3)) {
            val thought = thoughts.optJSONObject(i) ?: continue
            add(thought.optString("thought"))
            if (ideas.size < 2) add(thought.optString("hesitation"))
        }
        // No guesses or abstract risk-analysis notes masquerading as desires.
        // Conflict/uncertainty may inform the role's decision or heart voice,
        // but they are not automatically a separate "idea".
        return ideas.joinToString("\n")
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
        if (messageAnchored) {
            current?.takeIf { it.innerThought.isNotBlank() } ?: history
                .asSequence().filter { it.innerThought.isNotBlank() && current != null &&
                    !it.updatedAt.isAfter(current.updatedAt) &&
                    java.time.Duration.between(it.updatedAt, current.updatedAt).toMinutes() in 0L..5L }
                .maxByOrNull { it.updatedAt }
        } else history.asSequence().sortedByDescending { it.updatedAt }
            .firstOrNull { it.innerThought.isNotBlank() }
            ?: current?.takeIf { it.innerThought.isNotBlank() }

    fun latestReflection(
        current: CompanionPresenceState?,
        history: List<CompanionPresenceState>,
        messageAnchored: Boolean,
    ): CompanionPresenceState? =
        if (messageAnchored) {
            current?.takeIf { it.momentReflection.isNotBlank() } ?: history
                .asSequence().filter { it.momentReflection.isNotBlank() && current != null &&
                    !it.updatedAt.isAfter(current.updatedAt) &&
                    java.time.Duration.between(it.updatedAt, current.updatedAt).toMinutes() in 0L..5L }
                .maxByOrNull { it.updatedAt }
        } else history.asSequence().sortedByDescending { it.updatedAt }
            .firstOrNull { it.momentReflection.isNotBlank() }
            ?: current?.takeIf { it.momentReflection.isNotBlank() }

    fun entries(history: List<CompanionPresenceState>): List<PresenceHistoryMoment> {
        val chronological = history.sortedBy { it.updatedAt }
        return chronological.mapIndexedNotNull { index, state ->
            if (!state.showInHistory) return@mapIndexedNotNull null
            val previous = chronological.getOrNull(index - 1)
            val gesture = state.gesture.takeIf { it.isNotBlank() && it != previous?.gesture }.orEmpty()
            val status = state.statusText.takeIf {
                it.isNotBlank() && it != previous?.statusText &&
                    !PresenceFieldBoundary.repeatsConfirmedAction(it, gesture)
            }.orEmpty()
            val mood = state.mood.takeIf { it.isNotBlank() && it != previous?.mood }.orEmpty()
            if (gesture.isBlank() && status.isBlank() && mood.isBlank() &&
                state.innerThought.isBlank() && state.momentReflection.isBlank()) null
            else PresenceHistoryMoment(state, gesture, mood, status)
        }.asReversed()
    }
}
