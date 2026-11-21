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
    val modelReply: com.jiacimu.lulu.ai.ModelReply? = null,
)

internal object IslandActorPlay {
    fun eventPrefix(id: String, turn: Int) = "队友[" + id + "] 第" + turn + "回合："

    fun options(s: BlockIslandState, id: String, canAct: Boolean = true): List<IslandActorOption> {
        val w = s.world
        val a = w.friends.firstOrNull { it.id == id && it.hp > 0 } ?: return emptyList()
        val result = mutableListOf(IslandActorOption("none", "观察、交谈或保持安静", "none"))
        if (id in s.companionTravel && !w.finished)
            result += IslandActorOption("stop_travel", "取消自己正在执行的行程或采木计划", "stop_travel")
        val project=s.houseProject
        if(project?.ownerId==id) {
            if(project.phase in setOf("building","demolishing"))
                result += IslandActorOption("pause_house","暂时停下当前建造或拆除工程","pause_house")
            if(project.phase=="paused")
                result += IslandActorOption("resume_house",
                    if(project.resumePhase=="demolishing")"继续已暂停的拆除工程"
                    else "继续已暂停的建房工程","resume_house")
            if(project.phase!="demolishing")
                result += IslandActorOption("demolish_house","逐步拆除自己建造的小屋","demolish_house")
        }
        if(project==null && !w.finished) {
            for((x,y) in IslandHouseProjects.sites(s,id).take(3)) {
                for((style,title) in IslandHouseProjects.styles) {
                    result += IslandActorOption("start_house:"+x+":"+y+":"+style,
                        "自主决定在位置("+x+","+y+")给玩家建"+title+
                            "（3×3、约17木）；结构逐步真实消耗木材，完成后有独立屋顶外观",
                        "start_house",x,y)
                }
            }
        }
        if(!w.finished && s.companionTravel[id]?.targetMobId!=-3 &&
            (project?.ownerId!=id || project.phase !in setOf("building","demolishing")) &&
            IslandTravel.reachableWood(s,id)!=null) {
            for(count in listOf(3,6))
                result += IslandActorOption("plan_harvest_wood:"+count,
                    "自己选择连续采集"+count+"棵可达树木；每回合真实走路或砍一棵，木材进入共享库存；可随时取消，不额外逐格调用模型",
                    "plan_harvest_wood")
        }
        if(!w.finished && s.companionTravel[id]?.targetMobId!=-2 &&
            abs(a.x-w.x)+abs(a.y-w.y)>1)
            result += IslandActorOption("plan_regroup",
                "改为下一回合去和玩家集合，不立刻多走一步","plan_regroup")
        if(!w.finished)for(mob in w.mobs.filter { it.hp>0 &&
                s.companionTravel[id]?.targetMobId!=it.id &&
                abs(it.x-w.x)+abs(it.y-w.y)<=8 }.take(6)) {
            result += IslandActorOption("plan_support:"+mob.id,
                "改为下回合跟踪这只怪物，支援玩家共同战斗",
                "plan_support",mob.x,mob.y)
        }
        if (!canAct || w.finished) return result
        val playerPart = IslandHouseProjects.nextPlayerPart(s)
        if (playerPart != null && IslandHouseProjects.canPlacePart(s, playerPart)) {
            val (index, kind) = playerPart
            val x = index % w.width
            val y = index / w.width
            val distance = abs(a.x - x) + abs(a.y - y)
            val cost = IslandHouseProjects.partCost(kind)
            if (distance == 1) {
                result += IslandActorOption("help_house:$x:$y",
                    "帮玩家安放蓝图的下一块，消耗共享木材 $cost，工程与屋主仍属玩家",
                    "help_house", x, y)
            } else if (distance in 2..8 &&
                s.companionTravel[id]?.let { it.x == x && it.y == y } != true &&
                BlockIslandRules.nextStep(s, a.x, a.y, x, y) != null) {
                result += IslandActorOption("approach_house:$x:$y",
                    "主动走向玩家的施工位，到达后再决定是否帮忙",
                    "approach", x, y)
            }
        }
        if (s.fruit > 0 && w.hp < 12 &&
            abs(a.x - w.x) + abs(a.y - w.y) <= 1)
            result += IslandActorOption("help_player", "把一颗野果送给玩家，为他恢复生命", "help_player")
        if (abs(a.x-w.x)+abs(a.y-w.y)<=2 && s.guardedBy!=id)
            result += IslandActorOption("guard_player","自主护在玩家身边，下一次怪物打到玩家时代为抵挡1点伤害，自己受1伤","guard_player")
        if(abs(a.x-w.x)+abs(a.y-w.y) in 3..12) {
            val step=BlockIslandRules.nextStep(s,a.x,a.y,w.x,w.y)
            if(step!=null && w.friends.none { it.id!=id && it.hp>0 &&
                    it.x==step.first && it.y==step.second } &&
                !(w.x==step.first && w.y==step.second))
                result += IslandActorOption("regroup","自己决定和玩家集合，逐回合靠近玩家",
                    "regroup",w.x,w.y)
        }
        for(mob in w.mobs.filter { it.hp>0 &&
            abs(it.x-w.x)+abs(it.y-w.y)<=3 &&
            abs(it.x-a.x)+abs(it.y-a.y) in 2..9 }.take(3)) {
            val step=BlockIslandRules.nextStep(s,a.x,a.y,mob.x,mob.y)
            if(step!=null && w.friends.none { it.id!=id && it.hp>0 &&
                    it.x==step.first && it.y==step.second } &&
                !(w.x==step.first && w.y==step.second))
                result += IslandActorOption("protect:"+mob.x+":"+mob.y,
                    "玩家附近有怪物，主动靠近威胁支援他，可沿已选行程逐步走到敌人旁","approach",mob.x,mob.y)
        }
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
                if(abs(w.x-x)+abs(w.y-y)<=1)
                    result += IslandActorOption("combo:$x:$y",
                        "与玩家合击同一只怪物，造成3点伤害","combo",x,y)
                continue
            }
            if (w.cell(x, y) in setOf(1, 2) &&
                (w.cell(x,y)!=2 ||
                    !IslandProgression.crystalVein(w.seed,w.index(x,y)) ||
                    "pickaxe" in s.tools))
                result += IslandActorOption("gather:$x:$y",
                    "采集($x,$y)的" + if(w.cell(x,y)==1) "树" else if(IslandProgression.crystalVein(w.seed,w.index(x,y)))"蓝色晶矿（可得晶石）" else "石矿",
                    "gather",x,y)
            val occupied = w.friends.any { it.x == x && it.y == y } ||
                w.x == x && w.y == y ||
                s.animals.any { it.x == x && it.y == y }
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
        if (choice.actionId.startsWith("plan_"))
            return IslandTravel.choose(s,id,choice.actionId,choice.speech)
        if (IslandHouseProjects.isChangeAction(choice.actionId))
            return IslandHouseProjects.choose(s,id,choice.actionId,choice.speech)
        if (w.events.any { it.startsWith(eventPrefix(id,turn)) }) return null
        val a = options(s,id).firstOrNull { it.id==choice.actionId } ?: return null
        if (a.kind == "help_house") {
            val project = s.houseProject ?: return null
            val part = IslandHouseProjects.nextPlayerPart(s)
                ?.takeIf { it.first == w.index(a.x, a.y) && IslandHouseProjects.canPlacePart(s, it) }
                ?: return null
            val built = IslandHouseProjects.placePart(s, project, part, id)
            val completed = project.placed.size + 1
            val message = eventPrefix(id, turn) + "帮你安放了小屋的一块 · $completed/9" +
                choice.speech.takeIf(String::isNotBlank)?.let { "；他说：「$it」" }.orEmpty()
            return built.copy(
                world = built.world.copy(notice = message, events = (w.events + message).takeLast(150)),
                companionTravel = s.companionTravel - id,
            )
        }
        var next = w
        var fruit = s.fruit
        var crystals = s.crystals
        val outcome = when (a.kind) {
            "none" -> "观察现场"
            "move" -> {
                next = w.copy(friends=w.friends.map {
                    if (it.id==id) it.copy(x=a.x,y=a.y) else it
                })
                "自己前进到("+a.x+","+a.y+")"
            }
            "approach","regroup" -> {
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
                val rich=tile==2 && IslandProgression.crystalVein(w.seed,w.index(a.x,a.y))
                if(rich && "pickaxe" !in s.tools)return null
                val tiles=w.tiles.toMutableList().also { it[w.index(a.x,a.y)]=0 }
                next=w.copy(tiles=tiles,wood=w.wood+if(tile==1) 1 else 0,
                    stone=w.stone+if(tile==2) 1 else 0)
                if(rich)crystals+=1
                if(tile==1) "自己采木+1" else if(rich)"自己开采蓝色晶矿：石料+1、晶石+1" else "自己采石+1"
            }
            "fight","combo" -> {
                val damage=if(a.kind=="combo")3 else 2
                next=w.copy(mobs=w.mobs.map {
                    if(it.hp>0 && it.x==a.x && it.y==a.y)
                        it.copy(hp=(it.hp-damage).coerceAtLeast(0)) else it
                })
                if(a.kind=="combo")"与玩家一起合击怪物，造成3点伤害"
                else "自己攻击怪物，造成2点伤害"
            }
            "guard_player" -> {
                "主动保护玩家，下一次怪物攻击时挡下1点伤害"
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
        val applied = s.copy(world=next, fruit=fruit,crystals=crystals,
            guardedBy=if(a.kind=="guard_player")id else s.guardedBy)
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
            .put("sharedCrystals",s.crystals).put("craftedTools",JSONArray(s.tools.sorted()))
            .put("recoverableBackpacks",s.lostPacks.size)
            .put("teammates",JSONArray(w.friends.map { teammate ->
                JSONObject().put("id", teammate.id)
                    .put("x", teammate.x).put("y", teammate.y).put("hp", teammate.hp)
            }))
            .put("currentTravel",s.companionTravel[id]?.json() ?: JSONObject.NULL)
            .put("currentTravelGoal",s.companionTravel[id]?.let {
                if(it.targetMobId==-2)"与玩家集合"
                else if(it.targetMobId>=0)"追踪怪物"
                else "地图探索"
            } ?: JSONObject.NULL)
            .put("houseProject",s.houseProject?.json() ?: JSONObject.NULL)
            .put("playerGuardedBy",s.guardedBy)
            .put("visibleThreats",JSONArray(w.mobs.filter { it.hp>0 &&
                abs(it.x-w.x)+abs(it.y-w.y)<=5 }.map {
                JSONObject().put("x",it.x).put("y",it.y).put("hp",it.hp)
            }))
            .put("nearby",JSONArray(nearby))
            .put("recentEvents",JSONArray(w.events.takeLast(10)))
            .put("playerSpeech",heard)
            .put("recentTeamTalk",JSONArray(GameTeamConversationTimeline.recentLines(
                id, "block_island", w.seed.toString())))
            .put("options",JSONArray(options(s,id,canAct).map {
                JSONObject().put("id",it.id).put("meaning",it.name)
            })).toString()
    }

    suspend fun decide(s: BlockIslandState,id: String,heard: String): Result<IslandActorChoice> =
        LuluAiServices.gateway.generate(
            characterId=id,
            facts=facts(s,id,heard),
            instruction="""你和玩家在方块小岛共同生活。根据伙伴近况、可见资源和家园进度，自主选择采集、建造、协作或观察。currentTravel 和 houseProject 是真实进度，修改行程或工程由你决定，开工、采木、拆除都要等游戏回合逐步落实。
从 options 的 id 和含义中选择。玩家安静时仍可主动行动；刚听见的话、先前打算或新的发现都可以让你改主意。想说话就用自己的口吻，可以沉默。
只输出有效 JSON：{"actionId":"options里的id","speech":"你此刻自然想说的话，可以留空","intent":"可选，你自己的下一步打算，可留空"}。
角色决定行动与说话，程序只检查真实执行与回执。你不是任务助手，不需要每步都解释或每次都讲话。""",
            source="游戏 · 方块小岛自主同行",
            capturePrivateMoment = true,
            title="方块小岛伙伴自主决策",
            usage=ModelUsage.Game,
            contextMode=CompanionContextMode.Full,
            maxTokens=320,
        ).mapCatching { parse(it.text)?.copy(modelReply = it) ?: error("游戏模型未返回有效的结构化选择") }
}
