package com.jiacimu.lulu.games

import com.jiacimu.lulu.ai.CompanionContextMode
import com.jiacimu.lulu.ai.LuluAiServices
import com.jiacimu.lulu.ai.ModelUsage
import org.json.JSONArray
import org.json.JSONObject
import kotlin.math.abs

/** The model chooses among real options; existing game state remains authoritative. */
internal data class IslandActorOption(val id: String, val name: String, val kind: String, val x: Int = -1, val y: Int = -1)
internal data class IslandActorChoice(val actionId: String, val speech: String)

internal object IslandActorPlay {
    fun eventPrefix(id: String, turn: Int) = "队友[" + id + "] 第" + turn + "回合："

    fun options(s: BlockIslandState, id: String, canAct: Boolean = true): List<IslandActorOption> {
        val w = s.world
        val a = w.friends.firstOrNull { it.id == id && it.hp > 0 } ?: return emptyList()
        val result = mutableListOf(IslandActorOption("none", "观察、交谈或保持安静", "none"))
        if (!canAct || w.finished) return result
        for ((x, y) in listOf(a.x+1 to a.y, a.x-1 to a.y, a.x to a.y+1, a.x to a.y-1)) {
            if (!w.inBounds(x, y)) continue
            val mob = w.mobs.firstOrNull { it.hp > 0 && it.x == x && it.y == y }
            if (mob != null) {
                result += IslandActorOption("fight:$x:$y", "攻击坐标($x,$y)的怪物", "fight", x, y)
                continue
            }
            if (w.cell(x, y) in setOf(1, 2))
                result += IslandActorOption("gather:$x:$y",
                    "采集($x,$y)的" + if (w.cell(x,y)==1) "树" else "矿", "gather", x, y)
            val occupied = w.friends.any { it.hp>0 && it.x==x && it.y==y } ||
                w.x==x && w.y==y
            if (BlockIslandRules.isWalkable(s,x,y) && !occupied)
                result += IslandActorOption("move:$x:$y", "走到($x,$y)", "move", x, y)
            if (w.cell(x,y)==0 && !occupied && w.index(x,y) !in s.structures) {
                if (w.wood>=1) {
                    result += IslandActorOption("plant:$x:$y", "种树，消耗1木", "plant", x, y)
                    result += IslandActorOption("floor:$x:$y", "铺地板，消耗1木", "floor", x, y)
                }
                if (w.wood>=2)
                    result += IslandActorOption("wall:$x:$y", "建造木墙，消耗2木", "wall", x, y)
            }
        }
        if (a.hp<8 && s.structures.any { (index,kind) ->
                kind in setOf("campfire","bed") &&
                    abs(index%w.width-a.x)+abs(index/w.width-a.y)<=1 })
            result += IslandActorOption("rest", "在营地休息恢复生命", "rest")
        return result
    }

    fun parse(raw: String): IslandActorChoice? = runCatching {
        val j = JSONObject(raw.trim())
        if (!j.has("speech") || j.get("speech") !is String) return@runCatching null
        val action = j.getString("actionId")
        if (action.isBlank()) return@runCatching null
        IslandActorChoice(action, j.getString("speech").trim())
    }.getOrNull()

