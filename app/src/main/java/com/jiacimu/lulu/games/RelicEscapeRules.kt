package com.jiacimu.lulu.games

import org.json.JSONArray
import org.json.JSONObject
import kotlin.math.abs

internal enum class RelicPhase { PREPARE, EXPLORE, DOWNED, EXTRACT, SUCCESS, FAILURE, SETTLEMENT }
internal enum class RelicTier(val label:String,val base:Int,val weight:Int) {
    COMMON("普通",70,1), RARE("稀有",185,2), PRECIOUS("珍贵",430,3)
}
internal data class RelicLoot(val id:String,val name:String,val tier:RelicTier,val value:Int) {
    val weight get()=tier.weight
    fun json()=JSONObject().put("id",id).put("name",name).put("tier",tier.name).put("value",value)
    companion object { fun read(j:JSONObject)=RelicLoot(j.getString("id"),j.getString("name"),
        RelicTier.valueOf(j.getString("tier")),j.getInt("value")) }
}
internal data class RelicActor(val id:String,val x:Int,val y:Int,val hp:Int=10,val kits:Int=0,
    val bag:List<RelicLoot> = emptyList(), val rescues:Int=0,val downs:Int=0) {
    val load get()=bag.sumOf{it.weight}
    fun json()=JSONObject().put("id",id).put("x",x).put("y",y).put("hp",hp).put("kits",kits)
        .put("bag",JSONArray(bag.map{it.json()})).put("rescues",rescues).put("downs",downs)
    companion object { fun read(j:JSONObject)=RelicActor(j.getString("id"),j.getInt("x"),j.getInt("y"),
        j.getInt("hp"),j.getInt("kits"),j.getJSONArray("bag").lootList(),
        j.optInt("rescues"),j.optInt("downs")) }
}
internal data class RelicChest(val id:Int,val x:Int,val y:Int,val tier:RelicTier,val opened:Boolean=false) {
    fun json()=JSONObject().put("id",id).put("x",x).put("y",y).put("tier",tier.name).put("opened",opened)
    companion object { fun read(j:JSONObject)=RelicChest(j.getInt("id"),j.getInt("x"),j.getInt("y"),
        RelicTier.valueOf(j.getString("tier")),j.getBoolean("opened")) }
}
internal data class RelicMob(val id:Int,val x:Int,val y:Int,val hp:Int,val kind:Int,val alert:Int=0,val windup:Boolean=false) {
    fun json()=JSONObject().put("id",id).put("x",x).put("y",y).put("hp",hp).put("kind",kind)
        .put("alert",alert).put("windup",windup)
    companion object { fun read(j:JSONObject)=RelicMob(j.getInt("id"),j.getInt("x"),j.getInt("y"),
        j.getInt("hp"),j.getInt("kind"),j.getInt("alert"),j.getBoolean("windup")) }
}
internal data class RelicDrop(val x:Int,val y:Int,val loot:RelicLoot) {
    fun json()=JSONObject().put("x",x).put("y",y).put("loot",loot.json())
    companion object { fun read(j:JSONObject)=RelicDrop(j.getInt("x"),j.getInt("y"),RelicLoot.read(j.getJSONObject("loot"))) }
}
internal data class RelicSite(val id:Int,val x:Int,val y:Int,val kind:String,val spent:Boolean=false) {
    fun json()=JSONObject().put("id",id).put("x",x).put("y",y).put("kind",kind).put("spent",spent)
    companion object { fun read(j:JSONObject)=RelicSite(j.getInt("id"),j.getInt("x"),
        j.getInt("y"),j.getString("kind"),j.optBoolean("spent")) }
}
internal data class RelicEvent(val who:String,val action:String,val text:String,val turn:Int) {
    fun json()=JSONObject().put("who",who).put("action",action).put("text",text).put("turn",turn)
    companion object { fun read(j:JSONObject)=RelicEvent(j.getString("who"),j.getString("action"),
        j.getString("text"),j.getInt("turn")) }
}
internal fun JSONArray.lootList()=(0 until length()).map{RelicLoot.read(getJSONObject(it))}
private fun JSONArray.actors()=(0 until length()).map{RelicActor.read(getJSONObject(it))}
private fun JSONArray.chests()=(0 until length()).map{RelicChest.read(getJSONObject(it))}
private fun JSONArray.mobs()=(0 until length()).map{RelicMob.read(getJSONObject(it))}
private fun JSONArray.drops()=(0 until length()).map{RelicDrop.read(getJSONObject(it))}
private fun JSONArray.events()=(0 until length()).map{RelicEvent.read(getJSONObject(it))}
private fun JSONArray.sites()=(0 until length()).map{RelicSite.read(getJSONObject(it))}

