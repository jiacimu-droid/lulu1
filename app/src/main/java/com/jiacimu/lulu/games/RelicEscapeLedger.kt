package com.jiacimu.lulu.games

import android.content.Context
import com.jiacimu.lulu.data.EventEvidenceKind
import com.jiacimu.lulu.data.SharedExperienceTimeline
import org.json.JSONArray
import org.json.JSONObject
import java.time.Instant
import kotlin.random.Random

internal data class RelicSnapshot(
    val run:RelicRun?=null,
    val vault:List<RelicLoot> = emptyList(),
    val coins:Int=0,
    val kits:Int=3,
    val weapon:Int=1,
    val successes:Int=0,
    val awarded:Set<String> = emptySet(),
) {
    fun json()=JSONObject().put("v",1).put("run",run?.json()?:JSONObject.NULL)
        .put("vault",JSONArray(vault.map{it.json()})).put("coins",coins).put("kits",kits)
        .put("weapon",weapon).put("successes",successes).put("awarded",JSONArray(awarded.sorted()))
    companion object {
        fun read(raw:String?):RelicSnapshot=runCatching {
            if(raw.isNullOrBlank())return@runCatching RelicSnapshot()
            val j=JSONObject(raw)
            if(j.getInt("v")!=1)return@runCatching RelicSnapshot()
            val ids=j.getJSONArray("awarded").let{a->(0 until a.length()).map{a.getString(it)}.toSet()}
            RelicSnapshot(
                run=j.optJSONObject("run")?.let{RelicRun.read(it)},
                vault=j.getJSONArray("vault").lootList(),
                coins=j.optInt("coins"),kits=j.optInt("kits",3),weapon=j.optInt("weapon",1),
                successes=j.optInt("successes"),awarded=ids)
        }.getOrDefault(RelicSnapshot())
    }
}

/**
 * One JSON document and one synchronous SharedPreferences commit for the run, permanent
 * vault and settlement tombstones. No independently awarded "score" can mint another item.
 * Unknown/interrupted old v1 Expedition saves remain untouched and cannot be auto-awarded.
 */
internal class RelicEscapeLedger(context:Context) {
    private val prefs=context.applicationContext.getSharedPreferences("relic_escape_v2",Context.MODE_PRIVATE)
    @Synchronized fun read():RelicSnapshot=RelicSnapshot.read(prefs.getString("ledger",null))
    @Synchronized private fun write(snapshot:RelicSnapshot):RelicSnapshot {
        check(prefs.edit().putString("ledger",snapshot.json().toString()).commit()) {
            "无法保存摸金进度，本次操作未确认"
        }
        return snapshot
    }
    @Synchronized fun begin(participants:List<String>,medkits:Int):RelicSnapshot {
        val old=read()
        if(old.run?.phase in listOf(RelicPhase.EXPLORE,RelicPhase.EXTRACT,RelicPhase.DOWNED))
            return old
        if(medkits !in 0..3 || old.kits<medkits)return old
        val level=(old.successes+1).coerceAtMost(5)
        val run=RelicEscapeWorld.create(Random.nextInt(),level,participants,medkits,old.weapon)
        return write(old.copy(run=run,kits=old.kits-medkits))
    }
    @Synchronized fun buyKit():RelicSnapshot {
        val old=read()
        return if(old.coins>=70)write(old.copy(coins=old.coins-70,kits=old.kits+1))else old
    }
    @Synchronized fun upgradeWeapon():RelicSnapshot {
        val old=read()
        return if(old.weapon==1 && old.coins>=350)
            write(old.copy(weapon=2,coins=old.coins-350))else old
    }
    @Synchronized fun sellLoot(id:String):RelicSnapshot {
        val old=read();val item=old.vault.firstOrNull{it.id==id}?:return old
        return write(old.copy(vault=old.vault.filterNot{it.id==id},
            coins=old.coins+item.value))
    }
    @Synchronized fun perform(action:String,x:Int=0,y:Int=0,itemId:String="",autonomousIds:Set<String> = emptySet()):RelicSnapshot {
        val old=read();val current=old.run?:return old
        val result=if(action=="abandon")RelicEscapeRules.abandon(current)
            else RelicEscapeRules.act(current,action,
                if(action in setOf("move","sneak","open","attack","invoke","disarm","lure"))x else current.hero.x,
                if(action in setOf("move","sneak","open","attack","invoke","disarm","lure"))y else current.hero.y,
                itemId,autonomousIds)
        if(result==current)return old
        val next=if(result.phase in setOf(RelicPhase.SUCCESS,RelicPhase.FAILURE) && result.completedAtMs==0L)
            result.copy(completedAtMs=System.currentTimeMillis()) else result
        val terminal=next.phase in setOf(RelicPhase.SUCCESS,RelicPhase.FAILURE) && next.id !in old.awarded
        val award=if(terminal && next.phase==RelicPhase.SUCCESS)next.secured else emptyList()
        // Even if caller replays a stale action, each unique run can be settled only once.
        return write(old.copy(run=next,
            vault=old.vault+award.filter{candidate->old.vault.none{it.id==candidate.id}},
            coins=old.coins+if(terminal)award.sumOf{it.value}/4 else 0,
            successes=old.successes+if(terminal && next.phase==RelicPhase.SUCCESS)1 else 0,
            awarded=if(terminal)old.awarded+next.id else old.awarded))
    }

