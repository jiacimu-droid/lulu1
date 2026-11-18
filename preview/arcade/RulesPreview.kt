package com.jiacimu.lulu.games

import org.json.JSONObject
import java.util.UUID

private const val PARTNER = "试玩队友"
private val capacity = 24

private sealed interface GameSession {
    data class Island(var state: BlockIslandState) : GameSession
    data class Relic(var run: RelicRun) : GameSession
}

private val sessions = LinkedHashMap<String, GameSession>(capacity)

private val reviewCrew = setOf("苔羽", "砾灯", "渡鸦")
private fun selectedCrew(q: JSONObject): List<String> = q.optJSONArray("crew")?.let { crew ->
    (0 until crew.length()).map { crew.optString(it) }.filter { it in reviewCrew }.distinct().take(2)
} ?: if (q.optBoolean("partner")) listOf(PARTNER) else emptyList()

private fun request(q: JSONObject): JSONObject {
    val verb = q.optString("verb")
    val kind = q.optString("kind")
    val seed = q.optInt("seed", 4317).coerceIn(1, Int.MAX_VALUE)
    val companions = selectedCrew(q)
    val sessionId = if (verb == "start") UUID.randomUUID().toString() else q.optString("session")
    if (verb == "start") {
        rememberSession(sessionId, when (kind) {
            "island" -> GameSession.Island(if (q.optString("map") == "coast") ArcadeMaps.coast(seed,companions)
                else BlockIslandRules.newWorld(seed, companions))
            "relic" -> {
                val difficulty = RelicDifficulty.entries.firstOrNull { it.name == q.optString("difficulty") }
                    ?: RelicDifficulty.NORMAL
                val weapon = q.optString("weapon", "blade").takeIf { it in setOf("blade", "spear", "bow") } ?: "blade"
                val base=RelicEscapeWorld.create(seed, 1, companions, 2, 1, difficulty = difficulty,
                    purchasedWeapons = setOf(weapon), startingWeapon = weapon).copy(
                    profession = RelicProfession.entries.firstOrNull { it.name == q.optString("profession") }
                        ?: RelicProfession.SCOUT)
                GameSession.Relic(when(q.optString("map")) {
                    "cloister" -> ArcadeMaps.cloister(base,false)
                    "ambush" -> ArcadeMaps.cloister(base,true)
                    else -> base
                })
            }
            else -> error("未知游戏")
        })
    }
    val input = ArcadeInput.read(q, PARTNER)
    val current = sessions[sessionId] ?: error("试玩已结束，请从大厅重新出发。")
    rememberSession(sessionId, current)
    val selected = input.selected.takeUnless { verb == "act" && input.action == "move" }
    val view = when (val game = current) {
        is GameSession.Island -> {
            val before = game.state
            if (verb == "act") game.state = arcadeIslandAct(before, input,
                before.world.friends.map { it.id }.toSet())
            val placed = verb == "act" && input.action == input.build && game.state.world.turns > before.world.turns
            arcadeIslandView(game.state, selected, input.facing, if (placed) "" else input.build)
        }
        is GameSession.Relic -> {
            val step = if (verb == "act") arcadeRelicAct(game.run, input, game.run.allies.map { it.id }.toSet()) else null
            if (step != null) game.run = step.run
            val projection = arcadeRelicView(game.run, selected, input.facing)
            step?.annotate(projection) ?: projection
        }
    }
    return view.put("session", sessionId)
}

private fun rememberSession(id: String, session: GameSession) {
    sessions.remove(id)
    sessions[id] = session
    if (sessions.size > capacity) sessions.remove(sessions.keys.first())
}

internal fun previewSave(id: String): String = when (val game = sessions[id]) {
    is GameSession.Island -> JSONObject().put("island", JSONObject(game.state.toJson())).toString()
    is GameSession.Relic -> JSONObject().put("relic", game.run.json()).toString()
    null -> ""
}

internal fun previewRestore(id: String, saved: JSONObject): Boolean {
    val session = saved.optJSONObject("island")?.let { BlockIslandState.fromJson(it.toString()) }
        ?.let { GameSession.Island(it) }
        ?: saved.optJSONObject("relic")?.let { RelicRun.read(it) }?.let { GameSession.Relic(it) }
        ?: return false
    rememberSession(id, session)
    return true
}

internal fun previewResponse(line: String): String = runCatching { request(JSONObject(line)) }
    .getOrElse { JSONObject().put("error", it.message ?: "试玩状态无法读取") }.toString()

fun main() {
    System.err.println("Lulu game rules loaded")
    generateSequence(::readLine).forEach { println(previewResponse(it)) }
}
