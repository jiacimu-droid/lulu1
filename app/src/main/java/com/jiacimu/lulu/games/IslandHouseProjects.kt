package com.jiacimu.lulu.games

import org.json.JSONArray
import org.json.JSONObject
import kotlin.math.abs

/** One role-owned building task, saved alongside the authoritative island snapshot. */
internal data class IslandHouseProject(
    val ownerId: String,
    val left: Int,
    val top: Int,
    val phase: String = "building",
    val placed: List<Int> = emptyList(),
    val lastStepTurn: Int = -1,
) {
    fun json(): JSONObject = JSONObject().put("ownerId",ownerId).put("left",left)
        .put("top",top).put("phase",phase).put("placed",JSONArray(placed))
        .put("lastStepTurn",lastStepTurn)

    companion object {
        fun read(raw: JSONObject?,world: ExpeditionState): IslandHouseProject? = runCatching {
            val j=raw ?: return@runCatching null
            val positions=j.getJSONArray("placed").let { a ->
                (0 until a.length()).map { a.getInt(it) }
            }
            val p=IslandHouseProject(j.getString("ownerId"),j.getInt("left"),j.getInt("top"),
                j.getString("phase"),positions,j.optInt("lastStepTurn",-1))
            val possible=IslandHouseProjects.parts(world,p.left,p.top).map { it.first }
            if(p.ownerId.isBlank() || p.left !in 1 until world.width-3 ||
                p.top !in 1 until world.height-3 ||
                p.phase !in setOf("building","paused","complete","demolishing") ||
                positions.distinct().size!=positions.size ||
                positions.any { it !in possible } || p.lastStepTurn>world.turns) null else p
        }.getOrNull()
    }
}

internal object IslandHouseProjects {
    fun parts(w: ExpeditionState,left: Int,top: Int): List<Pair<Int,String>> =
        listOf(
            Triple(left+1,top+1,"floor"),
            Triple(left,top,"wall"),Triple(left+1,top,"wall"),
            Triple(left+2,top,"wall"),Triple(left,top+1,"wall"),
            Triple(left+2,top+1,"wall"),Triple(left,top+2,"wall"),
            Triple(left+2,top+2,"wall"),Triple(left+1,top+2,"door"),
        ).map { w.index(it.first,it.second) to it.third }

    fun sites(s: BlockIslandState,id: String): List<Pair<Int,Int>> {
        val w=s.world
        val actor=w.friends.firstOrNull { it.id==id && it.hp>0 } ?: return emptyList()
        if(s.houseProject!=null || w.finished)return emptyList()
        return (2 until w.width-4).flatMap { left ->
            (2 until w.height-4).mapNotNull { top ->
                val d=abs(left+1-actor.x)+abs(top+1-actor.y)
                if(d>11 || parts(w,left,top).any { (index,_) ->
                    w.tiles[index]!=0 || index in s.structures || index in s.supplyCaches ||
                    index==w.index(w.x,w.y) ||
                    w.friends.any { w.index(it.x,it.y)==index } ||
                    w.mobs.any { it.hp>0 && w.index(it.x,it.y)==index }
                }) null else Triple(left,top,d)
            }
        }.sortedWith(compareBy<Triple<Int,Int,Int>> { it.third }
            .thenBy { it.second }.thenBy { it.first })
            .asSequence().filter { (left,top,distance) ->
                distance<=1 || BlockIslandRules.nextStep(
                    s,actor.x,actor.y,left+1,top+1)!=null
            }.take(4).map { it.first to it.second }.toList()
    }

    fun isChangeAction(actionId: String): Boolean =
        actionId.startsWith("start_house:") ||
            actionId in setOf("pause_house","resume_house","demolish_house")

    fun choose(s: BlockIslandState,id: String,action: String,speech: String): BlockIslandState? {
        val w=s.world
        val old=s.houseProject
        val next=when {
            action.startsWith("start_house:") && old==null -> {
                val tokens=action.split(":")
                if(tokens.size!=3)return null
                val x=tokens[1].toIntOrNull() ?: return null
                val y=tokens[2].toIntOrNull() ?: return null
                if((x to y) !in sites(s,id))return null
                IslandHouseProject(id,x,y)
            }
            old==null || old.ownerId!=id -> return null
            action=="pause_house" && old.phase in setOf("building","demolishing") ->
                old.copy(phase="paused")
            action=="resume_house" && old.phase=="paused" -> old.copy(phase="building")
            action=="demolish_house" && old.phase!="demolishing" -> old.copy(phase="demolishing")
            else -> return null
        }
        val description=when(action.substringBefore(":")) {
            "start_house"->"决定开始一座3×3小屋工程"
            "pause_house"->"决定暂停建房，保留已经搭好的部分"
            "resume_house"->"决定继续之前的小屋工程"
            else->"决定逐步拆除自己建造的小屋"
        }
        val log="工程["+id+"] 第"+w.turns+"回合："+description+
            if(speech.isBlank())"" else "；他说：「"+speech+"」"
        return s.copy(houseProject=next,companionTravel=s.companionTravel-id,
            world=w.copy(notice=log,events=(w.events+log).takeLast(150)))
    }

