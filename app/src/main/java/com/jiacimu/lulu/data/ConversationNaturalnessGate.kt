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
        appendLine("当只是简单追问或工具未成功时，留最关键的事实和本人真实反应就够了。删掉重复的原因复述、客服式安慰及事后检讨；简短而不是故意敷衍。")
        appendLine("留白发生在这一轮的内容取舍上：保留核心判断、必要答案与一点本人情绪，删掉后续赏析和解释情绪的解释；不是把同一篇长文分成更多气泡。")
        appendLine("如果是在安慰，只接当前最相关的担忧，不负责一次把所有情绪劝好；去掉额外的呼吸指导、心理归因和人生道理，不把它们换词后重新写一遍。用户明确要求的指导与必要的求助信息仍须保留。")
    }.trim()
}

internal object ConversationNaturalnessGate {
    private val assistantOpening = Regex(
        """^(?:嗯?[，, ]*)?(?:我理解(?:你的意思|你)|我明白(?:你的意思|了)|听起来|看起来|根据你说的|你的意思是)"""
    )
    private val passDecisionBack = Regex(
        """(?:(?:那)?我(?:该|应该)(?:做什么|做些什么|怎么做|怎么办)|你想让我(?:做什么|做些什么|怎么做)|你希望我(?:做什么|做些什么|怎么做)|需要我(?:做什么|做些什么|怎么做)|我能为你做什么|有什么我(?:可以|能)帮(?:你|忙)的)"""
    )
    // A genuine, model-authored move may be followed by a question. The gate
    // must not rewrite a chosen offer into a mandatory one-way comfort script.
    private val roleAlreadyOffered = Regex(
        """(?:我(?:先|来|陪你|想|打算|准备|给你|跟你)|不如我们|我们先|要不(?:我们)?|一起(?:玩|走|聊|看看)|让我先)"""
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
    // Independent rhetorical moves, counted across the whole turn. A compact
    // four-step counselling script can sound unnatural below any length limit;
    // a single sincere reassurance is not a defect.
    private val comfortScriptMoves = listOf(
        Regex("""(?:先.{0,5}(?:深呼吸|深吸.{0,3}气|冷静)|把心跳.{0,5}(?:缓|稳)|放松.{0,3}(?:身体|肩膀))"""),
        Regex("""(?:我(?:知道|理解|明白|懂).{0,8}你.{0,8}(?:心里|害怕|恐慌|焦虑|压力)|听起来你.{0,12}(?:压力|焦虑|害怕))"""),
        Regex("""(?:(?:不等于|不代表|绝不等于).{0,8}(?:放弃|失败)|人不是.{0,8}(?:机器|铁)|天塌不下来|不要把.{0,18}极端)"""),
        Regex("""(?:(?:别|不要|不用).{0,5}(?:一个人扛|独自扛|自己扛)|(?:到|来).{0,5}我身边.{0,10}(?:缓|歇))"""),
    )
    private val requestedCalmingGuidance = Regex(
        """(?:怎么.{0,5}(?:缓解|放松|冷静)|(?:教我|带我|帮我).{0,8}(?:呼吸|放松|冷静)|一步一步)"""
    )

    /**
     * Online private chat commits its first reply inside the autonomous decision call,
     * instead of passing through the foreground expression renderer. Share the SAME
     * surface-quality assessment; never give this gate authority over the role's
     * actions, voice tracks, quote tokens, or chosen meaning. No request is made
     * when the ordinary utterance is already conversational.
     */
    fun onlinePrivateReview(
        userText: String,
        bubbles: List<String>,
        hasVoice: Boolean,
        hasLinkedActions: Boolean,
    ): ConversationNaturalnessAssessment? {
        if (hasVoice || hasLinkedActions || bubbles.isEmpty() ||
            bubbles.any { "⟪" in it }) return null
        return assess(userText, bubbles).takeIf { it.needsRerender }
    }

    /** Select a model-written revision, never trim or replace it with stock text. */
    fun shouldUseRewrite(
        userText: String,
        original: List<String>,
        rewritten: List<String>,
        originalAssessment: ConversationNaturalnessAssessment,
        rewrittenAssessment: ConversationNaturalnessAssessment,
    ): Boolean {
        if (!originalAssessment.needsRerender || rewritten.all(String::isBlank) ||
            !preservesSurfaceIntent(original, rewritten)) return false
        // A lower total must not hide a newly introduced assistant-like habit.
        if (rewrittenAssessment.reasons.any { it !in originalAssessment.reasons }) return false
        // Being shorter on a tied score is not evidence of a better reply.
        return rewrittenAssessment.score < originalAssessment.score
    }

    fun preservesSurfaceIntent(original: List<String>, rewritten: List<String>): Boolean {
        if (rewritten.isEmpty()) return false
        val before = original.joinToString("\n")
        val after = rewritten.joinToString("\n")
        val beforeQuestions = before.count { it == '？' || it == '?' }
        val afterQuestions = after.count { it == '？' || it == '?' }
        if (afterQuestions > beforeQuestions) return false

        // A cosmetic language pass must never rewrite actual facts, times,
        // negative conclusions or the identity of a specific shared post.
        // Reject the rewrite and use the original instead of letting style
        // polishing fabricate a tool result or invert someone's stance.
        val numbers = Regex("""(?<![A-Za-z0-9])\d+(?:[.:：]\d+)?(?:%|％|元|分钟|小时|天|次|条|张|月|日|点)?""")
            .findAll(before).map { it.value }.toSet()
        val numbersAfter = Regex("""(?<![A-Za-z0-9])\d+(?:[.:：]\d+)?(?:%|％|元|分钟|小时|天|次|条|张|月|日|点)?""")
            .findAll(after).map { it.value }.toSet()
        if (!numbersAfter.containsAll(numbers)) return false

        val protectedUrls = Regex("""https?://[^\s，。；;）)]+""", RegexOption.IGNORE_CASE)
            .findAll(before).map { it.value }.toSet()
        if (protectedUrls.any { it !in after }) return false

        val unreadSignals = Regex("""(?:还没|没有|没能|没法|尚未|未能|无法|不能|失败|没读|没看|未读取|不可)""")
        if (unreadSignals.containsMatchIn(before) &&
            !unreadSignals.containsMatchIn(after)) return false
        val executedSignals = Regex("""(?:已经(?:看过|读过|打开|发送|完成)|实际(?:读取|执行)|已成功)""")
        if (!executedSignals.containsMatchIn(before) &&
            executedSignals.containsMatchIn(after)) return false

        val actionOrPromise = Regex(
            """(?:我(?:会|要|去|来|帮你|给你|替你|陪你去)|待会(?:儿)?我|等会(?:儿)?我|马上(?:去|给|帮)|一定(?:会|要)|保证|答应你|说到做到)"""
        )
        val beforeActions = actionOrPromise.findAll(before).map { it.value }.toSet()
        val afterActions = actionOrPromise.findAll(after).map { it.value }.toSet()
        if (!beforeActions.containsAll(afterActions)) return false

        val relationshipEscalation = Regex("""(?:永远|一辈子|这辈子|只属于|最爱你|离不开你)""")
        val beforeRelation = relationshipEscalation.findAll(before).map { it.value }.toSet()
        val afterRelation = relationshipEscalation.findAll(after).map { it.value }.toSet()
        if (!beforeRelation.containsAll(afterRelation)) return false

        val controlProtocol = Regex("""(?:\{"action":"tool"|⟪(?:QUOTE|FAVORITE|RECALL|POKE_USER))""")
        if (controlProtocol.containsMatchIn(after) && !controlProtocol.containsMatchIn(before)) return false
        return true
    }

    fun assess(
        userText: String,
        bubbles: List<String>,
        recentHistory: String = "",
        recentCharacterReplies: List<String> = emptyList(),
        operationalEvidence: String = "",
    ): ConversationNaturalnessAssessment {
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
        if (initiativeCue != null && passDecisionBack.containsMatchIn(reply) &&
            !roleAlreadyOffered.containsMatchIn(reply)) {
            score += 3
            reasons += "用户已暴露需要线索，却把“该做什么”原样甩回给用户"
        }
        val asksTechnicalCause = Regex("为什么|为啥|怎么回事|什么原因|排查|修复|代码|日志|权限").containsMatchIn(userText)
        val reportTerms = Regex("执行回执|任务状态|触发链路|调度机制|故障原因|整改|后续措施|经核实|未能触发|执行结果")
        if (!asksTechnicalCause && reportTerms.findAll(reply).count() >= 2) {
            score += 2
            reasons += "把普通关系交流写成任务故障或整改报告"
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
        // Only used by the repeated-opening diagnostic. Not a global reply
        // limit and not a reason to shorten a valid character-owned answer.
        val replyLength = reply.count { !it.isWhitespace() }
        if (ConversationTurnEconomy.hasUnsupportedDeliveryCause(reply, operationalEvidence)) {
            score += 2
            reasons += "把回复间隔编成了未经本轮程序回执证实的消息或网络故障；保留未及时回应的事实，省略未知原因"
        }
        if (userLength in 1..70 && replyLength >= 36 &&
            ConversationTurnEconomy.repeatsOpening(reply, recentCharacterReplies, userText)) {
            score += 2
            reasons += "复用本人近期的短起手式后又扩写整段说明，像同一套开场模板"
        }
        if (ConversationTurnEconomy.overloadedClockPivot(userText, reply)) {
            score += 2
            reasons += "简单接话附带报时和连续新问题，偏离对方这一拍正在交流的事"
        }
        // Model-selected depth is not a function of message length or key phrases.
        // Spot real repeated content, whether in one bubble or several.
        val detailRequested = TransientConversationStyle.allowsExpansion(userText)
        if (repeatsSubstantialPassage(reply)) {
            score += 2
            reasons += "同一轮反复重复较长语段，没有推进对话"
        }
        if (!detailRequested && !requestedCalmingGuidance.containsMatchIn(userText) &&
            comfortScriptMoves.count { it.containsMatchIn(reply) } >= 3) {
            score += 2
            reasons += "把倾诉扩成呼吸指导、心理归因、说理与保证的安慰流程；只回应眼前担忧，给对方接话的余地"
        }
        // A three-beat apology / excuse / reassurance is a mini incident report,
        // even when it is short enough to evade the length threshold.
        // Count patterns across the whole turn, not just identical phrases.
        val selfJustifications = Regex(
            """(?:是我刚才|我刚才(?:没|不该|太)|对不起|抱歉|不好意思|你别着急|不是你.{0,15}(?:错|问题|线路)|害你(?:白等|担心)|让你(?:白等|担心)|没确认清楚)"""
        )
        if (!detailRequested && userLength in 1..70 &&
            selfJustifications.findAll(reply).count() >= 3) {
            score += 2
            reasons += "一轮对话反复解释失误、安慰和自我检讨，压过了真正想表达的内容"
        }
        val normalizedBubbles = bubbles.map(::surfaceNormalize).filter { it.length >= 8 }
        val repeatedPair = normalizedBubbles.indices.any { i ->
            (i + 1 until normalizedBubbles.size).any { j ->
                surfaceOverlap(normalizedBubbles[i], normalizedBubbles[j]) >= 0.68
            }
        }
        if (repeatedPair) {
            score += 2
            reasons += "同一轮多个气泡在换词重复同一个意思"
        }
        val firstSurface = normalizedBubbles.firstOrNull().orEmpty()
        if (firstSurface.length >= 10 && recentHistory.isNotBlank()) {
            val prefix = firstSurface.take(12)
            val normalizedHistory = surfaceNormalize(recentHistory)
            if (prefix.length >= 10 && normalizedHistory.contains(prefix)) {
                score += 2
                reasons += "近期重复了相同的长起手式"
            }
        }

        val questionCount = reply.count { it == '？' || it == '?' }
        val userQuestionCount = userText.count { it == '？' || it == '?' }
        if (questionCount >= 3 && userQuestionCount <= 1) {
            score += 1
            reasons += "一拍连续抛出过多问题"
        }

        return ConversationNaturalnessAssessment(score, reasons.distinct())
    }

    /** Find three non-overlapping appearances of the same substantial wording. */
    private fun repeatsSubstantialPassage(reply: String): Boolean {
        val normalized = surfaceNormalize(reply)
        val spanSize = 24
        if (normalized.length < spanSize * 3) return false
        val first = mutableMapOf<String, Int>()
        val second = mutableMapOf<String, Int>()
        for (index in 0..normalized.length - spanSize) {
            val span = normalized.substring(index, index + spanSize)
            val earliest = first[span]
            if (earliest == null) first[span] = index
            else if (index - earliest >= spanSize) {
                val next = second[span]
                if (next == null) second[span] = index
                else if (index - next >= spanSize) return true
            }
        }
        return false
    }

    private fun surfaceNormalize(value: String): String =
        value.lowercase().replace(Regex("""[\s，。！？!?、；;：:“”‘’…~～—_"'（）()]+"""), "")

    private fun surfaceOverlap(a: String, b: String): Double {
        if (a.length < 4 || b.length < 4) return 0.0
        val aPairs = a.windowed(2).toSet()
        val bPairs = b.windowed(2).toSet()
        if (aPairs.isEmpty() || bPairs.isEmpty()) return 0.0
        return aPairs.intersect(bPairs).size.toDouble() / minOf(aPairs.size, bPairs.size)
    }
}
