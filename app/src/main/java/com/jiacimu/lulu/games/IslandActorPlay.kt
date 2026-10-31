package com.jiacimu.lulu.games

import com.jiacimu.lulu.ai.CompanionContextMode
import com.jiacimu.lulu.ai.LuluAiServices
import com.jiacimu.lulu.ai.ModelUsage
import org.json.JSONArray
import org.json.JSONObject
import kotlin.math.abs

/** The model chooses among real options; existing game state remains authoritative. */
internal data class IslandActorOption(val id: String, val name: String, val kind: String, val x: Int = -1, val y: Int = -1)
internal data class IslandActorChoice(
    val actionId: String,
    val speech: String,
    /** One character-owned intention, not a completed action or persistent task. */
    val intent: String = "",
)

internal object IslandActorPlay {
    fun eventPrefix(id: String, turn: Int) = "队友[" + id + "] 第" + turn + "回合："

    fun options(s: BlockIslandState, id: String, canAct: Boolean = true): List<IslandActorOption> {
        val w = s.world
        val a = w.friends.firstOrNull { it.id == id && it.hp > 0 } ?: return emptyList()
        val result = mutableListOf(IslandActorOption("none", "观察、交谈或保持安静", "none"))
        if (id in s.companionTravel && !w.finished)
            result += IslandActorOption("stop_travel", "停止自己正在执行的行程，不再继续前往原目标", "stop_travel")
        val project=s.houseProject
        if(project?.ownerId==id) {
            if(project.phase in setOf("building","demolishing"))
                result += IslandActorOption("pause_house","暂时停下当前建造或拆除工程","pause_house")
            if(project.phase=="paused")
                result += IslandActorOption("resume_house","继续已暂停的建房工程","resume_house")
            if(project.phase!="demolishing")
                result += IslandActorOption("demolish_house","逐步拆除自己建造的小屋","demolish_house")
        }
        if(project==null && !w.finished) {
            for((x,y) in IslandHouseProjects.sites(s,id).take(3)) {
                result += IslandActorOption("start_house:"+x+":"+y,
                    "主动选择位置("+x+","+y+")建一座3×3小屋，约需17木；需要逐步施工","start_house",x,y)
            }
        }
        if (!canAct || w.finished) return result
        if (s.fruit > 0 && w.hp < 12 &&
            abs(a.x - w.x) + abs(a.y - w.y) <= 1)
            result += IslandActorOption("help_player", "把一颗野果送给玩家，为他恢复生命", "help_player")
        for ((x, y) in listOf(a.x+1 to a.y, a.x-1 to a.y, a.x to a.y+1, a.x to a.y-1)) {
            val injured = w.friends.firstOrNull {
                it.id != id && it.hp <= 0 && it.x == x && it.y == y
            }
            if (injured != null && s.fruit >= 2)
                result += IslandActorOption("revive:$x:$y",
                    "花两颗野果救起附近倒地队友", "revive", x, y)
            if (!w.inBounds(x, y)) continue
            val mob = w.mobs.firstOrNull { it.hp > 0 && it.x == x && it.y == y }
            if (mob != null) {
                result += IslandActorOption("fight:$x:$y", "攻击坐标($x,$y)的怪物", "fight", x, y)
                continue
            }
            if (w.cell(x, y) in setOf(1, 2))
                result += IslandActorOption("gather:$x:$y",
                    "采集($x,$y)的" + if (w.cell(x,y)==1) "树" else "矿", "gather", x, y)
            val occupied = w.friends.any { it.x == x && it.y == y } ||
                w.x == x && w.y == y
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
        // Let the person set their *own* destinations, not just react to adjacent tiles.
        // Each candidate exposes only a legal first step; never teleport or spend a
        // background model turn to walk all the way to a target.
        val destinations = (0 until w.height).flatMap { y ->
            (0 until w.width).mapNotNull { x ->
                val distance = abs(a.x - x) + abs(a.y - y)
                if (distance !in 2..10) return@mapNotNull null
                val tile = w.cell(x, y)
                val enemy = w.mobs.any { it.hp > 0 && it.x == x && it.y == y }
                val feature = when {
                    enemy -> "怪物"
                    tile == 1 -> "树林"
                    tile == 2 -> "石矿"
                    else -> return@mapNotNull null
                }
                Triple(x to y, feature, distance)
            }
        }.sortedWith(compareBy<Triple<Pair<Int, Int>, String, Int>> { it.third }
            .thenBy { it.first.second }.thenBy { it.first.first })
        // Limited map excerpt protects prompt tokens; this never limits the actor's
        // number of decisions or changes the meaning of a valid model selection.
        for ((target, feature, _) in destinations.take(16)) {
            val step = BlockIslandRules.nextStep(s, a.x, a.y, target.first, target.second)
                ?: continue
            if (w.friends.any { it.id != a.id &&
                    it.x == step.first && it.y == step.second } ||
                w.x == step.first && w.y == step.second) continue
            result += IslandActorOption(
                "approach:${target.first}:${target.second}",
                "主动前往(${target.first},${target.second})附近的${feature}（沿路线持续前往，到目标旁停下）",
                "approach", target.first, target.second,
            )
        }
        if (a.hp<8 && s.structures.any { (index,kind) ->
                kind in setOf("campfire","bed") &&
                    abs(index%w.width-a.x)+abs(index/w.width-a.y)<=1 })
            result += IslandActorOption("rest", "在营地休息恢复生命", "rest")
        return result
    }

    fun parse(raw: String): IslandActorChoice? = runCatching {
        // Common JSON code fences are presentation, not additional semantic decisions.
        val input = raw.trim()
        val fence = 96.toChar().toString().repeat(3)
        val json = if (input.startsWith(fence))
            input.substringAfter('\n').substringBeforeLast(fence).trim() else input
        val j = JSONObject(json)
        if (!j.has("speech") || j.get("speech") !is String) return@runCatching null
        val action = j.getString("actionId")
        if (action.isBlank()) return@runCatching null
        if (j.has("intent") && j.get("intent") !is String) return@runCatching null
        IslandActorChoice(action, j.getString("speech").trim(), j.optString("intent", "").trim())
    }.getOrNull()

    /** Null means stale, repeated or physically invalid; no action is invented. */
    fun apply(s: BlockIslandState, id: String, turn: Int, choice: IslandActorChoice): BlockIslandState? {
        val w = s.world
        if (w.turns != turn || w.finished || w.friends.none { it.id==id && it.hp>0 }) return null
        if (choice.actionId == "stop_travel") return IslandTravel.cancel(s,id,choice.speech)
        if (IslandHouseProjects.isChangeAction(choice.actionId))
            return IslandHouseProjects.choose(s,id,choice.actionId,choice.speech)
        if (w.events.any { it.startsWith(eventPrefix(id,turn)) }) return null
        val a = options(s,id).firstOrNull { it.id==choice.actionId } ?: return null
        var next = w
        var fruit = s.fruit
        val outcome = when (a.kind) {
            "none" -> "观察现场"
            "move" -> {
                next = w.copy(friends=w.friends.map {
                    if (it.id==id) it.copy(x=a.x,y=a.y) else it
                })
                "自己前进到("+a.x+","+a.y+")"
            }
            "approach" -> {
                val self = w.friends.first { it.id == id }
                val step = BlockIslandRules.nextStep(s, self.x, self.y, a.x, a.y)
                    ?: return null
                if (w.friends.any { it.id != id &&
                        it.x == step.first && it.y == step.second } ||
                    w.x == step.first && w.y == step.second) return null
                next = w.copy(friends = w.friends.map {
                    if (it.id == id) it.copy(x = step.first, y = step.second) else it
                })
                "主动朝(${a.x},${a.y})前进一格，到了(${step.first},${step.second})"
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
            "help_player" -> {
                next = w.copy(hp = (w.hp + 3).coerceAtMost(12))
                fruit -= 1
                "自己拿一颗野果帮助玩家恢复了生命"
            }
            "revive" -> {
                next = w.copy(friends = w.friends.map { teammate ->
                    if (teammate.id != id && teammate.hp <= 0 &&
                        teammate.x == a.x && teammate.y == a.y)
                        teammate.copy(hp = 4) else teammate
                })
                fruit -= 2
                "自己救起倒地伙伴，消耗了两颗野果"
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
        val plan = choice.intent.takeIf { it.isNotBlank() }
            ?.let { "；下一步想：${it.take(100)}" } ?: ""
        val event=eventPrefix(id,turn)+outcome+say+plan
        next=next.copy(events=(next.events+event).takeLast(150),notice=event)
        val applied = s.copy(world=next, fruit=fruit)
        return applied.copy(companionTravel=IslandTravel.afterChoice(s,applied,id,a))
    }

    /** Keep the player's newer turn; only the same still-legal chosen operation is merged. */
    fun applyLive(current: BlockIslandState, source: BlockIslandState, id: String,
                  choice: IslandActorChoice): BlockIslandState? {
        if (current.world.seed != source.world.seed || current.world.turns < source.world.turns ||
            current.world.finished) return null
        return apply(current,id,current.world.turns,choice)
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
            .put("teammates",JSONArray(w.friends.map { teammate ->
                JSONObject().put("id", teammate.id)
                    .put("x", teammate.x).put("y", teammate.y).put("hp", teammate.hp)
            }))
            .put("currentTravel",s.companionTravel[id]?.json() ?: JSONObject.NULL)
            .put("houseProject",s.houseProject?.json() ?: JSONObject.NULL)
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
像一个真正一起开黑的伙伴一样感知队伍，结合你自己的性格、关系、经历和此刻局势，自主决定下一步。队友有危险时可以选择照顾、支援、救起倒地伙伴，也可以视局势选择先做其他事，不是强制保姆。可以独自去采矿探路、主动防守、和队友商量目标、调侃或保持沉默。
options 给出真实可选操作。approach 是你自己选择的目的地，之后随已有游戏回合沿合法路径持续前往目标旁边，不代表已采集。currentTravel 是当前行程。
start_house 是你本人决定开启一座小屋工程，不代表已经建好。已选工程会随真实游戏回合逐格施工，每一步验证木材、路线、实体占用；木材不足就暂停。houseProject 是真实持久进度；你可以结合玩家当前讲话和你的想法，选择 pause_house、resume_house 或 demolish_house 逐格拆除自己建的部分。玩家改变主意应由你理解，不靠程序关键词强行拆房。none 只交流或观察，stop_travel 停止行程；没有原计划不自动编造下一个目标。
你也能注意伙伴位置、生命和最近事件。不要把上一轮行动当作新的成功，不编造资源、战斗或胜利。
只输出有效 JSON：{"actionId":"options里的id","speech":"你此刻自然想说的话，可以留空","intent":"可选，你自己的下一步打算，可留空"}。
角色决定行动与说话，程序只检查真实执行与回执。你不是任务助手，不需要每步都解释或每次都讲话。""",
            source="游戏 · 方块小岛自主同行",
            title="方块小岛伙伴自主决策",
            usage=ModelUsage.Game,
            contextMode=CompanionContextMode.CharacterAndScenario,
            maxTokens=320,
        ).mapCatching { parse(it.text) ?: error("游戏模型未返回有效的结构化选择") }
}
