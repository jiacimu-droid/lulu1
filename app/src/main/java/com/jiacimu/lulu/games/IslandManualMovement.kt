package com.jiacimu.lulu.games

/** A directional gesture is movement ONLY. Never translate collision into
 * gathering, opening chests, fighting or looting. Those are deliberate actions
 * using the shared target-action contract, which also exposes tool requirements.
 */
internal object IslandManualMovement {
    fun destination(s: BlockIslandState, dx: Int, dy: Int): Pair<Int,Int>? {
        if (kotlin.math.abs(dx)+kotlin.math.abs(dy)!=1) return null
        val w=s.world
        val x=w.x+dx
        val y=w.y+dy
        return (x to y).takeIf {
            BlockIslandRules.isWalkable(s,x,y) &&
                w.mobs.none { it.hp>0 && it.x==x && it.y==y } &&
                w.friends.none { it.x==x && it.y==y } &&
                s.animals.none { it.x==x && it.y==y }
        }
    }
}
