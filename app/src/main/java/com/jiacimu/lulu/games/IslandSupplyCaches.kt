package com.jiacimu.lulu.games

import kotlin.math.abs

/**
 * Four fixed, reachable supply stashes in each island. Locations are generated once and
 * serialized; opening a stash never changes its position or grants the reward twice.
 */
internal object IslandSupplyCaches {
    fun generate(state: BlockIslandState): List<Int> {
        val w = state.world
        if (!BlockIslandRules.isWalkable(state, w.x, w.y)) return emptyList()
        val reachable = mutableSetOf(w.index(w.x, w.y))
        val queue = java.util.ArrayDeque<Pair<Int, Int>>()
        queue.add(w.x to w.y)
        while (queue.isNotEmpty()) {
            val (x, y) = queue.removeFirst()
            for ((nx, ny) in listOf(x+1 to y, x-1 to y, x to y+1, x to y-1)) {
                if (!BlockIslandRules.isWalkable(state, nx, ny)) continue
                if (reachable.add(w.index(nx, ny))) queue.add(nx to ny)
            }
        }
        val candidates = reachable.map { it % w.width to it / w.width }.filter { (x, y) ->
            w.cell(x, y) in setOf(0, 4) &&
                abs(x-w.x)+abs(y-w.y)>3 &&
                w.index(x,y) !in state.structures &&
                w.friends.none { it.x==x && it.y==y } &&
                w.mobs.none { it.x==x && it.y==y && it.hp>0 }
        }
        val anchors = listOf(
            w.width/4 to w.height/4,
            w.width*3/4 to w.height/4,
            w.width/4 to w.height*3/4,
            w.width*3/4 to w.height*3/4,
        )
        val selected = mutableListOf<Pair<Int,Int>>()
        for ((ax, ay) in anchors) {
            val choice = candidates.filter { (x,y) ->
                selected.none { (sx,sy) -> abs(x-sx)+abs(y-sy)<4 }
            }.minWithOrNull(compareBy<Pair<Int,Int>>(
                { abs(it.first-ax)+abs(it.second-ay) }, { w.index(it.first,it.second) }))
            if(choice!=null)selected+=choice
        }
        return selected.map { w.index(it.first,it.second) }
    }

    fun available(state: BlockIslandState, x: Int, y: Int): Boolean =
        state.world.inBounds(x,y) &&
            state.world.index(x,y) in state.supplyCaches &&
            state.world.index(x,y) !in state.openedSupplies

    fun remaining(state: BlockIslandState): Int =
        state.supplyCaches.count { it !in state.openedSupplies }
}