    /** Null means stale, repeated or physically invalid; no action is invented. */
    fun apply(s: BlockIslandState, id: String, turn: Int, choice: IslandActorChoice): BlockIslandState? {
        val w = s.world
        if (w.turns != turn || w.friends.none { it.id==id && it.hp>0 } ||
            w.events.any { it.startsWith(eventPrefix(id,turn)) }) return null
        val a = options(s,id).firstOrNull { it.id==choice.actionId } ?: return null
        var next = w
        val outcome = when (a.kind) {
            "none" -> "观察现场"
            "move" -> {
                next = w.copy(friends=w.friends.map {
                    if (it.id==id) it.copy(x=a.x,y=a.y) else it
                })
                "自己前进到("+a.x+","+a.y+")"
            }
            "gather" -> {
                val tile=w.cell(a.x,a.y)
                val tiles=w.tiles.toMutableList().also { it[w.index(a.x,a.y)]=0 }
                next=w.copy(tiles=tiles,wood=w.wood+if(tile==1) 1 else 0,
                    stone=w.stone+if(tile==2) 1 else 0)
                if(tile==1) "自己采木+1" else "自己采石+1"
            }
            "fight" -> {
                next=w.copy(mobs=w.mobs.map {
                    if(it.hp>0 && it.x==a.x && it.y==a.y)
                        it.copy(hp=(it.hp-2).coerceAtLeast(0)) else it
                })
                "自己攻击怪物，造成2点伤害"
            }
            "plant","floor","wall" -> {
                val tiles=w.tiles.toMutableList().also {
                    it[w.index(a.x,a.y)] = when(a.kind) {"plant"->1;"wall"->3;else->4}
                }
                next=w.copy(tiles=tiles,wood=w.wood-if(a.kind=="wall") 2 else 1)
                when(a.kind) {"plant"->"自己种树";"wall"->"自己建造木墙";else->"自己铺地板"}
            }
            "rest" -> {
                next=w.copy(friends=w.friends.map {
                    if(it.id==id) it.copy(hp=(it.hp+3).coerceAtMost(8)) else it
                })
                "自己在营地休息"
            }
            else -> return null
        }
        val say = if(choice.speech.isBlank()) "" else "；他说：「"+choice.speech+"」"
        val event=eventPrefix(id,turn)+outcome+say
        next=next.copy(events=(next.events+event).takeLast(150),notice=event)
        return s.copy(world=next)
    }

    fun facts(s: BlockIslandState, id: String, heard: String): String {
        val w=s.world
        val a=w.friends.first { it.id==id }
        val nearby=mutableListOf<String>()
        for (dy in -4..4) for(dx in -4..4) {
            val x=a.x+dx;val y=a.y+dy
            if(abs(dx)+abs(dy)>5 || !w.inBounds(x,y)) continue
            val tile=when(w.cell(x,y)){1->"树";2->"石矿";3->"墙";8->"水";else->""}
            val mob=if(w.mobs.any{it.hp>0&&it.x==x&&it.y==y}) "怪物" else ""
            if(tile.isNotEmpty()||mob.isNotEmpty()) nearby.add("("+x+","+y+")"+tile+mob)
        }
        val canAct=w.events.none{it.startsWith(eventPrefix(id,w.turns))}
        return JSONObject().put("game","方块小岛").put("turn",w.turns)
            .put("selfId",id).put("selfLocation",JSONArray(listOf(a.x,a.y)))
            .put("selfHp",a.hp).put("playerLocation",JSONArray(listOf(w.x,w.y)))
            .put("playerHp",w.hp).put("sharedWood",w.wood).put("sharedStone",w.stone)
            .put("nearby",JSONArray(nearby))
            .put("recentEvents",JSONArray(w.events.takeLast(10)))
            .put("playerSpeech",heard)
            .put("options",JSONArray(options(s,id,canAct).map {
                JSONObject().put("id",it.id).put("meaning",it.name)
            })).toString()
    }

    suspend fun decide(s: BlockIslandState,id: String,heard: String): Result<IslandActorChoice> =
        LuluAiServices.gateway.generate(
            characterId=id,
            facts=facts(s,id,heard),
            instruction="""你是角色本人，正在与玩家一起玩方块小岛。
按你自己的性格、兴趣、关系和当下情况，自主决定做什么；不必跟随玩家。可以探索、采集、建设、攻击或静静观察。
只选择 options 中真实存在的一个 id，不得凭空创造资源或宣称行动已成功。你可以说话也可以沉默。
只输出有效 JSON：{"actionId":"options里的id","speech":"你此刻愿意自然说出口的话，不想说就为空字符串"}。
行动将由游戏引擎独立校验、执行、记录结果；台词不是执行成功的证据。""",
            source="游戏 · 方块小岛自主同行",
            title="方块小岛伙伴自主决策",
            usage=ModelUsage.Game,
            contextMode=CompanionContextMode.CharacterAndScenario,
            maxTokens=260,
        ).mapCatching { parse(it.text) ?: error("游戏模型未返回有效的结构化选择") }
}
