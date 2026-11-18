package com.jiacimu.lulu.games

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.time.Instant
import kotlin.math.abs
import kotlin.random.Random

/**
 * Execute a role's existing model-selected focus on the same legal world engine
 * as the owner uses. Each character has their OWN physical save, not a second
 * personality or an invented story. Interruption preserves unfinished progress.
 */
internal data class SoloWorldResult(
    val type: LuluGameType, val title: String, val score: Int,
    val summary: String, val details: String,
)

internal class CharacterSoloWorldPlay(context: Context) {
    companion object { private val sharedSaveLock = Any() }
    private val prefs = context.applicationContext.getSharedPreferences(
        "character_solo_world_v1", Context.MODE_PRIVATE)

    fun play(id: String, game: String, focus: String, turns: Int,
             now: Instant): SoloWorldResult = synchronized(sharedSaveLock) {
        require(id.isNotBlank())
        require(LuluGameEdition.available(game)) { "该游戏仅在 game 分支版本提供" }
        require(turns in 4..128) { "单次游戏须为4至128回合" }
        return when (game) {
            "relic_escape" -> relic(id, focus, turns, now)
            "block_island" -> island(id, focus, turns)
            else -> error("独玩游戏尚未接通真实规则")
        }
    }

    fun readIsland(id: String): BlockIslandState? = synchronized(sharedSaveLock) {
        prefs.getString("island:"+id,null)?.let(BlockIslandState::fromJson)
    }

    fun readRelic(id: String): RelicSnapshot = synchronized(sharedSaveLock) {
        RelicSnapshot.read(prefs.getString("relic:"+id,null))
    }

    private fun relic(id:String, focus:String, turns:Int, now:Instant):SoloWorldResult {
        require(focus in setOf("explore","treasure","rare","safe","extract"))
        val key="relic:"+id
        val raw=prefs.getString(key,null)
        if (raw!=null) {
            val stored=requireNotNull(runCatching { JSONObject(raw) }.getOrNull()) {
                "摸金个人存档损坏，拒绝覆盖"
            }
            require(stored.optInt("v",-1)==1 && stored.optJSONArray("vault")!=null &&
                stored.optJSONArray("awarded")!=null) { "角色摸金记录版本异常，拒绝覆盖" }
        }
        val old=RelicSnapshot.read(raw)
        val resume=old.run?.phase in setOf(RelicPhase.EXPLORE,RelicPhase.EXTRACT,RelicPhase.DOWNED)
        var run=if(resume) checkNotNull(old.run) else RelicEscapeWorld.create(
            Random.nextInt(),(old.successes+1).coerceAtMost(5),emptyList(),
            old.kits.coerceAtMost(2),old.weapon,old.satchelLevel,
            purchasedWeapons=old.purchasedWeapons)
        val initial=run.turns
        val replayStart = RelicSoloReplay.start(run)
        val replayFrames = JSONArray()
        for (turn in 0 until turns) {
            if(run.phase !in setOf(RelicPhase.EXPLORE,RelicPhase.EXTRACT,RelicPhase.DOWNED))
                break
            val cmd=chooseRelic(run,focus) ?: break
            val next=RelicEscapeRules.act(run,cmd.first,cmd.second.first,cmd.second.second)
            if(next.turns>run.turns || next.phase!=run.phase) {
                replayFrames.put(RelicSoloReplay.delta(run, next))
                run=next
            }
        }
        require(run.turns>initial || run.phase in setOf(RelicPhase.SUCCESS,RelicPhase.FAILURE)) {
            "没有合法的摸金行动发生，本段游戏不计为完成"
        }
        if(run.phase in setOf(RelicPhase.SUCCESS,RelicPhase.FAILURE) && run.completedAtMs==0L)
            run=run.copy(completedAtMs=now.toEpochMilli())
        val settled=run.phase in setOf(RelicPhase.SUCCESS,RelicPhase.FAILURE) &&
            run.id !in old.awarded
        val earned=if(settled && run.phase==RelicPhase.SUCCESS)run.secured else emptyList()
        val snapshot=old.copy(
            run=run,
            kits=if(resume)old.kits else old.kits-(old.kits.coerceAtMost(2)),
            awarded=if(settled)old.awarded+run.id else old.awarded,
            vault=old.vault+earned.filter {loot->old.vault.none{it.id==loot.id}},
            successes=old.successes+if(settled && run.phase==RelicPhase.SUCCESS)1 else 0,
            discovered=old.discovered+earned.map{it.name},
            newDiscoveries=earned.map{it.name}.filterNot{it in old.discovered}.toSet(),
            coins=old.coins+if(settled)earned.sumOf{it.value}/4+
                RelicContractBoard.bonus(run)else 0,
        )
        check(prefs.edit().putString(key,snapshot.json().toString()).commit()) {
            "独玩摸金未能写盘，结算不成立"
        }
        val text=when(run.phase){
            RelicPhase.SUCCESS->"成功撤离，带回${run.secured.size}件实物"
            RelicPhase.FAILURE->"探险失败，本局没有入馆战利品"
            else->"暂时结束本段游玩，背包战利品尚未撤离归仓"
        }
        val summary="独自玩《迷雾摸金》：${text}；本段真实行动${run.turns-initial}回合。" +
            " 个人藏宝馆共有${snapshot.vault.size}件实物；当前背包${run.hero.bag.size}件。" +
            if(earned.isNotEmpty())" 新入馆：${earned.joinToString("、"){it.name}}。"else""
        val details=JSONObject().put("game","relic_escape").put("runId",run.id)
            .put("phase",run.phase.name).put("focus",focus)
            .put("replayStart",replayStart).put("replayFrames",replayFrames)
            .put("sessionTurns",run.turns-initial).put("turns",run.turns)
            .put("secured",JSONArray(run.secured.map{it.json()}))
            .put("unsecured",JSONArray(if(run.phase==RelicPhase.SUCCESS)emptyList()else run.hero.bag.map{it.json()}))
            .put("vaultCount",snapshot.vault.size)
            .put("events",JSONArray(run.events.takeLast(12).map{it.json()})).toString()
        return SoloWorldResult(LuluGameType.RelicEscape,"迷雾摸金",
            if(run.phase==RelicPhase.SUCCESS)run.secured.sumOf{it.value}else 0,summary,details)
    }