internal data class RelicRun(
    val id:String,val seed:Int,val level:Int,val w:Int,val h:Int,val tiles:List<Int>,
    val phase:RelicPhase,val hero:RelicActor,val allies:List<RelicActor>,val chests:List<RelicChest>,
    val mobs:List<RelicMob>,val drops:List<RelicDrop>,val exitX:Int,val exitY:Int,
    val seen:Set<Int> = emptySet(),val weapon:Int=1,val turns:Int=0,val extraction:Int=0,
    val downTurns:Int=0,val notice:String="探索遗迹，寻找宝物",val events:List<RelicEvent> = emptyList(),
    val secured:List<RelicLoot> = emptyList(),val lost:List<RelicLoot> = emptyList(),
    val completedAtMs:Long=0L,
    val sites:List<RelicSite> = emptyList(),
    val flares:Int=2,val lures:Int=1,val tools:Int=2,
    val flareX:Int=-1,val flareY:Int=-1,val flareTurns:Int=0,
    val lureX:Int=-1,val lureY:Int=-1,val lureTurns:Int=0,
    val weapons:Set<String> = setOf("blade"),val equipped:String="blade",
    val bolts:Int=5,val guarding:Boolean=false,
    val playerChestMarks:Set<Int> = emptySet(),
    val guardedBy:String = ""
) {
    fun index(x:Int,y:Int)=y*w+x
    fun bounds(x:Int,y:Int)=x in 0 until w && y in 0 until h
    fun tile(x:Int,y:Int)=if(bounds(x,y))tiles[index(x,y)] else 1
    fun walk(x:Int,y:Int)=tile(x,y)!=1
    fun visible(x:Int,y:Int)=bounds(x,y) && index(x,y) in seen
    val carried get()=hero.bag+allies.flatMap{it.bag}
    val value get()=carried.sumOf{it.value}
    fun log(who:String,action:String,text:String)=copy(notice=text,
        events=(events+RelicEvent(who,action,text,turns)).takeLast(300))
    fun json()=JSONObject().put("v",2).put("id",id).put("seed",seed).put("level",level)
        .put("w",w).put("h",h).put("tiles",JSONArray(tiles)).put("phase",phase.name)
        .put("hero",hero.json()).put("allies",JSONArray(allies.map{it.json()}))
        .put("chests",JSONArray(chests.map{it.json()})).put("mobs",JSONArray(mobs.map{it.json()}))
        .put("drops",JSONArray(drops.map{it.json()})).put("exitX",exitX).put("exitY",exitY)
        .put("seen",JSONArray(seen.sorted())).put("weapon",weapon).put("turns",turns)
        .put("extraction",extraction).put("downTurns",downTurns).put("notice",notice)
        .put("events",JSONArray(events.map{it.json()}))
        .put("secured",JSONArray(secured.map{it.json()})).put("lost",JSONArray(lost.map{it.json()}))
        .put("completedAtMs",completedAtMs)
        .put("sites",JSONArray(sites.map{it.json()}))
        .put("flares",flares).put("lures",lures).put("tools",tools)
        .put("flareX",flareX).put("flareY",flareY).put("flareTurns",flareTurns)
        .put("lureX",lureX).put("lureY",lureY).put("lureTurns",lureTurns)
        .put("weapons",JSONArray(weapons.sorted())).put("equipped",equipped)
        .put("bolts",bolts).put("guarding",guarding)
        .put("playerChestMarks",JSONArray(playerChestMarks.sorted()))
        .put("guardedBy",guardedBy)
    companion object {
        fun read(j:JSONObject):RelicRun?=runCatching {
            if(j.getInt("v")!=2)return@runCatching null
            val w=j.getInt("w");val h=j.getInt("h")
            if(w !in 12..32 || h !in 10..32)return@runCatching null
            val tiles=j.getJSONArray("tiles").let{a->(0 until a.length()).map{a.getInt(it)}}
            if(tiles.size!=w*h || tiles.any{it !in 0..3})return@runCatching null
            RelicRun(j.getString("id"),j.getInt("seed"),j.getInt("level"),w,h,tiles,
                RelicPhase.valueOf(j.getString("phase")),RelicActor.read(j.getJSONObject("hero")),
                j.getJSONArray("allies").actors(),j.getJSONArray("chests").chests(),
                j.getJSONArray("mobs").mobs(),j.getJSONArray("drops").drops(),
                j.getInt("exitX"),j.getInt("exitY"),j.getJSONArray("seen").let{a->
                    (0 until a.length()).map{a.getInt(it)}.filter{it in tiles.indices}.toSet()},
                j.getInt("weapon"),j.getInt("turns"),j.getInt("extraction"),
                j.getInt("downTurns"),j.getString("notice"),j.getJSONArray("events").events(),
                j.getJSONArray("secured").lootList(),j.getJSONArray("lost").lootList(),j.optLong("completedAtMs"),
                j.optJSONArray("sites")?.sites()?:emptyList(),
                j.optInt("flares",2),j.optInt("lures",1),j.optInt("tools",2),
                j.optInt("flareX",-1),j.optInt("flareY",-1),j.optInt("flareTurns"),
                j.optInt("lureX",-1),j.optInt("lureY",-1),j.optInt("lureTurns"),
                j.optJSONArray("weapons")?.let { a -> 
                    (0 until a.length()).mapNotNull { i ->
                        a.optString(i).takeIf { it in RelicArsenal.weapons }
                    }.toSet() + "blade"
                } ?: setOf("blade"),
                j.optString("equipped","blade").takeIf { it in RelicArsenal.weapons &&
                    (j.optJSONArray("weapons")?.let { a ->
                        (0 until a.length()).any { i -> a.optString(i)==it }
                    } ?: (it=="blade")) } ?: "blade",
                j.optInt("bolts",5).coerceIn(0,12),j.optBoolean("guarding",false),
                j.optJSONArray("playerChestMarks")?.let { a ->
                    (0 until a.length()).mapNotNull { i -> a.optInt(i,-1).takeIf { it>=0 } }.toSet()
                } ?: emptySet(),
                j.optString("guardedBy","").takeIf { id ->
                    j.getJSONArray("allies").let { a ->
                        (0 until a.length()).any { a.getJSONObject(it).optString("id")==id }
                    }
                } ?: "")
        }.getOrNull()
    }
}

