package com.jiacimu.lulu.games

import kotlin.math.abs

/**
 * Deterministic, persisted roguelike weapon rules. No weapon can hit through walls,
 * unexplored fog or another living monster. Switching equipment is not a combat turn.
 */
internal object RelicArsenal {
    val weapons = setOf("blade", "spear", "bow")
    fun label(id: String): String = when(id) {
        "spear" -> "长矛"; "bow" -> "弩弓"; else -> "短剑"
    }
    fun description(id: String): String = when(id) {
        "spear" -> "直线2格 · 稳定输出"
        "bow" -> "直线4格 · 每箭耗1弹"
        else -> "近战1格 · 可打断蓄力"
    }
    fun chestUnlock(chest: RelicChest): String? = when {
        chest.id==0 -> "spear"
        chest.id==2 || chest.tier==RelicTier.PRECIOUS -> "bow"
        chest.tier==RelicTier.RARE -> "spear"
        else -> null
    }
    fun canHit(s: RelicRun, x: Int, y: Int): Boolean {
        if(!s.visible(x,y) || s.mobs.none { it.hp>0 && it.x==x && it.y==y })return false
        val h=s.hero
        val dx=x-h.x
        val dy=y-h.y
        val d=abs(dx)+abs(dy)
        if(d==0)return false
        if(s.equipped=="blade")return d==1
        if(dx!=0 && dy!=0)return false
        if(s.equipped=="spear" && d>2)return false
        if(s.equipped=="bow" && (d>4 || s.bolts<=0))return false
        val sx=dx.compareTo(0)
        val sy=dy.compareTo(0)
        for(step in 1 until d) {
            val px=h.x+sx*step
            val py=h.y+sy*step
            if(!s.walk(px,py) ||
                s.mobs.any { it.hp>0 && it.x==px && it.y==py })return false
        }
        return true
    }
    fun damage(s: RelicRun, target: RelicMob): Int {
        val base=when(s.equipped) {
            "spear"->if(s.weapon>=2)4 else 3
            "bow"->if(s.weapon>=2)5 else 4
            else->if(s.weapon>=2)4 else 2
        }
        return (base-if(target.kind==1 || target.kind==3)1 else 0).coerceAtLeast(1)
    }
    fun monsterName(kind: Int): String = when(kind) {
        0->"洞穴徘徊者"; 1->"铠甲守卫"; 2->"狂热祭司"; else->"遗迹哨兵"
    }
    fun monsterDrop(s: RelicRun, mob: RelicMob): RelicLoot =
        RelicLoot(s.id+":mob:"+mob.id,when(mob.kind) {
            0->"暗影碎片";1->"锻铁徽片";2->"祭司秘纹";else->"哨兵之核"
        },if(mob.kind>=2)RelicTier.RARE else RelicTier.COMMON,
            42+mob.kind*38+s.level*8)
}
