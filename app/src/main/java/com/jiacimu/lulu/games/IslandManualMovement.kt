package com.jiacimu.lulu.games

/** A directional gesture is movement ONLY. Never translate collision into
 * gathering, opening chests, fighting or looting. Those are deliberate actions
 * using the shared target-action contract, which also exposes tool requirements.
 */
internal object IslandManualMovement {
    /**
     * Explain an attempted blocked joystick step without spending a world turn,
     * gathering an item or saving the same notice on every held-key repeat.
     * Presentation only; actual rules still decide whether each action succeeds.
     */
    fun blockedHint(s: BlockIslandState, dx: Int, dy: Int): String? {
        if(kotlin.math.abs(dx)+kotlin.math.abs(dy)!=1)return null
        val w=s.world
        if(w.finished)return "当前旅程已结束，可从菜单重新开始"
        if(destination(s,dx,dy)!=null)return null
        val x=w.x+dx
        val y=w.y+dy
        if(!w.inBounds(x,y))return "已经到达小岛边缘，换个方向探索"
        val friend=w.friends.firstOrNull{it.x==x && it.y==y}
        val mob=w.mobs.firstOrNull{it.hp>0 && it.x==x && it.y==y}
        val animal=s.animals.firstOrNull{it.x==x && it.y==y}
        val interaction=islandTargetAction(s,x to y)
        return when {
            mob!=null -> "怪物挡住了去路 · 点「攻击」战斗或绕行"
            friend!=null && friend.hp<=0 -> if(s.fruit>=2)
                "伙伴倒地了 · 点「救援」扶起伙伴"
                else "伙伴倒地了 · 救援还需要2枚野果"
            friend!=null -> "伙伴就在前面 · 换个方向走"
            animal!=null -> "前方是"+IslandWildlife.name(animal.species)+
                " · 可用互动键接近它"
            interaction.action=="gather" -> if(interaction.enabled)
                interaction.label+" · 点「采集」再前进"
                else interaction.label+" · 先准备对应工具"
            interaction.action=="fish" -> if(interaction.enabled)
                "水边可以钓鱼 · 点互动键" else "水边无法直接走过 · 先制作鱼竿"
            else -> "前方有地形或建筑挡路 · 换条路或选择可互动目标"
        }
    }

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
