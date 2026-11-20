package com.jiacimu.lulu.games

import com.jiacimu.lulu.ai.CompanionContextMode
import com.jiacimu.lulu.ai.LuluAiServices
import com.jiacimu.lulu.ai.ModelUsage
import com.jiacimu.lulu.ai.ModelReply
import org.json.JSONObject

/** A role's validated decision, not a system-selected "best score". */
internal sealed interface YachtRoleDecision {
    val speech: String
    val modelReply: ModelReply?
    data class Keep(
        val indices: Set<Int>,
        override val speech: String,
        val fallbackCategory: YachtCategory? = null,
        override val modelReply: ModelReply? = null,
    ) : YachtRoleDecision
    data class Score(val category: YachtCategory, override val speech: String, override val modelReply: ModelReply? = null) : YachtRoleDecision
}

/** Invalid/used slots never silently become a different choice. */
internal fun parseYachtRoleDecision(
    raw: String, available: Set<YachtCategory>, roll: Int,
): YachtRoleDecision? = runCatching {
    val json = JSONObject(raw.trim().removePrefix("```json").removePrefix("```").removeSuffix("```").trim())
    val speech = json.optString("say").trim().take(180)
    when (json.getString("action")) {
        "score" -> {
            val category = YachtCategory.entries.firstOrNull { it.name == json.getString("category") }
                ?: return@runCatching null
            if (category !in available || roll !in 1..3) return@runCatching null
            YachtRoleDecision.Score(category, speech)
        }
        "keep" -> {
            if (roll !in 1..2) return@runCatching null
            val array = json.getJSONArray("indices")
            val indices = (0 until array.length()).map { array.getInt(it) }
            if (indices.any { it !in 0..4 } || indices.distinct().size != indices.size) return@runCatching null
            // On roll two the role explicitly authorizes a category for use
            // only if the final roll API response has no usable decision.
            val fallback = if (roll == 2) {
                YachtCategory.entries.firstOrNull { it.name == json.optString("fallbackCategory") }
                    ?.takeIf { it in available } ?: return@runCatching null
            } else null
            YachtRoleDecision.Keep(indices.toSet(), speech, fallbackCategory = fallback)
        }
        else -> null
    }
}.getOrNull()

internal data class YachtDecisionPrompts(val facts: String, val instruction: String)

