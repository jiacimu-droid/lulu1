package com.jiacimu.lulu.games

/** Visible expedition targets only. The UI may pan the camera or highlight a
 * known objective, never reveal unseen loot, move the hero or claim a reward. */
internal data class RelicObjectiveTarget(
    val x:Int,val y:Int,val label:String,
)

internal object RelicObjectiveGuide {
    fun nextVisible(run:RelicRun):RelicObjectiveTarget? {
        if(run.phase !in setOf(RelicPhase.EXPLORE,RelicPhase.EXTRACT)) return null
        val hero=run.hero
        val exitVisible=run.visible(run.exitX,run.exitY)
        if((run.carried.isNotEmpty() || run.phase==RelicPhase.EXTRACT) && exitVisible)
            return RelicObjectiveTarget(run.exitX,run.exitY,"已发现的撤离门")
        // Only already-seen unopened treasure is eligible. The map's fog
        // remains authoritative and undiscovered loot stays secret.
        val known=run.chests.filter { !it.opened && run.visible(it.x,it.y) }
            .minWithOrNull(compareBy<RelicChest> {
                RelicEscapeRules.distance(hero.x,hero.y,it.x,it.y)
            }.thenBy { it.id })
        if(run.carried.isEmpty() && known!=null)
            return RelicObjectiveTarget(known.x,known.y,"已发现的宝箱")
        return if(exitVisible)
            RelicObjectiveTarget(run.exitX,run.exitY,"已发现的撤离门")
        else null
    }

    /** Shortest route over tiles already discovered by the player.
     * This is only a visual estimate: it never spends turns, enters fog,
     * assumes unknown enemies are harmless or awards treasure.
     * An undiscovered corridor is reported as unknown, not as an easy route.
     */
    fun knownRouteSteps(run:RelicRun,target:RelicObjectiveTarget):Int? {
        if(!run.visible(target.x,target.y) ||
            !run.bounds(run.hero.x,run.hero.y))return null
        val start=run.index(run.hero.x,run.hero.y)
        val visited=IntArray(run.w*run.h){-1}
        val frontier=java.util.ArrayDeque<Pair<Int,Int>>()
        visited[start]=0
        frontier.add(run.hero.x to run.hero.y)
        while(frontier.isNotEmpty()) {
            val (x,y)=frontier.removeFirst()
            val step=visited[run.index(x,y)]
            if(RelicEscapeRules.distance(x,y,target.x,target.y)<=1) return step
            for((nx,ny) in listOf(x+1 to y,x-1 to y,x to y+1,x to y-1)) {
                if(!run.visible(nx,ny) || !run.walk(nx,ny))continue
                if(run.tile(nx,ny)==2)continue // corrosive ground is not a safe shortcut
                if(run.mobs.any{it.hp>0 && it.x==nx && it.y==ny})continue
                if(run.sites.any{!it.spent && it.kind=="trap" &&
                        it.x==nx && it.y==ny})continue
                val index=run.index(nx,ny)
                if(visited[index]>=0)continue
                visited[index]=step+1
                frontier.add(nx to ny)
            }
        }
        return null
    }

    fun prompt(run:RelicRun):String {
        val target=nextVisible(run)
        if(target!=null) {
            val steps=knownRouteSteps(run,target)
            return when(steps) {
                null -> "◈ "+target.label+"已发现 · 通路未探明，继续侦察"
                0 -> "◈ "+target.label+"就在附近 · 点击定位"
                else -> "◈ "+target.label+" · 已探明路线约"+steps+"步 · 点击定位"
            }
        }
        return if(run.carried.isEmpty())"◈ 尚未发现宝箱 · 探索迷雾或侦察"
            else "◈ 尚未发现出口 · 继续探索寻找撤离门"
    }
}
