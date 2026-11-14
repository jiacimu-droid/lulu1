package com.jiacimu.lulu.games

import org.json.JSONArray
import org.json.JSONObject
import java.util.UUID

/** A resumable competitive Yacht match. Navigation is not a forfeit or settlement. */
internal data class YachtMatchSnapshot(
    val id: String = UUID.randomUUID().toString(),
    val companionIds: List<String>,
    val scores: Map<String, Map<YachtCategory, Int>> = emptyMap(),
    val currentPlayerIndex: Int = 0,
    val dice: List<Int> = emptyList(),
    val held: Set<Int> = emptySet(),
    val rolls: Int = 0,
    val lastResult: String = "",
    val turnLog: List<String> = emptyList(),
    val matchLog: List<String> = emptyList(),
    val completed: Boolean = false,
) {
    val playerIds: List<String> get() = listOf("user") + companionIds.map { "role:$it" }

    fun toJson(): String = JSONObject().apply {
        put("id", id)
        put("companionIds", JSONArray(companionIds))
        put("scores", JSONObject().apply {
            scores.forEach { (player, sheet) ->
                put(player, JSONObject().apply { sheet.forEach { (category, value) -> put(category.name, value) } })
            }
        })
        put("currentPlayerIndex", currentPlayerIndex)
        put("dice", JSONArray(dice))
        put("held", JSONArray(held.sorted()))
        put("rolls", rolls)
        put("lastResult", lastResult)
        put("turnLog", JSONArray(turnLog))
        put("matchLog", JSONArray(matchLog))
        put("completed", completed)
    }.toString()

    /** Objective game facts for the model, never another dialogue policy. */
    fun contextFor(characterId: String): String {
        if (characterId !in companionIds || completed) return ""
        val played = scores["role:$characterId"]?.size ?: 0
        val totals = playerIds.joinToString("；") { "${it}=${scores[it].orEmpty().values.sum()}分" }
        val current = playerIds.getOrElse(currentPlayerIndex) { "user" }
        return "【真实进行中的游戏：快艇骰子】主人和你已开始同一局13轮竞技对抗；" +
            "你已完成$played/13轮，当前行动方=$current，累计得分：$totals。" +
            "必须所有玩家各完成13个计分格后才能结算，目前尚未分出胜负；跳转聊天不代表退出、拒绝或尚未开局。"
    }

    companion object {
        fun fromJson(raw: String?): YachtMatchSnapshot? = runCatching {
            if (raw.isNullOrBlank()) return@runCatching null
            val json = JSONObject(raw)
            val participants = json.getJSONArray("companionIds").strings().distinct().take(3)
            val scoreSheets = buildMap<String, Map<YachtCategory, Int>> {
                val board = json.optJSONObject("scores") ?: JSONObject()
                val ids = board.keys()
                while (ids.hasNext()) {
                    val player = ids.next()
                    val sheet = board.optJSONObject(player) ?: continue
                    put(player, buildMap {
                        YachtCategory.entries.forEach { category ->
                            if (sheet.has(category.name)) put(category, sheet.getInt(category.name))
                        }
                    })
                }
            }
            YachtMatchSnapshot(
                id = json.getString("id").takeIf(String::isNotBlank) ?: return@runCatching null,
                companionIds = participants,
                scores = scoreSheets,
                currentPlayerIndex = json.optInt("currentPlayerIndex").coerceIn(0, participants.size),
                dice = json.optJSONArray("dice")?.ints()?.takeIf { it.size == 5 && it.all { n -> n in 1..6 } } ?: emptyList(),
                held = json.optJSONArray("held")?.ints()?.filter { it in 0..4 }?.toSet() ?: emptySet(),
                rolls = json.optInt("rolls").coerceIn(0, 3),
                lastResult = json.optString("lastResult"),
                turnLog = json.optJSONArray("turnLog")?.strings().orEmpty(),
                matchLog = json.optJSONArray("matchLog")?.strings().orEmpty(),
                completed = json.optBoolean("completed"),
            )
        }.getOrNull()
    }
}

private fun JSONArray.strings(): List<String> = List(length()) { optString(it) }.filter(String::isNotBlank)
private fun JSONArray.ints(): List<Int> = List(length()) { optInt(it) }
