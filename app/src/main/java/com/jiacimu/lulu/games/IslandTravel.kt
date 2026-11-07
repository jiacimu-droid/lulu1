package com.jiacimu.lulu.games

import org.json.JSONObject
import kotlin.math.abs

/** A model-selected game destination, not a second character goal or memory store. */
internal data class IslandTravelPlan(
    val x: Int,
    val y: Int,
    val targetTile: Int,
    val targetMobId: Int,
    val selectedTurn: Int,
    val lastStepTurn: Int,
    /** Physical tree harvests remaining from one model-selected plan. */
    val harvestRemaining: Int = 0,
) {
    fun json(): JSONObject = JSONObject().put("x",x).put("y",y)
        .put("targetTile",targetTile).put("targetMobId",targetMobId)
        .put("selectedTurn",selectedTurn).put("lastStepTurn",lastStepTurn)
        .put("harvestRemaining",harvestRemaining)
}

/** Continue only the exact chosen trip, once per existing world turn. No API timer. */
internal object IslandTravel {
    /** A reachable physical tree, not a fabricated resource reward. */
    fun reachableWood(s: BlockIslandState, id: String): Pair<Int,Int>? {
        val w=s.world
        val actor=w.friends.firstOrNull { it.id==id && it.hp>0 } ?: return null
        return w.tiles.indices.asSequence().filter { w.tiles[it]==1 }
            .map { it%w.width to it/w.width }
            .filter { (x,y) ->
                !(w.x==x && w.y==y) &&
                w.friends.none { it.x==x && it.y==y } &&
                w.mobs.none { it.hp>0 && it.x==x && it.y==y } &&
                s.animals.none { it.x==x && it.y==y }
            }
            .sortedWith(compareBy<Pair<Int,Int>>(
                { abs(actor.x-it.first)+abs(actor.y-it.second) },
                { w.index(it.first,it.second) }))
            .firstOrNull { (x,y) ->
                abs(actor.x-x)+abs(actor.y-y)<=1 ||
                    BlockIslandRules.nextStep(s,actor.x,actor.y,x,y)!=null
            }
    }

    fun restore(raw: JSONObject?, world: ExpeditionState): Map<String,IslandTravelPlan> {
        if (raw == null) return emptyMap()
        val restored = mutableMapOf<String,IslandTravelPlan>()
        for (id in world.friends.map { it.id }) {
            val j = raw.optJSONObject(id) ?: continue
            val plan = runCatching {
                IslandTravelPlan(j.getInt("x"),j.getInt("y"),j.getInt("targetTile"),
                    j.getInt("targetMobId"),j.getInt("selectedTurn"),j.getInt("lastStepTurn"),
                    j.optInt("harvestRemaining",0))
            }.getOrNull() ?: continue
            // A bad optional movement instruction must not erase the actual island save.
            if (world.inBounds(plan.x,plan.y) && plan.targetTile in 0..8 &&
                plan.targetMobId >= -3 && plan.selectedTurn in 0..world.turns &&
                plan.lastStepTurn in plan.selectedTurn..world.turns &&
                (if(plan.targetMobId==-3) plan.targetTile==1 &&
                    plan.harvestRemaining in 1..6 else plan.harvestRemaining==0))
                restored[id] = plan
        }
        return restored
    }

    fun afterChoice(s: BlockIslandState, next: BlockIslandState, id: String,
                    option: IslandActorOption): Map<String,IslandTravelPlan> {
        if (option.kind == "none") return next.companionTravel
        if (option.kind !in setOf("approach","regroup")) return next.companionTravel - id
        val a=next.world.friends.first { it.id==id }
        val (tx,ty)=if(option.kind=="regroup")next.world.x to next.world.y
            else option.x to option.y
        if(abs(a.x-tx)+abs(a.y-ty)<=1)return next.companionTravel-id
        val mob=if(option.kind=="regroup")null else s.world.mobs.firstOrNull {
            it.hp>0 && it.x==tx && it.y==ty
        }
        return next.companionTravel+(id to IslandTravelPlan(tx,ty,
            s.world.cell(tx,ty),
            if(option.kind=="regroup")-2 else mob?.id ?: -1,
            s.world.turns,next.world.turns))
    }

