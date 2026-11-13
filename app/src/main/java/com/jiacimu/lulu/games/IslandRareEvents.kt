package com.jiacimu.lulu.games

import kotlin.math.abs
import kotlin.random.Random

/**
 * Rare but inspectable world event. The seed and completed turn decide whether a
 * tidal chest washes up. The encounter adds a physical, persistent chest; it
 * does NOT mint a crystal, material or achievement until a legal salvage action.
 */
internal object IslandRareEvents {
    fun arrives(seed: Int, turn: Int): Boolean =
        turn > 0 && turn % 48 == 0 &&
            Random(seed xor (turn * 7919) xor 0x71AC).nextInt(6) == 0

    fun advance(s: BlockIslandState): BlockIslandState {
        val w = s.world
        if (!arrives(w.seed,w.turns) || s.rareSupplyCaches.size >= 3 ||
            s.supplyCaches.size >= 8 || w.finished) return s

        val rng = Random(w.seed xor (w.turns * 1237) xor 0x2A45)
        val candidates = (0 until 64).map {
            rng.nextInt(2,w.width-2) to rng.nextInt(2,w.height-2)
        }
        val point = candidates.firstOrNull { (x,y) ->
            val i=w.index(x,y)
            w.cell(x,y) in listOf(0,4) &&
                i !in s.supplyCaches && i !in s.structures && i !in s.lostPacks &&
                abs(x-w.x)+abs(y-w.y)>5 &&
                w.friends.none { it.x==x && it.y==y } &&
                w.mobs.none { it.hp>0 && it.x==x && it.y==y } &&
                s.animals.none { it.x==x && it.y==y } &&
                BlockIslandRules.nextStep(s,w.x,w.y,x,y)!=null
        } ?: return s
        val index=w.index(point.first,point.second)
        val notice="✦ 潮汐秘藏出现于(${point.first},${point.second})！需真正抵达开箱，才有机会收获晶石"
        return s.copy(supplyCaches=s.supplyCaches+index,
            rareSupplyCaches=s.rareSupplyCaches+index,
            world=w.copy(notice=w.notice+" · "+notice,
                events=(w.events+notice).takeLast(150)))
    }
}
