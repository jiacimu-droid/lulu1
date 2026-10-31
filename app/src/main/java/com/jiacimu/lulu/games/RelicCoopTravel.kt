package com.jiacimu.lulu.games

import org.json.JSONObject

/** A saved, character-chosen route, not an automated choice of what to do next. */
internal data class RelicCoopPlan(val kind:String,val targetId:Int=-1,val lastStepTurn:Int=-1) {
    fun json():JSONObject=JSONObject().put("kind",kind).put("targetId",targetId)
        .put("lastStepTurn",lastStepTurn)
}

internal object RelicCoopTravel {
    fun restore(raw:JSONObject?,s:RelicRun):Map<String,RelicCoopPlan> {
        if(raw==null)return emptyMap()
        return s.allies.mapNotNull { actor ->
            val j=raw.optJSONObject(actor.id) ?:return@mapNotNull null
            val plan=runCatching {
                RelicCoopPlan(j.getString("kind"),j.optInt("targetId",-1),
                    j.optInt("lastStepTurn",-1))
            }.getOrNull() ?:return@mapNotNull null
            if(plan.kind !in setOf("support","regroup","chest") ||
                plan.lastStepTurn !in -1..s.turns ||
                (plan.kind!="regroup" && plan.targetId<0))null
            else actor.id to plan
        }.toMap()
    }
    fun target(s:RelicRun,p:RelicCoopPlan):Pair<Int,Int>? = when(p.kind) {
        "support"->s.mobs.firstOrNull { it.id==p.targetId && it.hp>0 }
            ?.let { it.x to it.y }
        "chest"->s.chests.firstOrNull { it.id==p.targetId && !it.opened &&
            it.id !in s.playerChestMarks }?.let { it.x to it.y }
        "regroup"->s.hero.takeIf { it.hp>0 }?.let { it.x to it.y }
        else->null
    }
    fun afterChoice(before:RelicRun,after:RelicRun,id:String,option:IslandActorOption):RelicRun {
        val plan=when(option.kind) {
            "support"->before.mobs.firstOrNull { it.x==option.x && it.y==option.y && it.hp>0 }
                ?.let { RelicCoopPlan("support",it.id,after.turns) }
            "approach_chest"->before.chests.firstOrNull {
                it.x==option.x && it.y==option.y && !it.opened
            }?.let { RelicCoopPlan("chest",it.id,after.turns) }
            "regroup"->RelicCoopPlan("regroup",lastStepTurn=after.turns)
            "none","reserve"->return after
            else->null
        }
        val actor=after.allies.firstOrNull { it.id==id } ?:return after
        val place=plan?.let { target(after,it) }
        val keep=place!=null &&
            RelicEscapeRules.distance(actor.x,actor.y,place.first,place.second)>1
        return after.copy(coopPlans=if(keep)after.coopPlans+(id to plan!!)
            else after.coopPlans-id)
    }
    fun cancel(s:RelicRun,id:String,speech:String=""):RelicRun? {
        if(id !in s.coopPlans)return null
        return s.copy(coopPlans=s.coopPlans-id).log(id,"travel",
            "停止自己选定的合作行程"+
                if(speech.isEmpty())"" else "；他说：「"+speech+"」")
    }
    /** Advance once using the actual board, not a free teleport or new AI decision. */
    fun advance(original:RelicRun,autonomousIds:Set<String>):RelicRun {
        var s=original
        for(id in original.coopPlans.keys) {
            val plan=s.coopPlans[id] ?:continue
            if(id !in autonomousIds || s.phase !in
                setOf(RelicPhase.EXPLORE,RelicPhase.EXTRACT,RelicPhase.DOWNED) ||
                plan.lastStepTurn>=s.turns || RelicActorPlay.acted(s,id))continue
            val a=s.allies.firstOrNull { it.id==id && it.hp>0 }
            if(a==null) { s=s.copy(coopPlans=s.coopPlans-id);continue }
            val goal=target(s,plan)
            if(goal==null) {
                s=s.copy(coopPlans=s.coopPlans-id)
                    .log(id,"travel","目标消失或已被打开，行程停止，下一轮由本人重新决定")
                continue
            }
            if(RelicEscapeRules.distance(a.x,a.y,goal.first,goal.second)<=1) {
                s=s.copy(coopPlans=s.coopPlans-id)
                    .log(id,"travel","已到达自己选择的目标附近，下一步由本人判断")
                continue
            }
            val step=RelicEscapeRules.path(s,a.x,a.y,goal.first,goal.second)
            if(step==null || !s.walk(step.first,step.second) ||
                s.hero.x==step.first && s.hero.y==step.second ||
                s.allies.any { it.id!=id && it.hp>0 &&
                    it.x==step.first && it.y==step.second } ||
                s.mobs.any { it.hp>0 && it.x==step.first && it.y==step.second }) {
                s=s.copy(coopPlans=s.coopPlans-id)
                    .log(id,"travel","路线受阻，停止原行程等待本人调整")
                continue
            }
            val trap=s.sites.firstOrNull { it.kind=="trap" && !it.spent &&
                it.x==step.first && it.y==step.second }
            val damage=(if(s.tile(step.first,step.second)==2)1 else 0)+
                (if(trap!=null)2 else 0)
            val actor=a.copy(x=step.first,y=step.second,
                hp=(a.hp-damage).coerceAtLeast(0),
                downs=a.downs+if(a.hp>0 && a.hp<=damage)1 else 0)
            val remaining=RelicEscapeRules.distance(actor.x,actor.y,
                goal.first,goal.second)>1 && actor.hp>0
            s=s.copy(allies=s.allies.map { if(it.id==id)actor else it },
                sites=if(trap==null)s.sites else s.sites.map {
                    if(it.id==trap.id)it.copy(spent=true)else it
                },
                coopPlans=if(remaining)s.coopPlans+(id to plan.copy(lastStepTurn=s.turns))
                    else s.coopPlans-id)
                .log(id,"decision","沿自己决定的"+when(plan.kind) {
                    "support"->"支援";"chest"->"找宝箱";else->"集合"
                }+"目标前进一步，到("+actor.x+","+actor.y+")"+
                    if(damage>0)"；踩到危险地形，受伤"+damage else "")
        }
        return s
    }
}