/** Shared pure contract for the live request and game-policy regression tests. */
internal fun buildYachtDecisionPrompts(
    characterId: String,
    dice: List<Int>,
    roll: Int,
    scores: Map<String, Map<YachtCategory, Int>>,
    playerNames: Map<String, String>,
    ownerSpeech: String,
): YachtDecisionPrompts {
    val sheet = scores["role:$characterId"].orEmpty()
    val available = YachtCategory.entries.filterNot { it in sheet }.toSet()
    val facts = buildString {
        appendLine("【快艇骰子 · 真实对局】你在和其他玩家竞争13个计分格，目标是争取尽量高的整局总分。")
        appendLine("你正在填第${sheet.size + 1}/13个格子；本回合已掷$roll/3次，最多三掷，允许提前计分。")
        appendLine("骰子位置从0到4：${dice.mapIndexed { i, n -> "$i=$n" }.joinToString("、")}。")
        playerNames.forEach { (id, name) ->
            val used = scores[id].orEmpty()
            appendLine("$name 已得${used.values.sum()}分；已填格：${used.entries.joinToString("、") { "${it.key.name}=${it.value}" }.ifBlank { "无" }}")
        }
        appendLine("你尚可填写的格子及本掷得分：")
        available.forEach { appendLine("${it.name}(${it.label})=${scoreYacht(it, dice)}") }
        if (roll < 3) {
            appendLine("还有${3 - roll}次重掷机会；你可自行决定保留哪些骰子，让其余骰子重新随机掷。")
        } else {
            appendLine("第三掷已完成，骰面锁定，本回合必须立即选择一个尚未填写的计分格，不能再掷。")
        }
        appendLine("主人最近说的话：${ownerSpeech.ifBlank { "暂无" }}")
    }
    val instruction = buildString {
        append("这是一场真正的竞技对局。默认目标是尽量争取更高的整局总分与获胜机会；不要只因为眼前已有可填的分数就急着收下。")
        append("每次结合真实骰面、剩余掷骰次数、所有未用计分格、各格当前及潜在得分、双方比分与上区奖励，自主比较现在计分、保留哪些骰子和继续重掷的预期收益、风险、后续可填空间与机会成本。")
        append("高分机会值得认真评估，但既不必每次赌到底，也不应机械提前收分；选择应随局面变化，而非套用某种骰型的固定策略。")
        append("你仍是具有本人性格、情绪和关系的人：可以因真实想法而冒险、求稳、故意让分或逗对手开心；若没有特别动机，就认真争取赢得比赛。")
        append("API是否可靠、调用成本、失败回退和备用格只属于程序执行机制，绝不能成为你提前计分或降低竞技目标的理由。")
        append("不需要详细推导，直接输出可执行的合法JSON对象。")
        if (roll == 1) {
            append("第一掷可选keep继续掷或score立刻填格。")
            append("""keep格式：{"action":"keep","indices":[0,1,2,3],"say":"再试一把。"}；score格式：{"action":"score","category":"Chance","say":""}。""")
        } else if (roll == 2) {
            append("第二掷可选keep进行第三掷，或score提前计分。")
            append("选keep时需额外提供fallbackCategory：你现在预先指定的一个合法未使用格，仅在第三掷API没有返回合法决定时按真实最终骰面兜底；现在不会填格，也不影响是否继续争高分。")
            append("""keep格式：{"action":"keep","indices":[0,1,2,3],"fallbackCategory":"FourKind","say":"再摇一次。"}；score格式：{"action":"score","category":"Chance","say":""}。""")
        } else {
            append("第三掷已满3/3：必须且只能score，不能keep，填写一个未使用计分格。")
            append("""只返回形如{"action":"score","category":"FourKind","say":"这轮收下。"}的JSON对象。""")
        }
        append("category/fallbackCategory的英文名必须来自本次尚可填写列表；indices是保留骰子在当前骰面中的0到4位置。")
        append("上述类别只是格式例子，不能照抄代替真实选择。say可为空；不要输出代码围栏、额外解释或尚未发生的结果。")
    }
    return YachtDecisionPrompts(facts, instruction)
}

/**
 * One model decision after each actual roll. Random dice are an engine fact;
 * choosing holds, an early score, or a sacrifice belongs to the same role.
 * No hidden future dice or other players' private reasoning is shown.
 */
internal suspend fun requestYachtRoleDecision(
    characterId: String,
    dice: List<Int>,
    roll: Int,
    scores: Map<String, Map<YachtCategory, Int>>,
    playerNames: Map<String, String>,
    ownerSpeech: String,
): Result<YachtRoleDecision> {
    val sheet = scores["role:$characterId"].orEmpty()
    val available = YachtCategory.entries.filterNot { it in sheet }.toSet()
    if (dice.size != 5 || dice.any { it !in 1..6 } || roll !in 1..3 || available.isEmpty()) {
        return Result.failure(IllegalArgumentException("当前骰面或计分状态不合法"))
    }
    val (facts, instruction) = buildYachtDecisionPrompts(
        characterId, dice, roll, scores, playerNames, ownerSpeech,
    )
    return LuluAiServices.gateway.generate(
        characterId = characterId, facts = facts, instruction = instruction,
        source = "游戏 · 快艇骰子本人决策", title = "快艇骰子 · 本人行动",
        capturePrivateMoment = true,
        // A real roll consumed all 2,308 output tokens in hidden reasoning and
        // produced zero action characters. Provide headroom for the single
        // decision without unlimited spending or an automatic paid retry.
        maxTokens = 6_144, usage = ModelUsage.Game,
        contextMode = CompanionContextMode.Full,
    ).mapCatching { reply ->
        when (val choice = parseYachtRoleDecision(reply.text, available, roll)) {
            is YachtRoleDecision.Keep -> choice.copy(modelReply = reply)
            is YachtRoleDecision.Score -> choice.copy(modelReply = reply)
            null -> throw IllegalArgumentException("角色未选择合法的保留骰子或计分格，本轮没有替其决定")
        }
    }
}