    /**
     * The role's model chooses an expedition focus; this executor must carry
     * out its physical turns on real, observed, safe tiles. No fake replay.
     */
    internal fun chooseRelic(s: RelicRun, focus: String): Pair<String, Pair<Int, Int>>? {
        val hero = s.hero
        val here = hero.x to hero.y
        fun distance(x: Int, y: Int) =
            RelicEscapeRules.distance(hero.x, hero.y, x, y)
        if (s.phase == RelicPhase.DOWNED || s.phase == RelicPhase.EXTRACT)
            return "wait" to here
        if (hero.hp <= 5 && hero.kits > 0) return "heal" to here

        // Use the real reach of the currently equipped blade, spear or bow.
        // Do not keep wasting single-target attacks when a sweep is legal.
        if (RelicArsenal.canSweep(s)) return "sweep" to here
        s.mobs.asSequence().filter { it.hp > 0 && RelicArsenal.canHit(s, it.x, it.y) }
            .sortedWith(compareByDescending<RelicMob> { it.windup }
                .thenByDescending { it.hp <= RelicArsenal.damage(s, it) }
                .thenBy { distance(it.x, it.y) })
            .firstOrNull()?.let { return "attack" to (it.x to it.y) }

        val capacity = RelicEscapeRules.heroCapacity(s)
        // Only a successful extraction puts these treasures into the museum.
        val goingHome = hero.bag.isNotEmpty() &&
            (focus in setOf("safe", "extract") || hero.load >= capacity - 2 ||
                hero.hp <= 5 || s.turns >= 60)
        if (goingHome) {
            val exit = RelicObjectiveGuide.visibleExit(s)
            if (exit != null) {
                val path = RelicObjectiveGuide.knownRouteTiles(s, exit)
                if (path != null) {
                    if (path.size <= 1) return "extract" to here
                    return "sneak" to path[1]
                }
            }
        } else {
            // Rank accessible discovered chests. The path ends beside a chest,
            // and never crosses an unrevealed cell, live guard or visible trap.
            val candidates = s.chests.asSequence()
                .filter { !it.opened && s.visible(it.x, it.y) &&
                    hero.load + it.tier.weight <= capacity }
                .mapNotNull { chest ->
                    val route = RelicObjectiveGuide.knownRouteTiles(
                        s, RelicObjectiveTarget(chest.x, chest.y, "宝箱"))
                    if (route == null) null else chest to route
                }.toList()
            val best = candidates.minWithOrNull(
                compareBy<Pair<RelicChest, List<Pair<Int, Int>>>> {
                    if (focus == "rare") -it.first.tier.ordinal else 0
                }.thenBy { it.second.size }.thenBy { it.first.id }
            )
            if (best != null) {
                val (chest, route) = best
                if (route.size <= 1) {
                    return (if (focus == "rare" && chest.tier == RelicTier.RARE &&
                        s.tools > 0 && hero.hp >= 7) "channel" else "open") to
                        (chest.x to chest.y)
                }
                return "sneak" to route[1]
            }
        }

        s.sites.firstOrNull { !it.spent && it.kind == "supply" &&
            s.visible(it.x, it.y) && distance(it.x, it.y) <= 1 &&
            (hero.kits < 5 || s.bolts < 12) }?.let {
            return "resupply" to (it.x to it.y)
        }
        s.sites.firstOrNull { !it.spent && it.kind == "fountain" &&
            s.visible(it.x, it.y) && hero.hp <= 6 &&
            distance(it.x, it.y) <= 1 }?.let {
            return "fountain" to (it.x to it.y)
        }
        if (!goingHome && focus == "rare") {
            s.sites.firstOrNull { !it.spent && it.kind == "altar" &&
                s.visible(it.x, it.y) && hero.hp >= 7 &&
                distance(it.x, it.y) <= 1 }?.let {
                return "invoke" to (it.x to it.y)
            }
        }

        // Survey reveals radius 5 while ordinary walking only reveals 3.
        // When the nearby radius is already known and no live guard can hear
        // the noise, survey HERE instead of pacing several safe tiles to the
        // fog boundary only to survey there. Under nearby threat preserve
        // quiet physical movement and the normal safety-first BFS.
        val localFloorKnown = (-3..3).all { dy -> (-3..3).all { dx ->
            abs(dx) + abs(dy) > 3 || !s.bounds(hero.x + dx, hero.y + dy) ||
                s.visible(hero.x + dx, hero.y + dy)
        } }
        val guardsNearby = s.mobs.any { it.hp > 0 &&
            distance(it.x, it.y) <= 5 }
        if (focus == "explore" && localFloorKnown && !guardsNearby &&
            RelicEscapeRules.canSurvey(s)) return "survey" to here

        // The old shortest-path helper could choose unrevealed/acidic tiles
        // and stall forever. Scout on known safe floor with a real BFS.
        val start = s.index(hero.x, hero.y)
        val parents = IntArray(s.w * s.h) { -1 }
        val queue = java.util.ArrayDeque<Int>()
        queue.add(start)
        parents[start] = start
        val blocked = s.mobs.filter { it.hp > 0 }.map { s.index(it.x, it.y) }.toSet() +
            s.sites.filter { it.kind == "trap" && !it.spent }
                .map { s.index(it.x, it.y) }
        while (queue.isNotEmpty()) {
            val current = queue.removeFirst()
            val x = current % s.w
            val y = current / s.w
            val frontier = listOf(x + 1 to y, x - 1 to y, x to y + 1, x to y - 1)
                .any { (nx, ny) -> s.bounds(nx, ny) && !s.visible(nx, ny) }
            if (current == start && frontier && RelicEscapeRules.canSurvey(s))
                return "survey" to here
            if (current != start && frontier) {
                var cursor = current
                while (parents[cursor] != start) cursor = parents[cursor]
                return "sneak" to (cursor % s.w to cursor / s.w)
            }
            for ((nx, ny) in listOf(x + 1 to y, x - 1 to y, x to y + 1, x to y - 1)) {
                if (!s.bounds(nx, ny)) continue
                val cell = s.index(nx, ny)
                if (parents[cell] != -1 || !s.visible(nx, ny) ||
                    !s.walk(nx, ny) || s.tile(nx, ny) == 2 || cell in blocked) continue
                parents[cell] = current
                queue.add(cell)
            }
        }
        if (RelicEscapeRules.canSurvey(s)) return "survey" to here
        return null
    }