/** Pure state transitions; invalid actions do not pass time or yield rewards. */
internal object RelicEscapeRules {
    const val CAPACITY=8
    fun distance(ax:Int,ay:Int,bx:Int,by:Int)=abs(ax-bx)+abs(ay-by)
    private fun near(a:RelicActor,x:Int,y:Int)=distance(a.x,a.y,x,y)<=1
    fun reveal(s:RelicRun):RelicRun {
        val seen=s.seen.toMutableSet()
        for(dy in -3..3)for(dx in -3..3)if(abs(dx)+abs(dy)<=3 && s.bounds(s.hero.x+dx,s.hero.y+dy))
            seen+=s.index(s.hero.x+dx,s.hero.y+dy)
        if(s.flareTurns>0)for(dy in -6..6)for(dx in -6..6)
            if(abs(dx)+abs(dy)<=6 && s.bounds(s.flareX+dx,s.flareY+dy))
                seen+=s.index(s.flareX+dx,s.flareY+dy)
        return s.copy(seen=seen)
    }
    /** Reusable but costly reconnaissance: expands fog knowledge, spends a turn and makes noise. */
    fun canSurvey(s: RelicRun): Boolean =
        s.phase in setOf(RelicPhase.EXPLORE, RelicPhase.EXTRACT) &&
            (-5..5).any { dy -> (-5..5).any { dx ->
                val x = s.hero.x + dx
                val y = s.hero.y + dy
                abs(dx)+abs(dy) in 4..5 && s.bounds(x,y) && !s.visible(x,y)
            } }

