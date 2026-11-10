package com.jiacimu.lulu.games

import kotlin.math.abs

/**
 * The final chapter of the existing island expedition, not a scripted AI
 * companion. The guardian is a real persisted ExpeditionMob; the encounter
 * only unlocks after both physical landmarks, a sword and the three-night
 * survival milestone. Regular player/companion attacks and obstacle rules
 * remain authoritative.
 */
internal object IslandGuardianEncounter {
    const val id = -7001
    const val hp = 18
    const val challengeId = "island_guardian"
    const val title = "南岭巨岩守卫"

    fun ready(s:BlockIslandState):Boolean =
        !s.guardianCleared && s.nightsSurvived>=3 && "sword" in s.tools &&
            IslandExpeditionTrail.landmarks.all { it.id in s.awardedChallenges }

    fun active(s:BlockIslandState):ExpeditionMob? =
        s.world.mobs.firstOrNull { it.id==id && it.hp>0 }

    fun remainingRequirements(s:BlockIslandState):String = when {
        IslandExpeditionTrail.next(s)!=null -> "先踏足两座探索地标"
        "sword" !in s.tools -> "先在工作台制作石剑"
        s.nightsSurvived<3 -> "继续守住3个夜晚（${s.nightsSurvived}/3）"
        s.guardianCleared -> "已击败，守卫纪念徽章永久保留"
        active(s)!=null -> "前往地图中的赤金守卫，击败它"
        else -> "前往南岭，唤醒守卫"
    }

    /** No duplicate boss on reload, rejected action or animation frame. */
    fun spawnIfReady(s:BlockIslandState):BlockIslandState {
        if(!ready(s) || s.world.mobs.any { it.id==id }) return s
        val w=s.world
        val south=IslandExpeditionTrail.landmarks.last()
        val (startX,y)=IslandExpeditionTrail.position(s,south)
        // The new island has a cleared southern coastal road; migrated maps
        // have a different but equally traversable old east/south corridor.
        val candidates=listOf(
            (startX+3).coerceAtMost(w.width-2) to y,
            (startX+2).coerceAtMost(w.width-2) to y,
            (startX+1).coerceAtMost(w.width-2) to y,
            (startX-1).coerceAtLeast(1) to y,
        ).distinct()
        val place=candidates.firstOrNull { (x,ty) ->
            BlockIslandRules.isWalkable(s,x,ty) &&
                !IslandSupplyCaches.available(s,x,ty) &&
                abs(w.x-x)+abs(w.y-ty)>2 &&
                w.friends.none { it.hp>0 && it.x==x && it.y==ty } &&
                w.mobs.none { it.hp>0 && it.x==x && it.y==ty } &&
                s.animals.none { it.x==x && it.y==ty }
        } ?: return s
        val event="⚔ 南岭巨岩守卫在古树附近苏醒！它真实占据道路，准备好石剑和食物。"
        return s.copy(world=w.copy(
            mobs=w.mobs+ExpeditionMob(id,place.first,place.second,hp),
            notice=w.notice+" · "+event,
            events=(w.events+event).takeLast(150),
        ))
    }

    /** Called before normal dead-monster cleanup so a companion's lethal
     * strike also resolves the encounter; the reward board is run once at
     * the end of the *same valid turn*. */
    fun recordVictory(s:BlockIslandState):BlockIslandState {
        if(s.guardianCleared || s.world.mobs.none { it.id==id && it.hp==0 }) return s
        return s.copy(guardianCleared=true)
    }

    fun impendingHeavyAttack(s:BlockIslandState):Boolean =
        active(s)!=null && (s.world.turns+1)%3==0
}
