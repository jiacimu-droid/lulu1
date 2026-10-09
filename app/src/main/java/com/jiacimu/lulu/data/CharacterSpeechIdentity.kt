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
    ): String {
        val initial = CharacterLifeStore.state(characterId).optJSONObject("profile")
            ?.optString("speechHabits").orEmpty().trim()
        val acquired = if (includeObserved) CharacterDevelopmentStore.active(characterId)
            .filter { it.kind == DevelopmentKind.ExpressionHabit }.takeLast(5) else emptyList()

        return buildString {
            appendLine("【这个角色自己的表达倾向，不是必须照着说的台词】")
            if (initial.isNotBlank()) appendLine("用户明确设定的个人语言习惯（优先遵守）：$initial")
            if (acquired.isNotEmpty()) {
                appendLine("从多次可核验交流中形成的习惯（结合当前反馈判断是否适用）：")
                acquired.forEach { appendLine("- ${it.content}") }
            }
            appendLine("没有明确设定或可靠证据时，不编造固定口头禅；也不把旧消息中偶然出现的华丽修辞当作一贯语气。最近消息用来理解上下文，不是语言模仿范本。")
            appendLine("表达习惯是倾向而不是任务，当前对象、话题和情绪可以让同一个人健谈、随意、安静或认真。临时模仿与真正养成习惯不同，对方明确表示不喜欢的说法不要继续强化。")
        }
    }
}
