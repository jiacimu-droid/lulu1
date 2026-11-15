package com.jiacimu.lulu.games

import org.json.JSONArray
import org.json.JSONObject

/**
 * Read-only frames for watching a character's actual relic run.
 * A single immutable board lives in replayStart; each accepted world action
 * stores only mutable physical state. No model call and no replay execution.
 * Old records without these frames remain honest text-only history.
 */
internal object RelicSoloReplay {
    fun start(run: RelicRun): JSONObject = run.copy(events = emptyList()).json()

    fun frame(run: RelicRun): JSONObject = JSONObject()
        .put("phase", run.phase.name)
        .put("hero", run.hero.json())
        .put("allies", JSONArray(run.allies.map { it.json() }))
        .put("chests", JSONArray(run.chests.map { it.json() }))
        .put("mobs", JSONArray(run.mobs.map { it.json() }))
        .put("drops", JSONArray(run.drops.map { it.json() }))
        .put("seen", JSONArray(run.seen.sorted()))
        .put("turns", run.turns)
        .put("extraction", run.extraction)
        .put("downTurns", run.downTurns)
        .put("notice", run.notice)
        .put("secured", JSONArray(run.secured.map { it.json() }))
        .put("lost", JSONArray(run.lost.map { it.json() }))
        .put("sites", JSONArray(run.sites.map { it.json() }))
        .put("flares", run.flares).put("lures", run.lures).put("tools", run.tools)
        .put("flareX", run.flareX).put("flareY", run.flareY)
        .put("flareTurns", run.flareTurns)
        .put("lureX", run.lureX).put("lureY", run.lureY)
        .put("lureTurns", run.lureTurns)
        .put("bolts", run.bolts).put("guarding", run.guarding)
        .put("playerChestMarks", JSONArray(run.playerChestMarks.sorted()))
        .put("guardedBy", run.guardedBy)
        .put("chestProgress", JSONObject().apply {
            run.chestProgress.forEach { (id, progress) -> put(id.toString(), progress) }
        })
        .put("sweepReadyTurn", run.sweepReadyTurn)
        .put("professionReadyTurn", run.professionReadyTurn)
        .put("professionCharges", run.professionCharges)

    /** Includes the true initial state, then exactly the committed frames in order. */
    fun read(raw: String): List<RelicRun>? = runCatching {
        val details = JSONObject(raw)
        if (details.optString("game") != "relic_escape") return@runCatching null
        val original = details.optJSONObject("replayStart") ?: return@runCatching null
        val first = RelicRun.read(original) ?: return@runCatching null
        val updates = details.optJSONArray("replayFrames") ?: return@runCatching null
        if (updates.length() !in 1..128) return@runCatching null
        val state = JSONObject(original.toString())
        val restored = mutableListOf(first)
        var previous = first
        for (index in 0 until updates.length()) {
            val change = updates.getJSONObject(index)
            val keys = change.keys()
            while (keys.hasNext()) {
                val key = keys.next()
                state.put(key, change.get(key))
            }
            val next = RelicRun.read(state) ?: return@runCatching null
            if (next.id != first.id || next.tiles != first.tiles ||
                next.turns < previous.turns || next.turns > previous.turns + 1 ||
                (next.turns == previous.turns && next.phase == previous.phase))
                return@runCatching null
            restored += next
            previous = next
        }
        if (details.optString("runId") != first.id) return@runCatching null
        restored
    }.getOrNull()
}