    private fun island(id:String,focus:String,turns:Int):SoloWorldResult {
        require(focus in setOf("explore","gather","build","survive","wildlife"))
        val key="island:"+id
        val old=prefs.getString(key,null)
        var s=if(old==null)BlockIslandRules.newWorld(Random.nextInt(),emptyList())
            else requireNotNull(BlockIslandState.fromJson(old)){"小岛个人存档损坏，拒绝覆盖"}
        require(!s.world.finished) { "小岛存档已结算，不能把零行动当作新的一段游玩" }
        val initial=s.world.turns
        repeat(turns){
            if(s.world.finished)return@repeat
            val cmd=chooseIsland(s,focus)?:return@repeat
            val next=BlockIslandRules.act(s,cmd.first,cmd.second.first,cmd.second.second)
            if(next.world.turns>s.world.turns)s=next
        }
        require(s.world.turns>initial) {
            "没有合法的小岛行动发生，本段游玩不计为完成"
        }
        check(prefs.edit().putString(key,s.toJson()).commit()){"小岛进度未成功保存"}
        val steps=s.world.turns-initial
        val summary="独自在《方块小岛》玩了${steps}回合，已存入自己的长期世界。" +
            " 总回合${s.world.turns}，木材${s.world.wood}、石料${s.world.stone}、" +
            "晶石${s.crystals}、设施${s.structures.size}、探索里程碑${s.awardedChallenges.size}。" +
            if(s.world.finished)" 该存档已结算。"else" 下次还可以继续。"
        val details=JSONObject().put("game","block_island").put("seed",s.world.seed)
            .put("focus",focus).put("sessionTurns",steps).put("totalTurns",s.world.turns)
            .put("position",JSONArray(listOf(s.world.x,s.world.y)))
            .put("wood",s.world.wood).put("stone",s.world.stone)
            .put("crystals",s.crystals).put("structures",s.structures.size)
            .put("milestones",JSONArray(s.awardedChallenges.sorted()))
            .put("events",JSONArray(s.world.events.takeLast(12))).toString()
        return SoloWorldResult(LuluGameType.BlockIsland,"方块小岛",steps,summary,details)
    }

