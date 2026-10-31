package com.jiacimu.lulu.data

/**
 * Only user-defined speech traits and evidence-verified acquired habits belong
 * to the character's style identity. Raw model-generated chat text is already
 * present as conversational history; feeding it back as a style exemplar can
 * amplify accidental purple prose and turn one-off wording into a persona.
 */
internal object CharacterSpeechIdentity {
    fun promptSection(
        characterId: String,
        includeObserved: Boolean = true,
        includeConfigured: Boolean = true,
        includeRelationship: Boolean = true,
        includeCore: Boolean = false,
        query: String = "",
    ): String {
        val initial = CharacterLifeStore.state(characterId).optJSONObject("profile")
            ?.optString("speechHabits").orEmpty().trim()
        val illustrativeDialogues = CharacterLifeStore.state(characterId)
            .optJSONObject("profile")?.optString("dialogueExamples").orEmpty().trim()
        val acquired = if (includeObserved) CharacterDevelopmentRecall.select(
            CharacterDevelopmentStore.active(characterId).filter { it.kind == DevelopmentKind.ExpressionHabit },
            query, limit = 5) else emptyList()

        return buildString {
            appendLine("【这个角色自己的表达倾向，不是必须照着说的台词】")
            appendLine(CharacterExpressionPreferences.promptSection(characterId))
            if (includeRelationship) {
                appendLine(CharacterAddressPreference.promptSection(characterId))
                CharacterRelationshipPreferences.promptSection(characterId, includeCore)
                    .takeIf(String::isNotBlank)?.let(::appendLine)
            }
            // Full-context calls already include this setting in the character profile.
            if (includeConfigured && initial.isNotBlank()) appendLine("用户明确设定的个人语言习惯（优先遵守）：$initial")
            if (illustrativeDialogues.isNotBlank()) {
                appendLine("【只属于本人的假设对话示范｜给表达层看节奏，不是过去真实发生的聊天】")
                // User-authored examples are complete exchanges. Cutting by character
                // count can remove the user's next turn and teach a monologue.
                appendLine(illustrativeDialogues)
                appendLine("这是此角色个人资料里的示范（可能由用户编辑或随角色预置），不是逐字台词、现实经历或对方现在说过的话。")
                appendLine("借鉴的是每一拍怎样接住对方、怎样停下来等她接话；示例中的下一条用户发言属于未来一轮，等她真实回应后再继续。从自己当下的立场、关系和情绪组织措辞，换情景可完全不同，不必每轮复现示例里的语气、行为或气泡数。")
                appendLine("真实用户原话、最近关系变化和本人已经选定的行动优先；绝不把示例中的虚构对象、道具、经历带入当前现实。")
            }
            if (acquired.isNotEmpty()) {
                appendLine("从多次可核验交流中形成的习惯（结合当前反馈判断是否适用）：")
                acquired.forEach { appendLine("- ${it.content}") }
            }
            appendLine("没有明确设定或可靠证据时，不编造固定口头禅；也不把旧消息中偶然出现的华丽修辞当作一贯语气。最近消息用来理解上下文，不是语言模仿范本。")
            appendLine("表达习惯是倾向而不是任务，当前对象、话题和情绪可以让同一个人健谈、随意、安静或认真。临时模仿与真正养成习惯不同，对方明确表示不喜欢的说法不要继续强化。")
        }
    }
}
