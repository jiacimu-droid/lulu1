package com.jiacimu.lulu.data

/** Initial constraints, not generated world facts or a diagnostic personality inventory. */
internal data class CharacterProfileField(val key: String, val label: String, val hint: String, val group: String)
internal object CharacterProfileSchema {
    // Storage keys stay stable for existing characters. Labels/groups are the authoring model:
    // facts live on the Profile page; this page only describes how the same person tends to
    // interpret, choose, relate and express. Short-lived feelings/concerns never belong here.
    val fields = listOf(
        CharacterProfileField("values", "价值观 · 优先与取舍", "珍视什么；价值冲突时怎样选择，允许保留矛盾", "人物内核"),
        CharacterProfileField("motives", "核心驱动 · 渴望与担忧", "长期想实现什么、害怕辜负什么；区别于当前目标", "人物内核"),
        CharacterProfileField("enneagram", "九型人格 · 动机参考", "可选类型与本人具体动机；不确定可留空，不凭行为强行定型", "人物内核"),
        CharacterProfileField("traits", "大五 · 稳定特质", "开放、尽责、社交与主张、亲和、情绪恢复；用具体倾向而非虚构分数", "人物内核"),
        CharacterProfileField("attachment", "依恋 · 亲密与修复", "面对靠近、分离、不安与冲突的倾向；结合不同关系，不强制安全型", "亲密与社交"),
        CharacterProfileField("cognitiveStyle", "MBTI · 荣格八维", "类型、功能主次及本人具体的理解与取舍方式", "认知结构"),
        CharacterProfileField("perception", "理解方式", "", "行事方式"),
        CharacterProfileField("conflict", "分歧与修复", "", "行事方式"),
        CharacterProfileField("care", "亲密的一面", "", "亲密与社交"),
        CharacterProfileField("respect", "关系中的态度", "", "亲密与社交"),
        CharacterProfileField("social", "面对其他人", "", "亲密与社交"),
        CharacterProfileField("expression", "主色调 · 外在气质", "", "呈现与表达"),
        CharacterProfileField("speechHabits", "说话的味道", "", "呈现与表达"),
        CharacterProfileField("dialogueExamples", "本人对话示例", "可编辑的虚构示范，非真实聊天记录", "呈现与表达"),
        CharacterProfileField("interests", "兴趣与审美", "", "呈现与表达"),
        // Historical typing labels are accepted for import but not injected as a
        // second personality rule: their content is already expressed as values
        // and reasoning style, and MBTI labels must not prescribe behavior.
        CharacterProfileField("typing", "旧人格类型参考", "", "兼容存档"),
    )
    val groupOrder = listOf("人物内核", "认知结构", "呈现与表达", "亲密与社交", "行事方式")
    val groupHints = emptyMap<String, String>()
    fun groupedFields(): List<Pair<String, List<CharacterProfileField>>> = groupOrder.mapNotNull { group ->
        fields.filter { it.group == group }.takeIf { it.isNotEmpty() }?.let { group to it }
    }

    // Personal characters are user-owned local data, never source-code presets.

}