    /** A slow response may merge only into the same live run, never a new game/settlement. */
    @Synchronized fun companion(runId:String,id:String,choice:IslandActorChoice):RelicSnapshot {
        val old=read()
        val current=old.run?:error("本局已经关闭")
        check(current.id==runId) { "对局已经更换，旧行动未执行" }
        val next=RelicActorPlay.apply(current,id,choice)
            ?:error("局势已改变，伙伴选择的行动未执行")
        return write(old.copy(run=next))
    }

    /** Called on app re-entry too. Stable timeline IDs make interrupted replay harmless. */
    fun syncExperiences(snapshot:RelicSnapshot,store:LuluGameStore) {
        val run=snapshot.run?:return
        if(run.id !in snapshot.awarded || run.phase !in setOf(RelicPhase.SUCCESS,RelicPhase.FAILURE))return
        val at=if(run.completedAtMs>0L)Instant.ofEpochMilli(run.completedAtMs) else Instant.now()
        for(actor in run.allies) {
            val id=actor.id
            if(id.isBlank())continue
            runCatching {
                SharedExperienceTimeline.record(eventId="relic:"+run.id+":"+id+":join",
                    characterId=id,channel="共同游戏《迷雾摸金》",speaker="游戏实况",
                    content="实际进入遗迹；本局"+run.turns+"回合",
                    occurredAt=at,source="game-relic",evidenceKind=EventEvidenceKind.ToolResult,
                    triggerExtraction=false)
                run.events.forEachIndexed{index,event->
                    if(event.who!=id)return@forEachIndexed
                    SharedExperienceTimeline.record(
                        eventId="relic:"+run.id+":"+id+":"+index,
                        characterId=id,channel="共同游戏《迷雾摸金》",speaker="游戏实况",
                        content=event.action+"："+event.text,occurredAt=at,
                        source="game-relic",evidenceKind=EventEvidenceKind.ToolResult)
                }
                SharedExperienceTimeline.record(eventId="relic:"+run.id+":"+id+":result",
                    characterId=id,channel="共同游戏《迷雾摸金》",speaker="游戏结算",
                    content=(if(run.phase==RelicPhase.SUCCESS)"本局结算；" else "本局失败；")+
                        "救援"+actor.rescues+"次，倒地"+actor.downs+"次，"+
                        (if(run.events.any{it.who==id && it.action=="escape"})"成功撤离" else "未成功撤离"),
                    occurredAt=at,source="game-relic",evidenceKind=EventEvidenceKind.ToolResult,
                    triggerExtraction=false)
            }
        }
        // Keep a conventional game replay record. Detect it on recovery rather than rely on
        // an in-memory 'recorded' flag; vault minting is already committed independently.
        if(store.state.value.records.none{it.type==LuluGameType.RelicEscape &&
            it.detailsJson.contains(run.id)}) {
            store.recordExternalGame(type=LuluGameType.RelicEscape,title="迷雾摸金",
                score=run.secured.sumOf{it.value},reward=0,
                summary=(if(run.phase==RelicPhase.SUCCESS)"撤离成功" else "撤离失败")+
                    "，带回"+run.secured.size+"件，遗失"+run.lost.size+"件，行动"+run.turns+"回合",
                detailsJson=run.json().toString(),
                characterIdOverride=run.allies.firstOrNull()?.id.orEmpty(),
                playedWithCharacterOverride=run.allies.isNotEmpty())
        }
    }
}
