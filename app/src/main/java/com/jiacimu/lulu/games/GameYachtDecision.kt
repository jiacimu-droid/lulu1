package com.jiacimu.lulu.games

import com.jiacimu.lulu.ai.CompanionContextMode
import com.jiacimu.lulu.ai.LuluAiServices
import com.jiacimu.lulu.ai.ModelUsage
import org.json.JSONObject

/** A role's validated decision, not a system-selected "best score". */
internal sealed interface YachtRoleDecision {
    val speech: String
    data class Keep(val indices: Set<Int>, override val speech: String) : YachtRoleDecision
    data class Score(val category: YachtCategory, override val speech: String) : YachtRoleDecision
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
            YachtRoleDecision.Keep(indices.toSet(), speech)
        }
        else -> null
    }
}.getOrNull()

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
    val facts = buildString {
        appendLine("【快艇骰子 · 已验证的本轮棋局】你是正在真实参与对抗的玩家。")
        appendLine("这是本局你的第${sheet.size + 1}/13个计分格。骰子五颗，最多掷三次，也可以提前计分。")
        appendLine("本回合第$roll/3次掷骰，五颗骰子按0到4位置为：${dice.mapIndexed { i, n -> "$i=$n" }.joinToString("、")}。")
        appendLine("所有人在同一计分桌的公开分数与已经使用的格子：")
        playerNames.forEach { (id, name) ->
            val used = scores[id].orEmpty()
            appendLine("$name 当前${used.values.sum()}分；已填：${used.entries.joinToString("、") { "${it.key.label}=${it.value}" }.ifBlank { "无" }}")
        }
        appendLine("你这一次掷骰可立即填的项目及其当前得分：")
        available.forEach { appendLine("${it.name}（${it.label}）=${scoreYacht(it, dice)}分") }
        appendLine("主人本局最近说的话：${ownerSpeech.ifBlank { "暂无" }}")
        appendLine("后面的骰面是未知的。上区奖励、留点数追大牌或填零保其它格，都应自行按局势选择。")
    }
    val instruction = buildString {
        append("你本人决定下一步，不是发表评论后由程序替你操作。")
        append("可以在任一次掷骰后直接填尚未使用的格子(score)；")
        if (roll < 3) append("或者保留任意0至5颗骰子重新掷(keep)，indices是要保留的0基骰子位置；")
        else append("已经掷三次，必须从仍可填的格子中选一个score；")
        append("考虑整场累计比分、已用格子、下一轮机会和与主人刚才的交流，不要仅按这一掷的即时最高分。")
        append("say是你想对主人当场说的一句自然口语，也可为空；不得预告尚未发生的结果。")
        append("只返回一个JSON对象：")
        append("{\"action\":\"keep|score\",\"indices\":[0,2],\"category\":\"Ones|Twos|Threes|Fours|Fives|Sixes|ThreeKind|FourKind|FullHouse|SmallStraight|LargeStraight|Yacht|Chance\",\"say\":\"...\"}。")
        append("keep时只用indices，score时只用category；不加代码围栏。")
    }
    return LuluAiServices.gateway.generate(
        characterId = characterId, facts = facts, instruction = instruction,
        source = "游戏 · 快艇骰子本人决策", title = "快艇骰子 · 本人行动",
        maxTokens = 260, usage = ModelUsage.Game,
        contextMode = CompanionContextMode.Full,
    ).mapCatching { reply ->
        parseYachtRoleDecision(reply.text, available, roll)
            ?: throw IllegalArgumentException("角色未选择合法的保留骰子或计分格，本轮没有替其决定")
    }
}