    private fun log(s: BlockIslandState,id: String,message: String): BlockIslandState {
        val w=s.world
        val entry=IslandActorPlay.eventPrefix(id,w.turns)+message
        return s.copy(world=w.copy(notice=entry,events=(w.events+entry).takeLast(150)))
    }

    private fun walkCloser(s: BlockIslandState,id: String,x: Int,y: Int): BlockIslandState? {
        val w=s.world
        val actor=w.friends.first { it.id==id }
        val neighbors=listOf(x+1 to y,x-1 to y,x to y+1,x to y-1)
            .sortedBy { abs(it.first-actor.x)+abs(it.second-actor.y) }
        for ((nx,ny) in neighbors) {
            if(!BlockIslandRules.isWalkable(s,nx,ny) ||
                nx==w.x && ny==w.y ||
                w.friends.any { it.id!=id && it.x==nx && it.y==ny } ||
                w.mobs.any { it.hp>0 && it.x==nx && it.y==ny })continue
            val step=BlockIslandRules.nextStep(s,actor.x,actor.y,nx,ny) ?: continue
            if(w.friends.any { it.id!=id && it.x==step.first && it.y==step.second })continue
            val updated=w.copy(friends=w.friends.map {
                if(it.id==id)it.copy(x=step.first,y=step.second)else it
            })
            return s.copy(world=updated)
        }
        return null
    }

    /** At most one physical move/build/removal per existing game turn. */
    fun advance(s: BlockIslandState,autonomousIds: Set<String>): BlockIslandState {
        val p=s.houseProject ?: return s
        if(p.phase !in setOf("building","demolishing") ||
            p.lastStepTurn>=s.world.turns)return s
        val w=s.world
        val id=p.ownerId
        val actor=w.friends.firstOrNull { it.id==id && it.hp>0 } ?: return s
        if(id !in autonomousIds ||
            w.events.any { it.startsWith(IslandActorPlay.eventPrefix(id,w.turns)) })return s
        val all=parts(w,p.left,p.top)
        val part=if(p.phase=="building")all.firstOrNull { it.first !in p.placed }
            else all.lastOrNull { it.first in p.placed }
        if(part==null) {
            val done=s.copy(houseProject=if(p.phase=="building")p.copy(phase="complete")else null)
            return log(done,id,if(p.phase=="building")"自己的小屋建完了" else "小屋已拆完")
        }
        val (index,kind)=part
        val tx=index%w.width
        val ty=index/w.width
        val pNext=p.copy(lastStepTurn=w.turns)
        if(abs(actor.x-tx)+abs(actor.y-ty)!=1) {
            val moved=walkCloser(s,id,tx,ty)
            if(moved==null)return log(s.copy(houseProject=pNext.copy(phase="paused")),
                id,"施工道路受阻，工程暂停")
            return log(moved.copy(houseProject=pNext),id,
                "沿实际道路走向下一处施工位置("+tx+","+ty+")")
        }
        if(p.phase=="building") {
            val occupied=(w.x==tx && w.y==ty) ||
                w.friends.any { it.x==tx && it.y==ty } ||
                w.mobs.any { it.hp>0 && it.x==tx && it.y==ty }
            if(occupied || w.cell(tx,ty)!=0 || index in s.structures ||
                index in s.supplyCaches)
                return log(s.copy(houseProject=pNext.copy(phase="paused")),
                    id,"施工格已被占用或改动，暂停以保护现有建筑")
            val cost=if(kind=="floor")1 else 2
            if(w.wood<cost)
                return log(s.copy(houseProject=pNext.copy(phase="paused")),
                    id,"木材不足，已经停工；可先采集再决定续建")
            val cells=w.tiles.toMutableList().also { it[index]=if(kind=="wall")3 else 4 }
            val count=p.placed.size+1
            val next=s.copy(world=w.copy(tiles=cells,wood=w.wood-cost),
                structures=if(kind=="door")s.structures+(index to "door")else s.structures,
                houseProject=pNext.copy(placed=p.placed+index,
                    phase=if(count==all.size)"complete" else "building"))
            return log(next,id,"真实消耗木材，建造"+kind+" ("+count+"/"+all.size+")")
        }
        // Never demolish player-made changes, even if they overlap a former plan.
        val expected=if(kind=="wall")3 else 4
        if(w.cell(tx,ty)!=expected ||
            (if(kind=="door")s.structures[index]!="door" else index in s.structures))
            return log(s.copy(houseProject=pNext.copy(phase="paused")),id,
                "这处建筑已经被修改，暂停拆除，避免破坏别人的作品")
        if((w.x==tx && w.y==ty) ||
            w.friends.any { it.x==tx && it.y==ty } ||
            w.mobs.any { it.hp>0 && it.x==tx && it.y==ty })
            return log(s.copy(houseProject=pNext.copy(phase="paused")),id,
                "待拆位置有人或怪物，暂停拆除")
        val cells=w.tiles.toMutableList().also { it[index]=0 }
        val remaining=p.placed-index
        val next=s.copy(world=w.copy(tiles=cells,wood=w.wood+1),
            structures=if(kind=="door")s.structures-index else s.structures,
            houseProject=if(remaining.isEmpty())null else pNext.copy(placed=remaining))
        return log(next,id,"拆除自己建的一格并回收1木；剩余"+remaining.size+"格")
    }
}