    private fun chooseIsland(s:BlockIslandState,focus:String):Pair<String,Pair<Int,Int>>? {
        val w=s.world
        val here=w.x to w.y
        fun dist(x:Int,y:Int)=abs(w.x-x)+abs(w.y-y)
        if(s.fruit>0 && (w.hp<=5 || s.hunger<=2))return "eat" to here
        w.mobs.firstOrNull{it.hp>0 && dist(it.x,it.y)<=1}?.let{
            return "fight" to (it.x to it.y)
        }
        s.supplyCaches.firstOrNull{it !in s.openedSupplies &&
            dist(it%w.width,it/w.width)<=1}?.let{
            return "salvage" to (it%w.width to it/w.width)
        }
        if(focus in setOf("build","survive")){
            for(kind in listOf("workbench","campfire","lantern")){
                if(kind in s.structures.values)continue
                for((x,y) in listOf(w.x+1 to w.y,w.x-1 to w.y,w.x to w.y+1,w.x to w.y-1))
                    if(BlockIslandRules.canBuild(s,kind,x,y))
                        return kind to (x to y)
            }
            val workbench=s.structures.any{(i,kind)->
                kind=="workbench" && dist(i%w.width,i/w.width)<=2
            }
            if(workbench)for(tool in listOf("axe","pickaxe","sword")){
                val recipe=IslandWorkbenchRecipes.find(tool)?:continue
                if(tool !in s.tools && w.wood>=recipe.wood &&
                    w.stone>=recipe.stone && s.crystals>=recipe.crystals)
                    return ("craft_"+tool) to here
            }
        }
        if(focus=="wildlife")
            s.animals.firstOrNull{!it.befriended && dist(it.x,it.y)<=1 &&
                IslandWildlife.canFeed(s,it) && w.turns-it.lastFedTurn>=4}?.let{
                return "tame" to (it.x to it.y)
            }
        val targetResource=if(focus=="build" && w.stone<3)2 else if(w.wood<5)1 else 2
        if(focus in setOf("gather","build","survive"))
            listOf(w.x+1 to w.y,w.x-1 to w.y,w.x to w.y+1,w.x to w.y-1)
                .firstOrNull{(x,y)->w.inBounds(x,y) && w.cell(x,y)==targetResource &&
                    (targetResource==1 || "pickaxe" in s.tools ||
                        !IslandProgression.crystalVein(w.seed,w.index(x,y)))}?.let{
                    return "gather" to it
                }
        // A chosen focus may already be exhausted in a long-lived world.
        // Do not strand the actor just because the first landmark/chest/animal
        // is done: continue with reachable, physical exploration and supplies.
        val resources=(1 until w.height-1).flatMap{y->
            (1 until w.width-1).mapNotNull{x->
                val tile=w.cell(x,y)
                if(tile in setOf(1,2) && (tile==1 || "pickaxe" in s.tools ||
                    !IslandProgression.crystalVein(w.seed,w.index(x,y))))
                    x to y else null
            }
        }
        val caches=s.supplyCaches.filter{it !in s.openedSupplies}
            .map{it%w.width to it/w.width}
        val places=when(focus){
            "explore"->listOfNotNull(IslandExpeditionTrail.next(s)?.let{
                IslandExpeditionTrail.position(s,it)
            })+caches+resources
            "wildlife"->s.animals.filter{!it.befriended &&
                IslandWildlife.canFeed(s,it)}.map{it.x to it.y}+caches+resources
            else->caches+resources
        }
        for((x,y)in places.sortedBy{dist(it.first,it.second)}){
            if(dist(x,y)<=1 && w.cell(x,y) in setOf(1,2) &&
                (w.cell(x,y)==1 || "pickaxe" in s.tools ||
                    !IslandProgression.crystalVein(w.seed,w.index(x,y))))
                return "gather" to (x to y)
            val step=BlockIslandRules.nextStep(s,w.x,w.y,x,y)?:continue
            if(step!=here && BlockIslandRules.isWalkable(s,step.first,step.second) &&
                w.mobs.none{it.hp>0 && it.x==step.first && it.y==step.second} &&
                s.animals.none{it.x==step.first && it.y==step.second})
                return "move" to step
        }
        // After visible objectives and resources are depleted, scouting still
        // moves on real traversable tiles. A successful move advances the
        // authoritative survival clock, which can replenish resources; blocked
        // worlds still return null and MUST NOT be recorded as successful play.
        val patrol=listOf(
            (w.width-3) to 3, (w.width-3) to (w.height-3),
            3 to (w.height-3), 3 to 3,
        )
        for(offset in patrol.indices){
            val (x,y)=patrol[((w.turns/12)+offset)%patrol.size]
            val step=BlockIslandRules.nextStep(s,w.x,w.y,x,y)?:continue
            if(step!=here && BlockIslandRules.isWalkable(s,step.first,step.second) &&
                w.mobs.none{it.hp>0 && it.x==step.first && it.y==step.second} &&
                s.animals.none{it.x==step.first && it.y==step.second})
                return "move" to step
        }
        return null
    }
}
