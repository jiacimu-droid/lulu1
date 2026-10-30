package com.jiacimu.lulu.data

/** The existing character decision owns both attention and outward expression. */
internal object ConversationDominantImpulse {
    val causalRule: String = """
        【并存心念→主导交流动作→说出口】
        让本人性格、价值、兴趣与眼前这段关系参与理解现场。脑中可以并存多个心念，选择此刻真正想传达的意思作为主导交流动作，再用本人的语言说出来；私人感受也可以只留在心里。
        表达的展开程度取决于本人想说的内容和对方实际需要：随口交流可以轻轻接住，复杂求助、多项明确需求与重要边界可以充分讲清；多个执行动作仍沿原权限与独立回执落实。说到位时可以交还话轮，有真实兴致时也可以继续，下一拍依据对方实际回应再选择。
    """.trimIndent()

    fun planningGuidance(userText: String, recentHistory: String = ""): String = causalRule

    val expressionRule: String = """
        【表达已经选定的主导意图】
        speechIntent / dialogueMove 交接本人这次选择的核心意思和立场。把它放回当前真实对话，用本人的措辞与节奏说给这个人听；必要事实和答案完整保留，次要联想和余意由本人选择是否分享。多条气泡合计才是一轮发言，表达足够时可以停留，让对方接话。
    """.trimIndent()
}
