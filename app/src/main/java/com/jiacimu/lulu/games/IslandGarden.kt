package com.jiacimu.lulu.games

import org.json.JSONObject

/**
 * A crop has three actual stages: sowing, tending, and harvesting.
 * Time comes from the same saved island turn counter; no timers or passive loot.
 */
internal data class IslandGardenCrop(
    val plantedTurn: Int,
    val tendedTurn: Int = -1,
) {
    fun json(): JSONObject = JSONObject()
        .put("plantedTurn", plantedTurn).put("tendedTurn", tendedTurn)
}

internal object IslandGarden {
    fun season(turn: Int): String = when ((turn / 96) % 4) {
        0 -> "春"; 1 -> "夏"; 2 -> "秋"; else -> "冬"
    }

    fun growthTurns(turn: Int): Int = if (season(turn) == "冬") 18 else 12

    fun harvestYield(turn: Int): Int = if (season(turn) == "秋") 5 else 4

    fun isReady(crop: IslandGardenCrop, turn: Int): Boolean =
        crop.tendedTurn >= crop.plantedTurn + 4 &&
            turn - crop.plantedTurn >= growthTurns(turn)

    fun action(state: BlockIslandState, index: Int): String {
        val crop = state.gardens[index] ?: return "sow_berries"
        return if (crop.tendedTurn >= 0) "harvest_berries" else "tend_berries"
    }

    fun actionLabel(state: BlockIslandState, index: Int): String {
        val crop = state.gardens[index] ?: return if (state.fruit > 0) "播种野果" else "需要1颗野果种子"
        return if (crop.tendedTurn < 0) {
            val remaining = (crop.plantedTurn + 4 - state.world.turns).coerceAtLeast(0)
            if (remaining > 0) "幼苗生长 · 再走$remaining 步" else "给菜圃松土"
        } else {
            val remaining = (crop.plantedTurn + growthTurns(state.world.turns) - state.world.turns)
                .coerceAtLeast(0)
            if (remaining > 0) "等待成熟 · 还需$remaining 步" else "收获野果"
        }
    }

    fun canInteract(state: BlockIslandState, index: Int): Boolean {
        val crop = state.gardens[index] ?: return state.fruit > 0
        return if (crop.tendedTurn < 0) state.world.turns - crop.plantedTurn >= 4
            else isReady(crop, state.world.turns)
    }

    fun restore(raw: JSONObject?, state: BlockIslandState): Map<Int, IslandGardenCrop> {
        if (raw == null) return emptyMap()
        val result = mutableMapOf<Int, IslandGardenCrop>()
        val keys = raw.keys()
        while (keys.hasNext()) {
            val key = keys.next()
            val index = key.toIntOrNull() ?: continue
            if (state.structures[index] != "garden") continue
            val item = raw.optJSONObject(key) ?: continue
            val planted = item.optInt("plantedTurn", -1)
            val tended = item.optInt("tendedTurn", -1)
            if (planted !in 0..state.world.turns || (tended >= 0 &&
                tended !in (planted + 4)..state.world.turns)) continue
            result[index] = IslandGardenCrop(planted, tended)
        }
        return result
    }
}
