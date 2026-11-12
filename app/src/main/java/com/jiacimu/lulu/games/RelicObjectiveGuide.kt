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

    /** The player can choose whether to keep hunting loot or head home.
     * Neither option hides/reveals map cells, spends a turn, or overrules the
     * player's intention. Unseen objects cannot be targeted from the HUD. */
    fun visibleChest(run:RelicRun):RelicObjectiveTarget? {
        if(run.phase !in setOf(RelicPhase.EXPLORE,RelicPhase.EXTRACT))return null
        val hero=run.hero
        return run.chests.filter { !it.opened && run.visible(it.x,it.y) }
            .minWithOrNull(compareBy<RelicChest> {
                RelicEscapeRules.distance(hero.x,hero.y,it.x,it.y)
            }.thenBy { it.id })
            ?.let { RelicObjectiveTarget(it.x,it.y,"已发现的宝箱") }
    }

    fun visibleExit(run:RelicRun):RelicObjectiveTarget? =
        if(run.phase in setOf(RelicPhase.EXPLORE,RelicPhase.EXTRACT) &&
            run.visible(run.exitX,run.exitY))
            RelicObjectiveTarget(run.exitX,run.exitY,"已发现的撤离门")
        else null

    /** A manual map tap can target any *currently* discovered unopened chest,
     * not just the nearest HUD suggestion. Never keep a stale opened target. */
    fun selectedVisible(run:RelicRun,x:Int,y:Int):RelicObjectiveTarget? {
        if(run.phase !in setOf(RelicPhase.EXPLORE,RelicPhase.EXTRACT) ||
            !run.bounds(x,y) || !run.visible(x,y)) return null
        if(run.chests.any { !it.opened && it.x==x && it.y==y })
            return RelicObjectiveTarget(x,y,"已发现的宝箱")
        return visibleExit(run)?.takeIf { it.x==x && it.y==y }
    }

    fun optionLabel(run:RelicRun,target:RelicObjectiveTarget):String {
        val knownSteps=knownRouteSteps(run,target)
        return if(knownSteps==null) "路径未探明"
            else if(knownSteps==0) "就在附近"
            else "已知路线约${knownSteps}步"
    }

    /** Shortest route over already-discovered safe tiles.
     *
     * The returned path includes the hero's tile and stops next to the
     * interactive object, not inside its occupied cell. It is a *drawing
     * hint*, not a turn-spending movement plan. Never reveal hidden map
     * cells or assume dangerous/occupied tiles are traversable.
     */
    fun knownRouteTiles(run:RelicRun,target:RelicObjectiveTarget):List<Pair<Int,Int>>? {
        if(run.phase !in setOf(RelicPhase.EXPLORE,RelicPhase.EXTRACT) ||
            !run.visible(target.x,target.y) ||
            !run.bounds(run.hero.x,run.hero.y)) return null
        val start=run.index(run.hero.x,run.hero.y)
        val previous=IntArray(run.w*run.h){-1}
        val visited=BooleanArray(run.w*run.h)
        val frontier=java.util.ArrayDeque<Pair<Int,Int>>()
        visited[start]=true
        frontier.add(run.hero.x to run.hero.y)
        while(frontier.isNotEmpty()) {
            val (x,y)=frontier.removeFirst()
            if(RelicEscapeRules.distance(x,y,target.x,target.y)<=1) {
                val path=mutableListOf<Pair<Int,Int>>()
                var index=run.index(x,y)
                while(index!=start) {
                    path.add(index%run.w to index/run.w)
                    index=previous[index]
                    if(index<0)return null
                }
                path.add(run.hero.x to run.hero.y)
                path.reverse()
                return path
            }
            for((nx,ny) in listOf(x+1 to y,x-1 to y,x to y+1,x to y-1)) {
                if(!run.visible(nx,ny) || !run.walk(nx,ny))continue
                if(run.tile(nx,ny)==2)continue // corrosive ground is not a shortcut
                if(run.mobs.any { it.hp>0 && it.x==nx && it.y==ny })continue
                if(run.sites.any { !it.spent && it.kind=="trap" &&
                        it.x==nx && it.y==ny })continue
                val index=run.index(nx,ny)
                if(visited[index])continue
                visited[index]=true
                previous[index]=run.index(x,y)
                frontier.add(nx to ny)
            }
        }
        return null
    }

    /** Steps use the *same* route as the on-map guide. No two path models. */
    fun knownRouteSteps(run:RelicRun,target:RelicObjectiveTarget):Int? =
        knownRouteTiles(run,target)?.size?.minus(1)

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
