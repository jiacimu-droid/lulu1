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
