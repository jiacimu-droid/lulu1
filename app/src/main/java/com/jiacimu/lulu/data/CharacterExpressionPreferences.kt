package com.jiacimu.lulu.data

/** One persistent channel preference, shared by decision and expression calls. */
internal object CharacterExpressionPreferences {
    val choices = listOf("restrained" to "少用", "natural" to "自然", "expressive" to "多用")

    fun selected(characterId: String, kind: String): String =
        CharacterLifeStore.state(characterId).optJSONObject("expressionPreferences")
            ?.optString(kind)?.takeIf { value -> choices.any { it.first == value } } ?: "expressive"

    fun promptSection(characterId: String): String = buildString {
        appendLine("【个人文字表达偏好｜不改变人格与事实】")
        val symbols = when (selected(characterId, "kaomoji")) {
            "restrained" -> "少用，在本人觉得必要时用"
            "natural" -> "随本人语感用"
            else -> "多用，随本人想表达的情绪或语气自然用"
        }
        val stickers = when (selected(characterId, "stickers")) {
            "restrained" -> "少用，遇到一张特别贴切的图再发"
            "natural" -> "随情境与本人习惯选图"
            else -> "多用。有贴切的真图时，优先用它表达这一拍的反应，可以单发，也可以跟在自己想说的话后面。关心、放松、吐槽、亲近或分享都可以有合适的图。"
        }
        appendLine("颜文字与 emoji：$symbols。")
        appendLine("图片表情包：$stickers")
        appendLine("用法跟着本人的个性及真实关系。用户的明确要求优先，需要认真回答时把话答清楚；只使用本轮真实候选里的ID。电话用口语、语气词和停顿传递同样的态度。")
    }
}