    /** Character-selected replan without granting a second movement in this turn. */
    fun choose(s:BlockIslandState,id:String,action:String,speech:String):BlockIslandState? {
        val w=s.world
        val actor=w.friends.firstOrNull { it.id==id && it.hp>0 } ?:return null
        if(w.finished)return null
        val parts=action.split(":")
        if(parts.firstOrNull()=="plan_harvest_wood") {
            val count=parts.getOrNull(1)?.toIntOrNull()
            if(parts.size!=2 || count !in setOf(3,6) ||
                s.companionTravel[id]?.targetMobId==-3 ||
                (s.houseProject?.ownerId==id &&
                    s.houseProject.phase in setOf("building","demolishing")) ||
                reachableWood(s,id)==null)return null
            val plan=IslandTravelPlan(actor.x,actor.y,1,-3,w.turns,w.turns,count!!)
            val event="行程["+id+"] 第"+w.turns+"回合：本人决定逐回合采木"+count+"次"+
                if(speech.isBlank())"" else "；他说：「"+speech+"」"
            return s.copy(companionTravel=s.companionTravel+(id to plan),
                world=w.copy(events=(w.events+event).takeLast(150),notice=event))
        }
        val (tx,ty,mobId)=when(parts.firstOrNull()) {
            "plan_regroup"->{
                if(parts.size!=1 || abs(actor.x-w.x)+abs(actor.y-w.y)<=1)return null
                Triple(w.x,w.y,-2)
            }
            "plan_support"->{
                if(parts.size!=2)return null
                val monster=parts[1].toIntOrNull()?.let { key ->
                    w.mobs.firstOrNull { it.id==key && it.hp>0 &&
                        abs(it.x-w.x)+abs(it.y-w.y)<=8 }
                } ?:return null
                Triple(monster.x,monster.y,monster.id)
            }
            else ->return null
        }
        if(s.companionTravel[id]?.targetMobId==mobId)return null
        val plan=IslandTravelPlan(tx,ty,w.cell(tx,ty),mobId,w.turns,w.turns)
        val event="行程["+id+"] 第"+w.turns+"回合：决定更换下一步合作目标"+
            if(speech.isBlank())"" else "；他说：「"+speech+"」"
        return s.copy(companionTravel=s.companionTravel+(id to plan),
            world=w.copy(events=(w.events+event).takeLast(150),notice=event))
    }
    fun cancel(s: BlockIslandState, id: String, speech: String = ""): BlockIslandState? {
        if (id !in s.companionTravel) return null
        return finish(s,id,"已取消前往原目标" + if(speech.isBlank()) "" else "；他说：「$speech」")
    }

    fun stopAll(s: BlockIslandState): BlockIslandState =
        s.companionTravel.keys.fold(s) { state,id -> finish(state,id,"自主操作已关闭，行程停止") }

    private fun finish(s: BlockIslandState,id: String,result: String): BlockIslandState {
        val event = "行程[$id] 第${s.world.turns}回合：$result"
        return s.copy(companionTravel=s.companionTravel-id,
            world=s.world.copy(events=(s.world.events+event).takeLast(150),notice=event))
    }

