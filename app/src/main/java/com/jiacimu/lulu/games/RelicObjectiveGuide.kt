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

    fun prompt(run:RelicRun):String {
        val target=nextVisible(run)
        if(target!=null)return "◈ 查看"+target.label+" →"
        return if(run.carried.isEmpty())"◈ 尚未发现宝箱 · 探索迷雾或侦察"
            else "◈ 尚未发现出口 · 继续探索寻找撤离门"
    }
}
