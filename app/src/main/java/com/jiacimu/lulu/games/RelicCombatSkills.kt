package com.jiacimu.lulu.games

/**
 * Input contract for the real-time touch HUD: ordinary attacks NEVER read the
 * map-inspection cursor. D-pad facing is the only directional input.
 * Nothing here mutates the world or spends a model/API call.
 */
internal enum class RelicProfession(val label:String,val skill:String,val detail:String) {
    GUARDIAN("守护者","坚守","恢复2生命并抵挡一次敌人攻击"),
    SCOUT("探路者","远眺","探明周围五格迷雾，声音较轻"),
    MEDIC("医师","急救","不耗药剂恢复4生命"),
}

internal object RelicCombatSkills {
    fun cardinal(facing:Pair<Int,Int>):Boolean =
        kotlin.math.abs(facing.first)+kotlin.math.abs(facing.second)==1

    private fun priority(s:RelicRun,targets:List<RelicMob>):RelicMob? =
        targets.sortedWith(compareByDescending<RelicMob>{it.windup}
            .thenBy{it.hp>RelicArsenal.damage(s,it)}
            .thenBy{RelicEscapeRules.distance(s.hero.x,s.hero.y,it.x,it.y)}
            .thenBy{it.id}).firstOrNull()

    /** One directional tile at a time; never shoots through undiscovered fog/walls. */
    fun line(s:RelicRun,facing:Pair<Int,Int>,range:Int):List<RelicMob> {
        if(!cardinal(facing))return emptyList()
        val found=mutableListOf<RelicMob>()
        for(step in 1..range) {
            val x=s.hero.x+facing.first*step
            val y=s.hero.y+facing.second*step
            if(!s.bounds(x,y)||!s.visible(x,y)||!s.walk(x,y))break
            s.mobs.firstOrNull {it.hp>0 && it.x==x && it.y==y}?.let{found+=it}
        }
        return found
    }

    /** Short sword: facing first, then any physically adjacent foe.
     * Spear and bow: ONLY the facing lane, so an arrow never turns a corner.
     */
    fun attackTarget(s:RelicRun,facing:Pair<Int,Int>):RelicMob? {
        if(s.phase !in setOf(RelicPhase.EXPLORE,RelicPhase.EXTRACT) || !cardinal(facing))return null
        if(s.equipped=="blade") {
            val first=line(s,facing,1).firstOrNull()
            if(first!=null && RelicArsenal.canHit(s,first.x,first.y))return first
            return priority(s,s.mobs.filter { it.hp>0 && RelicArsenal.canHit(s,it.x,it.y) })
        }
        val range=if(s.equipped=="spear")2 else 4
        return line(s,facing,range).firstOrNull()?.takeIf{RelicArsenal.canHit(s,it.x,it.y)}
    }

    fun weaponSkillName(s:RelicRun):String=when(s.equipped) {
        "spear"->"贯穿突刺"
        "bow"->"穿云箭"
        else->"回旋斩"
    }

    /** Blade surrounds; spear pierces up to 2 tiles; bow can pierce two
     * enemies in its visible four-tile lane for two actual bolts.
     */
    fun weaponSkillTargets(s:RelicRun,facing:Pair<Int,Int>):List<RelicMob> = when(s.equipped) {
        "blade"->RelicArsenal.sweepTargets(s)
        "spear"->line(s,facing,2).take(2)
        "bow"->line(s,facing,4).take(2)
        else->emptyList()
    }

    fun canWeaponSkill(s:RelicRun,facing:Pair<Int,Int>):Boolean =
        s.phase in setOf(RelicPhase.EXPLORE,RelicPhase.EXTRACT) &&
            s.turns>=s.sweepReadyTurn &&
            (s.equipped!="bow" || s.bolts>=2) &&
            weaponSkillTargets(s,facing).size >= if(s.equipped=="blade")2 else 1

    fun weaponSkillHint(s:RelicRun,facing:Pair<Int,Int>):String = when {
        s.turns<s.sweepReadyTurn->"武器技能冷却 ${s.sweepReadyTurn-s.turns} 回合"
        s.equipped=="bow" && s.bolts<2->"穿云箭需要2支弩箭"
        s.equipped=="blade"->"回旋斩需要身边至少两个敌人"
        else->"请用方向盘对准射程内的敌人"
    }

    fun professionAvailable(s:RelicRun):Boolean {
        if(s.phase !in setOf(RelicPhase.EXPLORE,RelicPhase.EXTRACT) ||
            s.turns<s.professionReadyTurn)return false
        return when(s.profession) {
            RelicProfession.GUARDIAN->true
            RelicProfession.MEDIC->s.hero.hp<10
            RelicProfession.SCOUT->RelicEscapeRules.canSurvey(s)
        }
    }

    fun professionHint(s:RelicRun):String=when {
        s.turns<s.professionReadyTurn->"职业技能冷却 ${s.professionReadyTurn-s.turns} 回合"
        s.profession==RelicProfession.MEDIC->"满血时无需急救"
        s.profession==RelicProfession.SCOUT->"附近迷雾都已探明"
        else->"当前不能使用职业技能"
    }
}
