package com.jiacimu.lulu.games

import org.json.JSONObject
import kotlin.math.abs

/** A visible blue-veined ore contains a crystal. Ordinary rocks can be mined by hand.
 * Crystal geology depends only on the saved world seed/tile, never animation or chance
 * evaluated during a gesture. A rejected barehand attempt never destroys the vein.
 */
internal object IslandProgression {
    fun crystalVein(seed: Int, tileIndex: Int): Boolean =
        (((tileIndex * 31) xor (seed * 17)) and Int.MAX_VALUE) % 7 == 0

    fun crystalLampProtects(s: BlockIslandState, x: Int = s.world.x, y: Int = s.world.y): Boolean =
        s.world.turns % 48 >= 30 && s.structures.any { (index, type) ->
            type == "crystal_lantern" &&
                abs(index % s.world.width - x) + abs(index / s.world.width - y) <= 3
        }

    fun foundCrystalText(s: BlockIslandState): String = when {
        "pickaxe" !in s.tools -> "找到蓝色晶矿！先收集木石，在工作台制作石镐"
        s.crystals == 0 -> "用石镐采蓝色晶矿得晶石，可造护夜晶灯或制作救命护符"
        else -> "晶石 ×${s.crystals}：可造护夜晶灯，或在工作台做一次性保命护符"
    }
}

/** A player's real dropped materials on knockout. No items are minted by recovery,
 * and older saves without drops load with none. Several independent deaths are safe.
 */
internal data class IslandLostPack(
    val wood: Int = 0,
    val stone: Int = 0,
    val fruit: Int = 0,
    val crystals: Int = 0,
) {
    fun isEmpty(): Boolean = wood == 0 && stone == 0 && fruit == 0 && crystals == 0
    fun merge(other: IslandLostPack): IslandLostPack = IslandLostPack(
        wood + other.wood, stone + other.stone, fruit + other.fruit, crystals + other.crystals)
    fun json(): JSONObject = JSONObject().put("wood",wood).put("stone",stone)
        .put("fruit",fruit).put("crystals",crystals)

    companion object {
        fun restore(raw: JSONObject?, world: ExpeditionState): Map<Int,IslandLostPack> {
            if (raw == null) return emptyMap()
            val output = mutableMapOf<Int,IslandLostPack>()
            val keys=raw.keys()
            while(keys.hasNext()) {
                val key=keys.next()
                val index=key.toIntOrNull() ?: continue
                if(index !in world.tiles.indices) continue
                val data=raw.optJSONObject(key) ?: continue
                val parts=listOf("wood","stone","fruit","crystals").map { data.optInt(it, -1) }
                if(parts.any { it !in 0..1000000 }) continue
                val pack=IslandLostPack(parts[0],parts[1],parts[2],parts[3])
                if(!pack.isEmpty())output[index]=pack
            }
            return output
        }
    }
}
