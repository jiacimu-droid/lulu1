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
        repeat(turns) {
            if(run.phase !in setOf(RelicPhase.EXPLORE,RelicPhase.EXTRACT,RelicPhase.DOWNED))
                return@repeat
            val cmd=chooseRelic(run,focus) ?: return@repeat
            val next=RelicEscapeRules.act(run,cmd.first,cmd.second.first,cmd.second.second)
            if(next.turns>run.turns || next.phase!=run.phase)run=next
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
            .put("sessionTurns",run.turns-initial).put("turns",run.turns)
            .put("secured",JSONArray(run.secured.map{it.json()}))
            .put("unsecured",JSONArray(if(run.phase==RelicPhase.SUCCESS)emptyList()else run.hero.bag.map{it.json()}))
            .put("vaultCount",snapshot.vault.size)
            .put("events",JSONArray(run.events.takeLast(12).map{it.json()})).toString()
        return SoloWorldResult(LuluGameType.RelicEscape,"迷雾摸金",
            if(run.phase==RelicPhase.SUCCESS)run.secured.sumOf{it.value}else 0,summary,details)
    }

    /** Only revealed landmarks enter navigation. The engine validates every move. */
    private fun chooseRelic(s:RelicRun,focus:String):Pair<String,Pair<Int,Int>>? {
        val h=s.hero
        val here=h.x to h.y
        fun dist(x:Int,y:Int)=RelicEscapeRules.distance(h.x,h.y,x,y)
        if(s.phase in setOf(RelicPhase.DOWNED,RelicPhase.EXTRACT))return "wait" to here
        if(h.hp<=5 && h.kits>0)return "heal" to here
        s.mobs.filter {it.hp>0 && s.visible(it.x,it.y) &&
            dist(it.x,it.y)<=2 && RelicArsenal.canHit(s,it.x,it.y)}
            .minByOrNull{dist(it.x,it.y)}?.let{return "attack" to (it.x to it.y)}
        val exitGoal=h.bag.isNotEmpty() && (focus in setOf("safe","extract") ||
            h.load>=RelicEscapeRules.heroCapacity(s)-2 || h.hp<=5 || s.turns>=60)
        if(exitGoal && s.visible(s.exitX,s.exitY) && dist(s.exitX,s.exitY)<=1)
            return "extract" to here
        if(!exitGoal){
            s.chests.filter{!it.opened && s.visible(it.x,it.y) && dist(it.x,it.y)<=1}
                .sortedByDescending{if(focus=="rare")it.tier.ordinal else 0}.firstOrNull()?.let {
                    return (if(focus=="rare" && it.tier==RelicTier.RARE &&
                        s.tools>0 && h.hp>=7)"channel"else"open") to (it.x to it.y)
                }
        }
        s.sites.firstOrNull{!it.spent && it.kind=="supply" &&
            s.visible(it.x,it.y) && dist(it.x,it.y)<=1}?.let {
            return "resupply" to (it.x to it.y)
        }
        s.sites.firstOrNull{!it.spent && it.kind=="fountain" && h.hp<=6 &&
            s.visible(it.x,it.y) && dist(it.x,it.y)<=1}?.let {
            return "fountain" to (it.x to it.y)
        }
        if(focus=="rare"){
            s.sites.firstOrNull{!it.spent && it.kind=="altar" && h.hp>=7 &&
                s.visible(it.x,it.y) && dist(it.x,it.y)<=1}?.let {
                return "invoke" to (it.x to it.y)
            }
        }
        val targets=if(exitGoal && s.visible(s.exitX,s.exitY))
            listOf(s.exitX to s.exitY)
        else s.chests.filter{!it.opened && s.visible(it.x,it.y)}
            .sortedWith(compareBy<RelicChest> {
                if(focus=="rare")-it.tier.ordinal else 0
            }.thenBy{dist(it.x,it.y)}).map{it.x to it.y}
        for((x,y)in targets) {
            val step=RelicEscapeRules.path(s,h.x,h.y,x,y)?:continue
            if(s.visible(step.first,step.second) &&
                s.mobs.none{it.hp>0 && it.x==step.first && it.y==step.second})
                return "sneak" to step
        }
        if(RelicEscapeRules.canSurvey(s))return "survey" to here
        val frontiers=(0 until s.h).flatMap{y->(0 until s.w).map{x->x to y}}
            .filter{(x,y)->s.visible(x,y) && s.walk(x,y) && dist(x,y)>0 &&
                listOf(x+1 to y,x-1 to y,x to y+1,x to y-1).any{(nx,ny)->
                    s.bounds(nx,ny) && !s.visible(nx,ny)
                }}
            .sortedBy{dist(it.first,it.second)}
        for((x,y)in frontiers){
            val step=RelicEscapeRules.path(s,h.x,h.y,x,y)?:continue
            if(s.visible(step.first,step.second) &&
                s.mobs.none{it.hp>0 && it.x==step.first && it.y==step.second})
                return "sneak" to step
        }
        return null
    }

    private fun island(id:String,focus:String,turns:Int):SoloWorldResult {
        require(focus in setOf("explore","gather","build","survive","wildlife"))
        val key="island:"+id
        val old=prefs.getString(key,null)
        var s=if(old==null)BlockIslandRules.newWorld(Random.nextInt(),emptyList())
            else requireNotNull(BlockIslandState.fromJson(old)){"小岛个人存档损坏，拒绝覆盖"}
        val initial=s.world.turns
        repeat(turns){
            if(s.world.finished)return@repeat
            val cmd=chooseIsland(s,focus)?:return@repeat
            val next=BlockIslandRules.act(s,cmd.first,cmd.second.first,cmd.second.second)
            if(next.world.turns>s.world.turns)s=next
        }
        require(s.world.turns>initial || s.world.finished) {
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
        val places=when(focus){
            "explore"->listOfNotNull(IslandExpeditionTrail.next(s)?.let{
                IslandExpeditionTrail.position(s,it)
            })+s.supplyCaches.filter{it !in s.openedSupplies}
                .map{it%w.width to it/w.width}
            "wildlife"->s.animals.filter{!it.befriended}.map{it.x to it.y}
            else->(1 until w.height-1).flatMap{y->
                (1 until w.width-1).mapNotNull{x->
                    val tile=w.cell(x,y)
                    if(tile in setOf(1,2) && (tile==1 || "pickaxe" in s.tools ||
                        !IslandProgression.crystalVein(w.seed,w.index(x,y))))
                        x to y else null
                }}
        }
        for((x,y)in places.sortedBy{dist(it.first,it.second)}){
            if(dist(x,y)<=1 && w.cell(x,y) in setOf(1,2) &&
                focus !in setOf("wildlife","explore"))return "gather" to (x to y)
            val step=BlockIslandRules.nextStep(s,w.x,w.y,x,y)?:continue
            if(step!=here && BlockIslandRules.isWalkable(s,step.first,step.second) &&
                w.mobs.none{it.hp>0 && it.x==step.first && it.y==step.second} &&
                s.animals.none{it.x==step.first && it.y==step.second})
                return "move" to step
        }
        return null
    }
}
