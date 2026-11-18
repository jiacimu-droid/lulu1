package com.jiacimu.lulu.games

import com.jiacimu.lulu.ai.CompanionContextMode
import com.jiacimu.lulu.ai.LuluAiServices
import com.jiacimu.lulu.ai.ModelUsage
import com.jiacimu.lulu.ai.ModelReply
import org.json.JSONObject

internal data class MemoryRoleChoice(val index: Int, val speech: String, val modelReply: ModelReply? = null)

/** A chosen card must be reachable in the CURRENT board, not a stale model frame. */
internal fun parseMemoryRoleChoice(raw: String, available: Set<Int>): MemoryRoleChoice? =
    runCatching {
        val json = JSONObject(raw.trim().removePrefix("```json").removePrefix("```").removeSuffix("```").trim())
        val index = json.getInt("index")
        if (index !in available) return@runCatching null
        MemoryRoleChoice(index, json.optString("say").trim().take(180))
    }.getOrNull()

/**
 * The role receives the public board and its own observed card history, NOT
 * game.cards at unseen indices. Unlike the former deterministic matcher, the
 * model chooses the next card and can speak while playing.
 */
internal suspend fun requestMemoryRoleChoice(
    characterId: String, game: MemoryMatchState, ownerSpeech: String,
): Result<MemoryRoleChoice> {
    if (game.finished || game.turn != MemoryTurn.Character || game.opened.size >= 2) {
        return Result.failure(IllegalStateException("现在不是角色可以翻牌的局面"))
    }
    val available = game.cards.indices.filter { it !in game.matched && it !in game.opened }.toSet()
    if (available.isEmpty()) return Result.failure(IllegalStateException("没有可以翻的牌"))
    val facts = buildString {
        appendLine("【记忆配对 · 同牌序双人挑战】你和主人分别使用完全相同的12张牌，各自找齐6对，谁总轮次少谁赢。你只能操作自己的牌桌。")
        appendLine("你已用${game.moves}轮找到${game.characterPairs}对；主人已经找到${game.userPairs}对。各自还需完成全部${game.cards.size / 2}对才能结算。")
        appendLine("牌位从0开始，可翻的位置：${available.sorted().joinToString("、")}。")
        appendLine("已经配对且不可选的位置：${game.matched.sorted().joinToString("、")}。")
        appendLine("本回合已经翻开的：${game.opened.sorted().joinToString("、") { "$it=${game.observed[it] ?: "未知"}" }}。")
        appendLine("你亲眼见过的牌（可能已经重新扣住）：${game.observed.toSortedMap().entries.joinToString("、") { "${it.key}=${it.value}" }.ifBlank { "无" }}。")
        appendLine("最新公开局况：${game.lastEvent}。")
        appendLine("主人本局最近的话：${ownerSpeech.ifBlank { "暂无" }}。")
        append("未被翻开过的牌背面是什么，你并不知道；不要凭空声称看到了隐藏牌。")
    }
    val result = LuluAiServices.gateway.generate(
        characterId = characterId, facts = facts,
        instruction = "这是你自己的真实回合。仅根据已经见过的卡牌、可能的配对和未翻位置自行决定翻哪一张；" +
            "没有可靠配对时也可猜测，必须承认未知。目标是在尽可能少的轮次内完成你的全部配对。" +
            "你可以顺便说一句自然的话，或保持安静。" +
            "只输出JSON：{\"index\":0,\"say\":\"...\"}，index必须从可翻位置中选，不要输出额外文字。",
        source = "游戏 · 记忆翻牌本人决策", title = "记忆翻牌 · 本人选牌",
        capturePrivateMoment = true,
        maxTokens = 160, usage = ModelUsage.Game,
        contextMode = CompanionContextMode.Full,
    )
    return result.mapCatching { reply ->
        parseMemoryRoleChoice(reply.text, available)?.copy(modelReply = reply)
            ?: throw IllegalArgumentException("角色没有选出合法牌位，本轮未擅自代翻")
    }
}