    fun advance(s: BlockIslandState, autonomousIds: Set<String>): BlockIslandState {
        var next = s
        for (id in s.companionTravel.keys) {
            val plan = next.companionTravel[id] ?: continue
            val w = next.world
            val actor = w.friends.firstOrNull { it.id == id }
            val stopped = when {
                w.finished -> "本局已结束"
                actor == null -> "伙伴已离队"
                actor.hp <= 0 -> "伙伴已倒地"
                id !in autonomousIds -> "自主操作已关闭"
                else -> null
            }
            if (stopped != null || actor == null) {
                next = finish(next,id,"行程停止：${stopped ?: "伙伴已离队"}")
                continue
            }
            if (plan.lastStepTurn >= w.turns ||
                w.events.any { it.startsWith(IslandActorPlay.eventPrefix(id,w.turns)) }) continue
            if(plan.targetMobId==-3) {
                val target=reachableWood(next,id)
                if(target==null) {
                    next=finish(next,id,"可达的树已经采完；剩余"+plan.harvestRemaining+"次未执行")
                    continue
                }
                val (tx,ty)=target
                if(abs(actor.x-tx)+abs(actor.y-ty)<=1) {
                    val remaining=plan.harvestRemaining-1
                    val event=IslandActorPlay.eventPrefix(id,w.turns)+
                        "按自己选择的采木计划砍下一棵树，真实木材+1；"+
                        if(remaining==0)"本次采集完成" else "还计划采木"+remaining+"次"
                    next=next.copy(world=w.copy(
                        tiles=w.tiles.toMutableList().also { it[w.index(tx,ty)]=0 },
                        wood=w.wood+1,events=(w.events+event).takeLast(150),notice=event),
                        companionTravel=if(remaining==0)next.companionTravel-id else
                            next.companionTravel+(id to plan.copy(x=tx,y=ty,
                                harvestRemaining=remaining,lastStepTurn=w.turns)))
                    continue
                }
                val step=BlockIslandRules.nextStep(next,actor.x,actor.y,tx,ty)
                if(step==null) {
                    next=finish(next,id,"采木道路受阻，材料未领取")
                    continue
                }
                val event=IslandActorPlay.eventPrefix(id,w.turns)+
                    "按已选采木计划走向树木("+tx+","+ty+")，走到("+
                    step.first+","+step.second+")"
                next=next.copy(world=w.copy(friends=w.friends.map {
                    if(it.id==id)it.copy(x=step.first,y=step.second)else it
                },events=(w.events+event).takeLast(150),notice=event),
                    companionTravel=next.companionTravel+(id to
                        plan.copy(x=tx,y=ty,lastStepTurn=w.turns)))
                continue
            }
            val enemy=if(plan.targetMobId>=0)
                w.mobs.firstOrNull { it.id==plan.targetMobId && it.hp>0 } else null
            val (tx,ty)=when {
                plan.targetMobId==-2 -> w.x to w.y
                enemy!=null -> enemy.x to enemy.y
                else -> plan.x to plan.y
            }
            val targetExists=when {
                plan.targetMobId==-2 -> w.hp>0
                plan.targetMobId>=0 -> enemy!=null
                else -> w.cell(plan.x,plan.y)==plan.targetTile
            }
            if (!targetExists) {
                next = finish(next,id,"原目标已改变，行程停止")
                continue
            }
            if (abs(actor.x-tx)+abs(actor.y-ty) <= 1) {
                next = finish(next,id,"已抵达原目标旁边")
                continue
            }
            val step = BlockIslandRules.nextStep(next,actor.x,actor.y,tx,ty)
            if (step == null) {
                next = finish(next,id,"通路受阻，未能抵达")
                continue
            }
            val arrived = abs(step.first-tx)+abs(step.second-ty) <= 1
            val event = IslandActorPlay.eventPrefix(id,w.turns) +
                "沿自己选定的路线前往("+tx+","+ty+")，走到(${step.first},${step.second})" +
                if (arrived) "；已到达目标旁边" else ""
            next = next.copy(world=w.copy(friends=w.friends.map {
                if (it.id == id) it.copy(x=step.first,y=step.second) else it
            },events=(w.events+event).takeLast(150),notice=if(arrived)event else w.notice),
                companionTravel=if (arrived) next.companionTravel-id else
                    next.companionTravel+(id to plan.copy(lastStepTurn=w.turns)))
        }
        return next
    }
}
