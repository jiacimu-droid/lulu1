package com.jiacimu.lulu.data

/**
 * Models select one dominant outward conversation intent inside their existing
 * causal decision. There is no second LLM call, additional memory, or rule-based
 * response mapping.
 */
internal object ConversationDominantImpulse {
    val causalRule: String = """
        【并存心念→主导交流动作→说出口】
        根据角色自己真实的人格、关系、经历、此刻心情和眼前事实，可能同时好奇、想逗对方、想关心、反对或暂时不想说。允许多个心念，也允许毫无特别反应，不能为了“活人感”硬造情绪或玩笑。
        如果决定开口，先选择这一刻自己最想落实的交流动作或具体意思，再用本人的语气自然说出来。其他候选心念不是必须逐一答完的子任务；没选中的可以暂时不说，也不自动成为未来补发、永久挂心或新长期动机。
        一个意图可以是一个拟声、短反问、直接选择或更完整的话；表达多少根据本轮真正需要沟通的内容，不按用户发了几个字决定机械字数。复杂求助、重要事实、多项明确需求与边界要认真答，真正必要的多个执行动作仍走已有独立权限和回执。
        灵动是当场抓住一个自己真正在意或觉得好玩的点，不是同时展示好奇、温柔、幽默、撒娇、审美、浪漫等所有特点。若玩心最强，一句玩笑已经传达到位便停，不附送另几个念头的解释。严肃和求救场景不能因为玩心而漏掉必要回应。
    """.trimIndent()

    /** Illustrative impulses, not keyword-controlled replies or shared history. */
    fun planningGuidance(userText: String, recentHistory: String = ""): String {
        val style = TransientConversationStyle.analyze(userText, recentHistory)
        val examples = if (userText.isNotBlank() &&
            style.pace == TransientConversationStyle.Pace.BRIEF &&
            style.tonePressure == TransientConversationStyle.TonePressure.LIGHT) """
            【虚构的短聊天例子｜只说明如何挑主意图，不是必说台词】
            对方：“宝宝？” 一位亲近的人既好奇她想说什么，也想撒娇；若好奇占主导，可以只回：“怎么啦宝宝？”
            对方：“喂” 一位本来就爱玩谐音梗的人又好奇又想逗她；若模仿的玩心占主导，可以只回：“歪？”
            但若刚刚有人求救，面对“喂”更可能认真回应：“我在，怎么回事？”不机械搞笑。
            对方：“六个我都发给你了” 某人最想传达自己选中了哪张，就可以说：“我还是喜欢戴眼镜的那个。”不用逐张赏析再安排一个浪漫结尾。
            示例只展示同一输入的不同心念与取舍。不能复制示例口癖、称呼、态度或恋爱关系；具体选择由本人的性格、真实上下文与对方需要决定。
        """.trimIndent() else ""
        return if (examples.isBlank()) causalRule else causalRule + "\n" + examples
    }

    val expressionRule: String = """
        【表达已经选定的主导意图】
        speechIntent / dialogueMove 表示这一轮真的选择表达的重点，不是要逐项兑现全部内心想法的提纲。不要再拼开头、全方位分析和总结收尾；符合本人语气的拟声、玩笑、简短追问或一个结论只要传达到位就能停。必要的事实、重要边界、明确请求的完整解释依然保留。多条气泡合计才是一轮发言，不靠拆泡假装短聊。
    """.trimIndent()
}
