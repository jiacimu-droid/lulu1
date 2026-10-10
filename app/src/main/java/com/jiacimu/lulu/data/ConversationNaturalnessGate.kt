package com.jiacimu.lulu.data

/**
 * Runtime quality gate for surface wording.
 *
 * It does not decide what the character thinks or does. It only spots combinations that make an
 * otherwise valid dialogue move sound like a service assistant, then allows one bounded re-render.
 */
internal data class ConversationNaturalnessAssessment(
    val score: Int,
    val reasons: List<String>,
) {
    val needsRerender: Boolean get() = score >= 2

    fun repairInstruction(): String = buildString {
        appendLine("保留原来的事实、立场、dialogueMove 和行动结果，只重写表面说法。")
        if (reasons.isNotEmpty()) appendLine("本次需要消掉的人机感来源：" + reasons.joinToString("；"))
        appendLine("不要新增建议、承诺、工具动作、关系结论或新的问题；像这个人当场接话，说够就停。")
    }.trim()
}

internal object ConversationNaturalnessGate {
    private val assistantOpening = Regex(
        """^(?:嗯?[，, ]*)?(?:我理解(?:你的意思|你)|我明白(?:你的意思|了)|听起来|看起来|根据你说的|你的意思是)"""
    )
    private val passDecisionBack = Regex(
        """(?:你想让我(?:做什么|怎么做)|你希望我(?:做什么|怎么做)|需要我做什么|我能为你做什么|有什么我可以帮你的)"""
    )
    private val servicePhrases = Regex(
        """(?:如果你愿意|如果你需要|你可以|我建议|建议你|可以考虑|我可以帮你|需要的话我可以)"""
    )
    private val outlinePhrases = Regex(
        """(?:首先|其次|最后|第一点|第二点|第三点|总结一下|总的来说)"""
    )
    private val metaAssistant = Regex(
        """(?:作为(?:一个)?(?:ai|AI|助手|模型)|根据你的需求|下面我(?:来|会)|我将为你)"""
    )

    fun assess(userText: String, bubbles: List<String>): ConversationNaturalnessAssessment {
        val reply = bubbles.joinToString("\n").trim()
        if (reply.isBlank()) return ConversationNaturalnessAssessment(0, emptyList())

        var score = 0
        val reasons = mutableListOf<String>()
        if (metaAssistant.containsMatchIn(reply)) {
            score += 3
            reasons += "出现助手/任务式元话语"
        }
        if (assistantOpening.containsMatchIn(reply)) {
            score += 1
            reasons += "用总结用户意思作模板式开场"
        }
        val initiativeCue = CharacterInitiativeRuntime.detect(userText)
        if (initiativeCue != null && passDecisionBack.containsMatchIn(reply)) {
            score += 3
            reasons += "用户已暴露需要线索，却把“该做什么”原样甩回给用户"
        }
        val serviceCount = servicePhrases.findAll(reply).count()
        if (serviceCount >= 2) {
            score += 2
            reasons += "连续使用服务式建议/许可措辞"
        }
        val outlineCount = outlinePhrases.findAll(reply).count()
        if (outlineCount >= 2) {
            score += 2
            reasons += "把即时聊天组织成说明书/提纲"
        }
        val userLength = userText.count { !it.isWhitespace() }
        val replyLength = reply.count { !it.isWhitespace() }
        if (userLength in 1..24 && replyLength >= 140) {
            score += 1
            reasons += "短促聊天被无必要扩成长篇"
        }
        val questionCount = reply.count { it == '？' || it == '?' }
        val userQuestionCount = userText.count { it == '？' || it == '?' }
        if (questionCount >= 3 && userQuestionCount <= 1) {
            score += 1
            reasons += "一拍连续抛出过多问题"
        }

        return ConversationNaturalnessAssessment(score, reasons.distinct())
    }
}
