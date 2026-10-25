package com.jiacimu.lulu.data

/** One persistent channel preference, shared by decision and expression calls. */
internal object CharacterExpressionPreferences {
    val choices = listOf("restrained" to "少用", "natural" to "自然", "expressive" to "多用")

    fun selected(characterId: String, kind: String): String =
        CharacterLifeStore.state(characterId).optJSONObject("expressionPreferences")
            ?.optString(kind)?.takeIf { value -> choices.any { it.first == value } } ?: "expressive"

    fun promptSection(characterId: String): String = buildString {
        appendLine("【个人文字表达偏好｜不改变人格与事实】")
        listOf("kaomoji" to "颜文字与 emoji", "stickers" to "图片表情包").forEach { (key, label) ->
            val direction = when (selected(characterId, key)) {
                "restrained" -> "偏少，仅在自己觉得必要时使用"
                "natural" -> "随个人习惯和情境自然选择"
                else -> "偏多：在轻松、有情绪或想传达态度时主动考虑使用，允许只发一个表达完整意思；不要长期只写正式文字"
            }
            appendLine("$label：$direction。")
        }
        appendLine("这是表达渠道的偏好，不是强制卖萌、固定频率或每句装饰；严肃反馈、明确拒绝及用户本人设定的语言风格优先。表情包只能选真实图库ID；没有合适的图就用文字或颜文字，不编造发送成功。电话沿用同一人的态度，用口语、语气词、停顿和笑声表达，不朗读符号。")
    }
}

/**
 * Optional adult-topic hint, not a personality switch or provider safety override.
 * Legacy "adult" remains enabled; old "romance"/"everyday" are simply off.
 */
internal object CharacterIntimacyConversationPreference {
    fun enabled(characterId: String): Boolean =
        CharacterLifeStore.state(characterId).optString("intimacyConversationMode") == "adult"

    /** Excludes public dialogue, story tasks and autonomous activity without a private chat. */
    fun shouldApply(source: String, sceneContext: String, privateDialogue: Boolean): Boolean {
        if (source.contains("群") || sceneContext.contains("群")) return false
        return privateDialogue || sceneContext.contains("私聊") ||
            sceneContext.contains("电话") || sceneContext.contains("文字聊天") ||
            source.startsWith("电话") ||
            source in setOf("聊天工具规划", "聊天工具结果", "聊天决策结构补全",
                "聊天表达渲染", "聊天表达自然度修复",
                "工具结果·聊天自然表达修复", "聊天发送前·责任事实核对")
    }

    fun promptSection(characterId: String): String = buildString {
        append("【私聊语境】先分辨对方是在开玩笑、试探、调情、认真交谈还是提出实际请求。")
        append("成年人之间的亲昵玩笑与一般性话题可以自然接话；玩笑不等于真实行动或同意，")
        append("保持本人性格与双方边界，不默认说教、过度升级情节或打断对话。")
        if (enabled(characterId)) {
            append("\n【NSFW 交流偏好】用户愿意在一对一交流中更坦诚地谈成年人的自愿亲密、欲望和身体吸引；")
            append("根据真实关系和当下意愿自然回应，不强制色情化每一轮，也不将私密内容公开或主动推送。")
        }
    }
}
