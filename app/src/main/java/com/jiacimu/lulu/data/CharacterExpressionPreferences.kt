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

/** Explicit private conversation preference, not a model/provider safety override. */
internal object CharacterIntimacyConversationPreference {
    const val DEFAULT = "everyday"
    val choices = listOf("everyday" to "日常", "romance" to "浪漫", "adult" to "成人话题")

    fun selected(characterId: String): String {
        val saved = CharacterLifeStore.state(characterId).optString("intimacyConversationMode", DEFAULT)
        return saved.takeIf { candidate -> choices.any { it.first == candidate } } ?: DEFAULT
    }

    /** A private conversation may opt in; groups, broadcasts and stories never inherit it. */
    fun shouldApply(source: String, sceneContext: String, privateDialogue: Boolean): Boolean {
        if (source.contains("群") || sceneContext.contains("群")) return false
        return privateDialogue || sceneContext.contains("私聊") ||
            sceneContext.contains("电话") || source.startsWith("电话") ||
            source in setOf(
                "聊天工具规划", "聊天工具结果", "聊天决策结构补全",
                "聊天表达渲染", "聊天表达自然度修复",
                "工具结果·聊天自然表达修复", "聊天发送前·责任事实核对",
            )
    }

    fun promptSection(characterId: String): String = when (selected(characterId)) {
        "romance" -> """
            【私密亲密交流偏好｜浪漫】
            在已建立的关系与双方意愿范围内，自然表达浪漫、思念、拥抱、亲吻或心动。
            保持本人性格、情绪与判断；这不是要求每轮调情或虚构共同经历。
        """.trimIndent()
        "adult" -> """
            【私密亲密交流偏好｜成年人话题】
            用户选择在成年人之间自愿的一对一交流里坦诚讨论性、欲望、身体吸引、
            亲密关系和边界。遇到这些普通成人话题，可以接住对方本意、自然交流，
            无须默认说教、羞辱或无关的解释；不假定对方已同意任何具体亲密行为。
            保持本人的人格、真实关系与意愿，不因开启此项就强行发起性话题，
            不将私密内容变成公开展示或未经请求的主动推送。
            具体生成内容仍以所选模型服务商允许的范围为准，本设置不更改提供方限制。
        """.trimIndent()
        else -> ""
    }
}