    fun treasure(s:RelicRun,c:RelicChest):RelicLoot {
        val names=when(c.tier) {
            RelicTier.COMMON->listOf("旧王银币","石刻铭牌","锈蚀铜杯")
            RelicTier.RARE->listOf("月银胸针","幽蓝宝石","古老法印")
            RelicTier.PRECIOUS->listOf("星穹冠冕","永夜结晶","遗迹王印")
        }
        val roll=abs((s.seed.toLong()*31+c.id*97)%1000).toInt()
        return RelicLoot(s.id+":chest:"+c.id,names[roll%names.size],c.tier,c.tier.base+roll%63)
    }
    fun act(s:RelicRun,action:String,x:Int=s.hero.x,y:Int=s.hero.y,lootId:String="", autonomousIds:Set<String> = emptySet()):RelicRun {
        if(s.phase !in listOf(RelicPhase.EXPLORE,RelicPhase.EXTRACT,RelicPhase.DOWNED))
            return s.copy(notice="当前不能继续行动")
        if(s.phase==RelicPhase.DOWNED && action!="wait")return s.copy(notice="倒地了，等待同伴救援")
        if(action=="equip") {
            if(lootId !in s.weapons || lootId !in RelicArsenal.weapons)
                return s.copy(notice="尚未获得这把武器")
            return if(s.equipped==lootId)s else s.copy(equipped=lootId)
                .log("player","equip","切换为"+RelicArsenal.label(lootId)+
                    " · "+RelicArsenal.description(lootId))
        }
        var next=s;var noise=0
        val hero=s.hero
        val mob=s.mobs.firstOrNull{it.hp>0 && it.x==x && it.y==y}
        when(action) {
            "survey" -> {
                if (!canSurvey(s)) return s.copy(notice="附近迷雾已经探明，继续前进再侦察")
                val explored = s.seen.toMutableSet()
                for (dy in -5..5) for (dx in -5..5) {
                    val px = hero.x+dx
                    val py = hero.y+dy
                    if (abs(dx)+abs(dy)<=5 && s.bounds(px,py)) explored+=s.index(px,py)
                }
                val count = explored.size-s.seen.size
                next=s.copy(seen=explored).log("player","survey",
                    "侦察发现$count 格新地形，动静可能惊动附近守卫")
                noise=2
            }
            "move","sneak"->{
                if(distance(hero.x,hero.y,x,y)!=1 || !s.walk(x,y) || mob!=null)
                    return s.copy(notice="只能向相邻的通路移动")
                next=s.copy(hero=hero.copy(x=x,y=y)).log("player",action,if(action=="sneak")"潜行一步" else "前进一步")
                if(s.tile(x,y)==2)next=next.copy(hero=next.hero.copy(hp=(next.hero.hp-1).coerceAtLeast(0)))
                    .log("player","hurt","踏入腐蚀地面，生命 -1")
                val trap=s.sites.firstOrNull{it.x==x && it.y==y && it.kind=="trap" && !it.spent}
                if(trap!=null)next=next.copy(
                    hero=next.hero.copy(hp=(next.hero.hp-2).coerceAtLeast(0)),
                    sites=next.sites.map{if(it.id==trap.id)it.copy(spent=true) else it})
                    .log("player","hurt","踩中机关！生命 -2（可用拆解工具提前解除）")
                if(s.phase==RelicPhase.EXTRACT && distance(x,y,s.exitX,s.exitY)>1)
                    next=next.copy(phase=RelicPhase.EXPLORE,extraction=0)
                        .log("player","cancel","离开出口，撤离进度重置")
                noise=if(action=="sneak")0 else 1
            }
            "open"->{
                val chest=s.chests.firstOrNull{it.x==x && it.y==y && !it.opened}
                    ?:return s.copy(notice="宝箱已经打开，或这里没有宝箱")
                if(!near(hero,x,y) || !s.visible(x,y))return s.copy(notice="先接近宝箱")
                val loot=treasure(s,chest);val fits=hero.load+loot.weight<=CAPACITY
                val unlocked=RelicArsenal.chestUnlock(chest)?.takeUnless { it in s.weapons }
                val ammo=if(chest.tier==RelicTier.COMMON)1 else 2
                next=s.copy(chests=s.chests.map{if(it.id==chest.id)it.copy(opened=true) else it},
                    hero=if(fits)hero.copy(bag=hero.bag+loot) else hero,
                    drops=if(fits)s.drops else s.drops+RelicDrop(x,y,loot),
                    weapons=if(unlocked==null)s.weapons else s.weapons+unlocked,
                    bolts=(s.bolts+ammo).coerceAtMost(12))
                    .log("player","open",chest.tier.label+"宝箱开启："+loot.name+
                        if(fits)"（入包）" else "（背包已满，留在原地）"+
                        if(unlocked!=null)" · 解锁"+RelicArsenal.label(unlocked) else ""+
                        " · 弩箭+$ammo")
                noise=2
            }
            "invoke"->{
                val altar=s.sites.firstOrNull{it.x==x && it.y==y && it.kind=="altar" && !it.spent}
                    ?:return s.copy(notice="这里没有可唤醒的祭坛")
                if(!near(hero,x,y) || !s.visible(x,y))return s.copy(notice="需要走近祭坛")
                val loot=RelicLoot(s.id+":altar:"+altar.id,"封印之心",RelicTier.PRECIOUS,650+s.level*65)
                val fits=hero.load+loot.weight<=CAPACITY
                val spawn=listOf(x+1 to y,x-1 to y,x to y+1,x to y-1)
                    .firstOrNull{(mx,my)->s.walk(mx,my) &&
                        s.mobs.none{it.hp>0 && it.x==mx && it.y==my} &&
                        s.allies.none{it.hp>0 && it.x==mx && it.y==my} &&
                        (hero.x!=mx || hero.y!=my)}
                next=s.copy(
                    sites=s.sites.map{if(it.id==altar.id)it.copy(spent=true)else it},
                    hero=if(fits)hero.copy(bag=hero.bag+loot) else hero,
                    drops=if(fits)s.drops else s.drops+RelicDrop(x,y,loot),
                    mobs=if(spawn==null)s.mobs else s.mobs+RelicMob(
                        (s.mobs.maxOfOrNull{it.id}?:0)+1,spawn.first,spawn.second,6+s.level,2,alert=4))
                    .log("player","invoke","祭坛苏醒！获得珍贵封印之心"+if(fits)"（已入包）" else "（掉落在地）")
                noise=3
            }
            "resupply"->{
                val station=s.sites.firstOrNull{it.x==x && it.y==y && it.kind=="supply" && !it.spent}
                    ?:return s.copy(notice="这处补给站已搜刮")
                if(!near(hero,x,y) || !s.visible(x,y))return s.copy(notice="先靠近补给站")
                next=s.copy(hero=hero.copy(kits=(hero.kits+1).coerceAtMost(5)),
                    bolts=(s.bolts+3).coerceAtMost(12),
                    sites=s.sites.map { if(it.id==station.id)it.copy(spent=true) else it })
                    .log("player","resupply","找到战地补给：药剂+1，弩箭+3（有上限）")
                noise=1
            }
            "fountain"->{
                val spring=s.sites.firstOrNull{it.x==x && it.y==y && it.kind=="fountain" && !it.spent}
                    ?:return s.copy(notice="灵泉力量已经耗尽")
                if(!near(hero,x,y) || !s.visible(x,y))return s.copy(notice="靠近恢复灵泉")
                if(hero.hp>=10)return s.copy(notice="生命已满，先保留灵泉")
                next=s.copy(hero=hero.copy(hp=(hero.hp+4).coerceAtMost(10)),
                    sites=s.sites.map { if(it.id==spring.id)it.copy(spent=true) else it })
                    .log("player","fountain","灵泉治愈，生命恢复")
            }
            "disarm"->{
                val trap=s.sites.firstOrNull{it.x==x && it.y==y && it.kind=="trap" && !it.spent}
                    ?:return s.copy(notice="附近没有未解除的机关")
                if(!near(hero,x,y) || !s.visible(x,y))return s.copy(notice="需要靠近机关")
                if(s.tools<=0)return s.copy(notice="拆解工具已经用完")
                next=s.copy(sites=s.sites.map{if(it.id==trap.id)it.copy(spent=true) else it},
                    tools=s.tools-1).log("player","disarm","成功拆除地面陷阱")
            }
            "flare"->{
                if(s.flares<=0)return s.copy(notice="照明弹已经用完")
                next=s.copy(flares=s.flares-1,flareX=hero.x,flareY=hero.y,flareTurns=4)
                    .log("player","flare","照明弹照亮周围，但也会引来敌人注意")
                noise=3
            }
            "lure"->{
                if(s.lures<=0)return s.copy(notice="诱饵已经用完")
                if(!s.walk(x,y) || !s.visible(x,y) || distance(hero.x,hero.y,x,y)>4)
                    return s.copy(notice="选择四格内已探索的通路投放诱饵")
                next=s.copy(lures=s.lures-1,lureX=x,lureY=y,lureTurns=4)
                    .log("player","lure","诱饵落地，吸引附近的巡逻者")
            }
            "pickup"->{
                val drop=s.drops.firstOrNull{it.loot.id==lootId && near(hero,it.x,it.y)}
                    ?:return s.copy(notice="找不到这件地面物品")
                if(hero.load+drop.loot.weight>CAPACITY)return s.copy(notice="背包容量不足，先舍弃其他物品")
                next=s.copy(hero=hero.copy(bag=hero.bag+drop.loot),drops=s.drops.filterNot{it.loot.id==lootId})
                    .log("player","pickup","拾起 "+drop.loot.name)
                noise=1
            }
            "drop"->{
                val item=hero.bag.firstOrNull{it.id==lootId}?:return s.copy(notice="背包里没有这件物品")
                next=s.copy(hero=hero.copy(bag=hero.bag.filterNot{it.id==lootId}),
                    drops=s.drops+RelicDrop(hero.x,hero.y,item)).log("player","drop","丢下 "+item.name)
            }
            "attack"->{
                if(mob==null || !RelicArsenal.canHit(s,x,y))
                    return s.copy(notice="目标不在"+RelicArsenal.label(s.equipped)+"的有效射程，或被阻挡")
                val damage=RelicArsenal.damage(s,mob)
                val defeated=mob.hp<=damage
                next=s.copy(mobs=s.mobs.map{if(it.id==mob.id)it.copy(
                    hp=(it.hp-damage).coerceAtLeast(0),
                    windup=if(s.equipped=="blade")false else it.windup) else it},
                    bolts=if(s.equipped=="bow")s.bolts-1 else s.bolts,
                    drops=if(defeated)s.drops+RelicDrop(x,y,RelicArsenal.monsterDrop(s,mob)) else s.drops)
                    .log("player","attack",
                        RelicArsenal.label(s.equipped)+"造成"+damage+"伤害"+
                        if(defeated)" · 击败"+RelicArsenal.monsterName(mob.kind)+"，掉落战利品！"
                        else if(mob.windup && s.equipped=="blade")" · 打断蓄力！" else "")
                noise=if(s.equipped=="bow")3 else 2
            }
            "defend"->{
                next=s.copy(guarding=true).log("player","defend","举盾防御，抵消本回合一次来自守卫的伤害")
                noise=0
            }
            "heal"->{
                if(hero.kits<=0 || hero.hp>=10)return s.copy(notice="没有可用的药剂，或生命已满")
                next=s.copy(hero=hero.copy(hp=(hero.hp+5).coerceAtMost(10),kits=hero.kits-1))
                    .log("player","heal","使用药剂，恢复生命")
            }
            "revive"->{
                val ally=s.allies.firstOrNull{it.id==lootId && it.hp<=0 && near(hero,it.x,it.y)}
                    ?:return s.copy(notice="需要靠近倒地队友")
                if(hero.kits<=0)return s.copy(notice="救援需要一瓶药剂")
                next=s.copy(hero=hero.copy(kits=hero.kits-1,rescues=hero.rescues+1),
                    allies=s.allies.map{if(it.id==ally.id)it.copy(hp=4) else it})
                    .log("player","rescue","成功救起队友 "+ally.id)
            }
            "extract"->{
                if(s.phase==RelicPhase.EXTRACT)return s.copy(notice="撤离已启动，继续行动或等待即可")
                if(!near(hero,s.exitX,s.exitY))return s.copy(notice="还没到撤离门")
                next=s.copy(phase=RelicPhase.EXTRACT,extraction=0)
                    .log("player","extract","撤离启动！守住出口3回合")
                noise=2
            }
            "wait"->next=s.log("player","wait",if(s.phase==RelicPhase.DOWNED)"倒地等待救援" else "原地观察")
            else->return s.copy(notice="无效行动")
        }
        next=reveal(next.copy(turns=s.turns+1))
        next=alliesTurn(next,autonomousIds)
        next=enemyTurn(next,noise)
        next=next.copy(flareTurns=(next.flareTurns-1).coerceAtLeast(0),
            lureTurns=(next.lureTurns-1).coerceAtLeast(0),guarding=false)
        if(next.hero.hp<=0 && next.phase!=RelicPhase.DOWNED) {
            next=next.copy(phase=RelicPhase.DOWNED,extraction=0,downTurns=0,hero=next.hero.copy(downs=next.hero.downs+1))
                .log("player","down","玩家倒地！等待队友救援")
        }
        if(next.phase==RelicPhase.DOWNED) {
            if(next.hero.hp>0)next=next.copy(phase=RelicPhase.EXPLORE,downTurns=0)
                .log("player","revived","队友救援成功")
            else {
                next=next.copy(downTurns=next.downTurns+1)
                if(next.downTurns>=4)return fail(next,"救援超时")
            }
        }
        if(next.phase==RelicPhase.EXTRACT && next.hero.hp>0 && action!="extract") {
            next=next.copy(extraction=next.extraction+1)
            if(next.extraction>=3)return success(next)
        }
        return next
    }
    private fun alliesTurn(original:RelicRun,autonomousIds:Set<String>):RelicRun {
        var s=original
        val done=mutableListOf<RelicActor>()
        for(friend in s.allies) {
            var a=friend
            if(a.hp<=0 || a.id in autonomousIds){done+=a;continue}
            val nearThreat=s.mobs.firstOrNull{it.hp>0 && near(a,it.x,it.y)}
            when {
                s.hero.hp<=0 && a.kits>0 && near(a,s.hero.x,s.hero.y)->{
                    a=a.copy(kits=a.kits-1,rescues=a.rescues+1)
                    s=s.copy(hero=s.hero.copy(hp=4)).log(a.id,"rescue","队友 "+a.id+" 救起玩家")
                }
                a.hp<=3 && a.kits>0->{
                    a=a.copy(hp=(a.hp+5).coerceAtMost(10),kits=a.kits-1)
                    s=s.log(a.id,"heal","队友 "+a.id+" 使用治疗药剂")
                }
                nearThreat!=null->{
                    s=s.copy(mobs=s.mobs.map{if(it.id==nearThreat.id)
                        it.copy(hp=(it.hp-2).coerceAtLeast(0)) else it})
                        .log(a.id,"attack","队友 "+a.id+" 掩护攻击")
                }
                else->{
                    val chest=s.chests.firstOrNull{!it.opened && near(a,it.x,it.y) &&
                        a.load+it.tier.weight<=CAPACITY}
                    if(chest!=null && s.phase==RelicPhase.EXPLORE) {
                        val loot=treasure(s,chest)
                        a=a.copy(bag=a.bag+loot)
                        s=s.copy(chests=s.chests.map{if(it.id==chest.id)it.copy(opened=true)else it})
                            .log(a.id,"open","队友 "+a.id+" 搜到 "+loot.name)
                    } else {
                        val target=if(s.hero.hp<=0) s.hero.x to s.hero.y else if(s.phase==RelicPhase.EXTRACT) s.exitX to s.exitY
                            else s.chests.filter{!it.opened && a.load<CAPACITY && distance(a.x,a.y,it.x,it.y)<=4}
                                .minByOrNull{distance(a.x,a.y,it.x,it.y)}?.let{it.x to it.y}
                                ?: (s.hero.x to s.hero.y)
                        val step=path(s,a.x,a.y,target.first,target.second)
                        if(step!=null && s.mobs.none{it.hp>0 && it.x==step.first && it.y==step.second}) {
                            a=a.copy(x=step.first,y=step.second)
                            s=s.log(a.id,"move","队友 "+a.id+" 前进到 "+step.first+","+step.second)
                        }
                    }
                }
            }
            done+=a
        }
        return s.copy(allies=done)
    }
    private fun enemyTurn(original:RelicRun,noise:Int):RelicRun {
        var s=original
        val alive=mutableListOf<RelicMob>()
        for(m in s.mobs) {
            if(m.hp<=0){alive+=m;continue}
            val targets=(listOf(s.hero)+s.allies).filter{it.hp>0}
            val nearest=targets.minByOrNull{distance(m.x,m.y,it.x,it.y)}
            if(nearest==null){alive+=m;continue}
            val playerDistance=distance(m.x,m.y,nearest.x,nearest.y)
            val decoy=s.lureTurns>0 && s.lureX>=0 && s.lureY>=0 &&
                distance(m.x,m.y,s.lureX,s.lureY)<=7 && playerDistance>2
            val target=if(decoy)RelicActor("decoy",s.lureX,s.lureY)else nearest
            val d=distance(m.x,m.y,target.x,target.y)
            if(m.windup) {
                if(decoy) {
                    alive+=m.copy(windup=false,alert=4)
                    s=s.log("world","clue","怪物转而扑向诱饵，攻击落空")
                    continue
                }
                if(d<=1) {
                    val rawDamage=m.kind+1
                    val protector=if(target.id=="player")s.allies.firstOrNull { ally ->
                        ally.id==s.guardedBy && ally.hp>0 &&
                            distance(ally.x,ally.y,s.hero.x,s.hero.y)<=2
                    } else null
                    val blocked=s.guarding || protector!=null
                    val damage=if(target.id=="player" && blocked)
                        (rawDamage-3).coerceAtLeast(0) else rawDamage
                    if(target.id=="player") {
                        s=s.copy(hero=s.hero.copy(hp=(s.hero.hp-damage).coerceAtLeast(0)),
                            guarding=false,guardedBy="")
                        if(protector!=null)s=s.copy(allies=s.allies.map { ally ->
                            if(ally.id==protector.id)ally.copy(hp=(ally.hp-1).coerceAtLeast(0))
                            else ally
                        })
                    } else s=s.copy(allies=s.allies.map{if(it.id==target.id)
                        it.copy(hp=(it.hp-damage).coerceAtLeast(0),
                            downs=it.downs+if(it.hp>0 && it.hp<=damage)1 else 0) else it})
                    s=s.log(target.id,if(damage==0)"block" else "hurt",
                        if(protector!=null)protector.id+"挡在玩家前面，自己受伤1点，玩家受到"+damage+"点伤害"
                        else if(damage==0)"成功格挡"+RelicArsenal.monsterName(m.kind)+"的攻击"
                        else "敌人击中 "+target.id+"，伤害"+damage)
                }
                alive+=m.copy(windup=false,alert=3)
                continue
            }
            val alert=if(decoy || d<=3 || noise>0 && d<=4+noise)4 else (m.alert-1).coerceAtLeast(0)
            if(d in 3..5 && s.turns%4==0 && noise==0)
                s=s.log("world","clue","迷雾里传来拖曳脚步声")
            if(decoy && d<=1) {
                alive+=m.copy(alert=4,windup=false)
                continue
            }
            if(d<=1) {
                alive+=m.copy(alert=4,windup=true)
                s=s.log("world","warning","怪物蓄力！下一回合攻击，快闪避")
            } else if(alert>0) {
                val step=path(s,m.x,m.y,target.x,target.y)
                alive+=if(step!=null && (step.first!=target.x || step.second!=target.y) &&
                    alive.none{it.hp>0 && it.x==step.first && it.y==step.second})
                    m.copy(x=step.first,y=step.second,alert=alert) else m.copy(alert=alert)
            } else alive+=m
        }
        return s.copy(mobs=alive)
    }
    /** Shortest passable step. No teleporting through terrain. */
    fun path(s:RelicRun,sx:Int,sy:Int,tx:Int,ty:Int):Pair<Int,Int>? {
        if(!s.bounds(sx,sy) || !s.bounds(tx,ty))return null
        val start=s.index(sx,sy);val parent=IntArray(s.w*s.h){-1}
        val queue=java.util.ArrayDeque<Int>();queue.add(start);parent[start]=start
        var end=-1
        while(queue.isNotEmpty()) {
            val p=queue.removeFirst();val x=p%s.w;val y=p/s.w
            if(distance(x,y,tx,ty)<=if(s.walk(tx,ty))0 else 1){end=p;break}
            for((nx,ny) in listOf(x+1 to y,x-1 to y,x to y+1,x to y-1)) {
                if(!s.bounds(nx,ny) || !s.walk(nx,ny))continue
                val ix=s.index(nx,ny)
                if(parent[ix]!=-1)continue
                parent[ix]=p;queue.add(ix)
            }
        }
        if(end<0 || end==start)return null
        var current=end
        while(parent[current]!=start && parent[current]!=current)current=parent[current]
        return current%s.w to current/s.w
    }
    private fun success(s:RelicRun):RelicRun {
        val escaped=s.allies.filter{it.hp>0 && distance(it.x,it.y,s.exitX,s.exitY)<=2}
        val abandoned=s.allies.filterNot{it in escaped}
        val saved=s.hero.bag+escaped.flatMap{it.bag}
        val lost=abandoned.flatMap{it.bag}+s.drops.map{it.loot}
        var next=s.copy(phase=RelicPhase.SUCCESS,secured=saved,lost=lost)
            .log("player","escape","成功撤离，带回"+saved.size+"件宝物")
        for(a in escaped)next=next.log(a.id,"escape",a.id+" 成功撤离")
        for(a in abandoned)next=next.log(a.id,"left",a.id+" 未赶上撤离")
        return next
    }
    private fun fail(s:RelicRun,why:String):RelicRun {
        val lost=s.carried+s.drops.map{it.loot}
        return s.copy(phase=RelicPhase.FAILURE,secured=emptyList(),lost=lost)
            .log("player","failure",why+"，遗失"+lost.size+"件本局宝物")
    }
    fun abandon(s:RelicRun):RelicRun=if(s.phase in listOf(RelicPhase.EXPLORE,RelicPhase.EXTRACT,RelicPhase.DOWNED))
        fail(s,"中途放弃") else s
}
