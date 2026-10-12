package com.jiacimu.lulu.data

/** Narrow source-backed surface repair, never inventing an affectionate action. */
internal object RelationshipExpressionGuard {
    fun requiresBufferedSpeech(characterId: String): Boolean {
        val state = CharacterRelationshipPreferences.snapshot(characterId)
        return state.rejectedAddresses.isNotEmpty() || state.preferences.any { it.requiredForms.isNotEmpty() }
    }
    fun apply(characterId: String, text: String, allowNames: Boolean = true): String {
        if (text.isBlank() || text.startsWith("[图片|") || text.startsWith("[游戏邀约|")) return text
        CharacterAddressPreference.refresh(characterId)
        val names = CharacterLifeStore.state(characterId).optJSONObject("socialNames")
        return repair(text, CharacterRelationshipPreferences.snapshot(characterId),
            names?.optString("preferredAddress").orEmpty(), allowNames)
    }

    internal fun repair(text: String, state: RelationshipPreferenceSnapshot, preferred: String = "", allowNames: Boolean = true): String {
        var result = text
        if (allowNames) state.rejectedAddresses.forEach { name ->
            val escaped = Regex.escape(name)
            // Clear direct vocatives only. Quoted names and third-person mentions stay unchanged.
            val vocative = Regex("(^|[。！？!?\\n]|⟪BUBBLE⟫)([ \\t]*)(?:哎[，, ]*)?$escaped(?=[，,：:！!？?~～]|[啊呀哦]?[，,。！!？?]|[，, ]*你|$)")
            result = result.replace(vocative) { match -> match.groupValues[1] + match.groupValues[2] +
                preferred.takeIf { it.isNotBlank() && it !in state.rejectedAddresses }.orEmpty().ifBlank { "你" } }
        }
        state.preferences.filter { it.trigger.isNotBlank() && it.requiredForms.isNotEmpty() }.forEach { preference ->
            val action = preference.trigger.replace(Regex("^(?:你|角色|给我|向我|对我|抛个|抛|发个|发|一个|个)+"), "")
            if (action.length < 2) return@forEach
            val sentencePattern = Regex("[^。！？!?\\n⟪⟫]*${Regex.escape(action)}[^。！？!?\\n⟪⟫]*[。！？!?]?")
            result = result.replace(sentencePattern) { match ->
                val sentence = match.value
                val realAttempt = Regex("(?:给你|向你|对你|送你|送给你|抛给你|抛|发|送|献上).{0,8}${Regex.escape(action)}").containsMatchIn(sentence)
                val hypothetical = Regex("不|没|别|如果|假如|要是|下次|以后|明天|想|准备|打算|要不要|可以吗|是什么|怎么|给她|给他|向她|向他|对她|对他|送她|送他|[“”\"「」]|[？?]").containsMatchIn(sentence)
                if (!realAttempt || hypothetical || preference.requiredForms.any { sentence.contains(it, ignoreCase = true) }) sentence
                else {
                    val clean = sentence.trimEnd()
                    val punctuation = clean.lastOrNull()?.takeIf { it in "。！!" }
                    if (punctuation != null) clean.dropLast(1) + "，" + preference.requiredForms.first() + punctuation
                    else clean + " " + preference.requiredForms.first()
                }
            }
        }
        return result
    }
}
