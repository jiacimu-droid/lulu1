package com.jiacimu.lulu.games

import org.json.JSONObject
import kotlin.math.abs

/** Real, saved island residents. Animals do not create model calls or secondary game state. */
internal data class IslandAnimal(
    val id: Int,
    val species: String,
    val x: Int,
    val y: Int,
    val befriended: Boolean = false,
    val lastGiftTurn: Int = -1,
) {
    fun json(): JSONObject = JSONObject()
        .put("id", id).put("species", species).put("x", x).put("y", y)
        .put("befriended", befriended).put("lastGiftTurn", lastGiftTurn)

    companion object {
        fun read(json: JSONObject, state: BlockIslandState): IslandAnimal? {
            val species = json.optString("species")
            val x = json.optInt("x", -1)
            val y = json.optInt("y", -1)
            if (species !in IslandWildlife.species || !state.world.inBounds(x, y) ||
                state.world.cell(x, y) !in setOf(0, 4, 7)) return null
            return IslandAnimal(
                id = json.optInt("id", -1), species = species, x = x, y = y,
                befriended = json.optBoolean("befriended", false),
                lastGiftTurn = json.optInt("lastGiftTurn", -1),
            ).takeIf { it.id >= 0 }
        }
    }
}

internal object IslandWildlife {
    val species = listOf("hare", "duck", "fox")
    fun name(species: String) = when (species) {
        "hare" -> "草甸兔"
        "duck" -> "池塘鸭"
        "fox" -> "赤尾狐"
        else -> "未知动物"
    }
    fun feed(species: String) = when (species) {
        "hare" -> "野果 ×1"
        "duck" -> "小鱼 ×1"
        "fox" -> "小鱼 ×2"
        else -> ""
    }
    fun canFeed(state: BlockIslandState, animal: IslandAnimal): Boolean =
        when (animal.species) {
            "hare" -> state.fruit >= 1
            "duck" -> state.fish >= 1
            "fox" -> state.fish >= 2
            else -> false
        }
    fun canGift(state: BlockIslandState, animal: IslandAnimal): Boolean =
        animal.befriended && state.world.turns - animal.lastGiftTurn >= 24

    /**
     * A few reproducible creatures on reachable existing grass. Prior buildings and save
     * positions win: upgrading a long-running home never overwrites player construction.
     */
    fun generate(state: BlockIslandState): List<IslandAnimal> {
        val w = state.world
        if (!BlockIslandRules.isWalkable(state, w.x, w.y)) return emptyList()
        val reached = mutableSetOf(w.index(w.x, w.y))
        val pending = java.util.ArrayDeque<Pair<Int, Int>>()
        pending.add(w.x to w.y)
        while (pending.isNotEmpty()) {
            val (x, y) = pending.removeFirst()
            for ((nx, ny) in listOf(x + 1 to y, x - 1 to y, x to y + 1, x to y - 1)) {
                if (!BlockIslandRules.isWalkable(state, nx, ny)) continue
                if (reached.add(w.index(nx, ny))) pending.add(nx to ny)
            }
        }
        val candidates = reached.map { it % w.width to it / w.width }.filter { (x, y) ->
            w.cell(x, y) == 0 && w.index(x, y) !in state.structures &&
                !IslandSupplyCaches.available(state, x, y) &&
                abs(x - w.x) + abs(y - w.y) >= 5 &&
                w.mobs.none { it.hp > 0 && it.x == x && it.y == y } &&
                w.friends.none { it.x == x && it.y == y }
        }
        val anchors = listOf(w.width / 3 to w.height / 3,
            w.width * 2 / 3 to w.height / 3,
            w.width * 2 / 3 to w.height * 2 / 3)
        val chosen = mutableListOf<Pair<Int, Int>>()
        return species.mapIndexedNotNull { id, animal ->
            val (ax, ay) = anchors[id]
            val spot = candidates
                .filter { (x, y) -> chosen.none { (cx, cy) -> abs(cx - x) + abs(cy - y) < 5 } }
                .minWithOrNull(compareBy<Pair<Int, Int>>(
                    { (x, y) -> if (animal == "duck" &&
                        listOf(x + 1 to y, x - 1 to y, x to y + 1, x to y - 1)
                            .any { (wx, wy) -> w.cell(wx, wy) == 8 }) 0 else if (animal == "duck") 8 else 0 },
                    { (x, y) -> abs(x - ax) + abs(y - ay) },
                    { (x, y) -> w.index(x, y) }))
            if (spot == null) null else {
                chosen += spot
                IslandAnimal(id, animal, spot.first, spot.second)
            }
        }
    }

    /** Only valid action turns can move animals. No background timers, hidden rewards or AI costs. */
    fun advance(state: BlockIslandState): BlockIslandState {
        val w = state.world
        if (w.turns % 4 != 0 || state.animals.isEmpty()) return state
        val occupied = state.animals.map { it.x to it.y }.toMutableSet()
        val updated = state.animals.map { animal ->
            occupied.remove(animal.x to animal.y)
            val choices = listOf(0 to 0, 0 to 1, 1 to 0, 0 to -1, -1 to 0)
                .map { (dx, dy) -> animal.x + dx to animal.y + dy }
                .filter { (x, y) ->
                    w.inBounds(x, y) && w.cell(x, y) == 0 &&
                        w.index(x, y) !in state.structures &&
                        !IslandSupplyCaches.available(state, x, y) &&
                        (x to y) !in occupied &&
                        (w.x != x || w.y != y) &&
                        w.friends.none { it.x == x && it.y == y } &&
                        w.mobs.none { it.hp > 0 && it.x == x && it.y == y }
                }
            val chosen = if (animal.befriended)
                choices.minWithOrNull(compareBy<Pair<Int, Int>>(
                    { (x, y) -> abs(x - w.x) + abs(y - w.y) },
                    { (x, y) -> if (x == animal.x && y == animal.y) 0 else 1 }))
            else if (choices.isEmpty()) null else
                choices[((w.seed.toLong() * 7 + w.turns.toLong() * 13 + animal.id * 17)
                    .and(Long.MAX_VALUE) % choices.size).toInt()]
            val newAnimal = if (chosen == null) animal else
                animal.copy(x = chosen.first, y = chosen.second)
            occupied.add(newAnimal.x to newAnimal.y)
            newAnimal
        }
        return state.copy(animals = updated)
    }
}
