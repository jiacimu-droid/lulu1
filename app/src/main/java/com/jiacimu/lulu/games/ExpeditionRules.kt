package com.jiacimu.lulu.games

import org.json.JSONArray
import org.json.JSONObject
import kotlin.math.abs
import kotlin.random.Random

/** Two real, locally simulated games. Every action changes the persisted board, never a narrated victory. */
internal enum class ExpeditionMode(val gameId: String, val title: String) {
    HOMESTEAD("block_island", "方块小岛"),
    RELIC_RUN("relic_escape", "迷雾摸金"),
}

internal data class ExpeditionFriend(
    val id: String,
    val x: Int,
    val y: Int,
    val hp: Int = 8,
    val mode: String = "follow",
)
internal data class ExpeditionMob(val id: Int, val x: Int, val y: Int, val hp: Int = 3)
internal data class ExpeditionState(
    val mode: ExpeditionMode,
    val seed: Int,
    val width: Int,
    val height: Int,
    val tiles: List<Int>,
    val x: Int,
    val y: Int,
    val hp: Int = 12,
    val wood: Int = 0,
    val stone: Int = 0,
    val relics: Int = 0,
    val lootValue: Int = 0,
    val turns: Int = 0,
    val friends: List<ExpeditionFriend> = emptyList(),
    val mobs: List<ExpeditionMob> = emptyList(),
    val opened: Set<Int> = emptySet(),
    val finished: Boolean = false,
    val escaped: Boolean = false,
    val recorded: Boolean = false,
    val events: List<String> = emptyList(),
    val notice: String = "点击格子选中目标，方向键移动；靠近目标再行动",
) {
    fun index(x: Int, y: Int) = y * width + x
    fun inBounds(x: Int, y: Int) = x in 0 until width && y in 0 until height
    fun cell(x: Int, y: Int) = if (inBounds(x, y)) tiles[index(x, y)] else 9
    fun adjacent(x: Int, y: Int) = abs(this.x - x) + abs(this.y - y) <= 1
    fun toJson(): JSONObject = JSONObject()
        .put("version", 1).put("mode", mode.name).put("seed", seed)
        .put("width", width).put("height", height).put("tiles", JSONArray(tiles))
        .put("x", x).put("y", y).put("hp", hp).put("wood", wood).put("stone", stone)
        .put("relics", relics).put("lootValue", lootValue).put("turns", turns)
        .put("finished", finished).put("escaped", escaped).put("recorded", recorded)
        .put("opened", JSONArray(opened.sorted())).put("events", JSONArray(events))
        .put("friends", JSONArray(friends.map { JSONObject()
            .put("id", it.id).put("x", it.x).put("y", it.y).put("hp", it.hp).put("mode", it.mode) }))
        .put("mobs", JSONArray(mobs.map { JSONObject()
            .put("id", it.id).put("x", it.x).put("y", it.y).put("hp", it.hp) }))

    companion object {
        fun fromJson(raw: String, expected: ExpeditionMode): ExpeditionState? = runCatching {
            val j = JSONObject(raw)
            if (j.getInt("version") != 1 || j.getString("mode") != expected.name) return@runCatching null
            val w = j.getInt("width")
            val h = j.getInt("height")
            if (w !in 10..35 || h !in 8..35) return@runCatching null
            val tiles = j.getJSONArray("tiles").ints()
            if (tiles.size != w * h || tiles.any { it !in 0..8 }) return@runCatching null
            val friends = j.getJSONArray("friends").objects().map { f ->
                ExpeditionFriend(f.getString("id"), f.getInt("x"), f.getInt("y"),
                    f.getInt("hp"), f.optString("mode", "follow"))
            }
            val mobs = j.getJSONArray("mobs").objects().map { m ->
                ExpeditionMob(m.getInt("id"), m.getInt("x"), m.getInt("y"), m.getInt("hp"))
            }
            val x = j.getInt("x"); val y = j.getInt("y")
            if (x !in 0 until w || y !in 0 until h) return@runCatching null
            ExpeditionState(expected, j.getInt("seed"), w, h, tiles, x, y,
                j.getInt("hp"), j.getInt("wood"), j.getInt("stone"), j.getInt("relics"),
                j.getInt("lootValue"), j.getInt("turns"), friends, mobs,
                j.getJSONArray("opened").ints().toSet(), j.getBoolean("finished"),
                j.getBoolean("escaped"), j.optBoolean("recorded"),
                j.getJSONArray("events").strings(), notice = "存档已恢复")
        }.getOrNull()
    }
}

private fun JSONArray.ints() = (0 until length()).map { getInt(it) }
private fun JSONArray.strings() = (0 until length()).map { getString(it) }
private fun JSONArray.objects() = (0 until length()).map { getJSONObject(it) }

internal object ExpeditionRules {
    // 0 soil, 1 wood, 2 ore, 3 wall, 4 floor, 5 chest, 6 exit, 7 home, 8 water.
    fun start(mode: ExpeditionMode, seed: Int, partnerIds: List<String>): ExpeditionState {
        val rng = Random(seed)
        val w = if (mode == ExpeditionMode.HOMESTEAD) 20 else 18
        val h = 16
        val tiles = MutableList(w * h) { index ->
            val x = index % w
            val y = index / w
            val roll = rng.nextInt(100)
            when {
                x == 0 || y == 0 || x == w - 1 || y == h - 1 -> 8
                mode == ExpeditionMode.HOMESTEAD && roll < 13 -> 1
                mode == ExpeditionMode.HOMESTEAD && roll < 21 -> 2
                mode == ExpeditionMode.RELIC_RUN && roll < 12 -> 3
                else -> 0
            }
        }
        // Guaranteed open corridors, avoiding random disconnected starting cells.
        for (y in 2 until h - 1) tiles[y * w + 3] = 0
        for (x in 2 until w - 1) tiles[3 * w + x] = 0
        for (y in 2 until h - 1) tiles[y * w + (w - 3)] = 0
        for (x in 2 until w - 1) tiles[(h - 3) * w + x] = 0
        for (y in 2..5) for (x in 2..6) tiles[y * w + x] = 0
        tiles[3 * w + 3] = if (mode == ExpeditionMode.HOMESTEAD) 7 else 0
        val mobs = mutableListOf<ExpeditionMob>()
        if (mode == ExpeditionMode.RELIC_RUN) {
            val chests = listOf(9 to 3, 15 to 4, 4 to 11, 10 to 12, 14 to 11)
            chests.forEach { (x, y) -> tiles[y * w + x] = 5 }
            tiles[12 * w + (w - 3)] = 6
            mobs += listOf(ExpeditionMob(1, 8, 5), ExpeditionMob(2, 12, 10), ExpeditionMob(3, 6, 12))
        } else {
            mobs += listOf(ExpeditionMob(1, 9, 8), ExpeditionMob(2, 15, 11))
        }
        return ExpeditionState(mode, seed, w, h, tiles, 3, 4,
            friends = partnerIds.distinct().take(3).mapIndexed { index, id ->
                ExpeditionFriend(id, 4 + index % 2, 4 + index / 2)
            }, mobs = mobs, events = listOf("进入${mode.title}；同行${partnerIds.size}人"))
    }

    fun withPartners(s: ExpeditionState, partners: List<String>): ExpeditionState {
        val old = s.friends.associateBy { it.id }
        return s.copy(friends = partners.distinct().take(3).mapIndexed { index, id ->
            old[id] ?: ExpeditionFriend(id, (s.x + (index % 2) + 1).coerceAtMost(s.width - 2),
                (s.y + index / 2).coerceAtMost(s.height - 2))
        })
    }

    /** A single player action plus one concrete teammate/enemy simulation turn. */
    fun act(s: ExpeditionState, action: String, tx: Int = s.x, ty: Int = s.y): ExpeditionState {
        if (s.finished) return s.copy(notice = "本局已结算，可以重新开局")
        if (!s.inBounds(tx, ty)) return s.copy(notice = "目标超出地图")
        var next = s
        var message = ""
        val target = s.cell(tx, ty)
        val mob = s.mobs.firstOrNull { it.hp > 0 && it.x == tx && it.y == ty }
        when (action) {
            "move" -> {
                if (abs(tx - s.x) + abs(ty - s.y) != 1) return s.copy(notice = "每次只能移动一格")
                if (target in listOf(1, 2, 3, 5, 8) || mob != null) return s.copy(notice = "前方无法通行，采集、战斗或绕路")
                next = s.copy(x = tx, y = ty)
                message = "移动到 ($tx,$ty)"
            }
            "gather" -> {
                if (!s.adjacent(tx, ty)) return s.copy(notice = "需要靠近才能采集")
                if (s.mode != ExpeditionMode.HOMESTEAD || target !in listOf(1, 2))
                    return s.copy(notice = "这里没有可采集的木材或矿石")
                next = s.changeTile(tx, ty, 0).copy(
                    wood = s.wood + if (target == 1) 2 else 0,
                    stone = s.stone + if (target == 2) 2 else 0)
                message = if (target == 1) "伐木 +2" else "采矿 +2"
            }
            "wall", "floor" -> {
                if (s.mode != ExpeditionMode.HOMESTEAD || !s.adjacent(tx, ty) ||
                    target != 0 || (tx == s.x && ty == s.y) ||
                    s.friends.any { it.x == tx && it.y == ty } || mob != null)
                    return s.copy(notice = "需要选择身边的空地建造")
                val woodCost = if (action == "wall") 2 else 1
                if (s.wood < woodCost) return s.copy(notice = "木材不足，先砍树")
                next = s.changeTile(tx, ty, if (action == "wall") 3 else 4)
                    .copy(wood = s.wood - woodCost)
                message = if (action == "wall") "放下一块木墙" else "铺设木地板"
            }
            "home" -> {
                if (s.mode != ExpeditionMode.HOMESTEAD || s.wood < 6 || s.stone < 2)
                    return s.copy(notice = "建房需要6木材和2石料")
                // Build a 3x3 shell with a walkable doorway adjacent to the player.
                val cx = (s.x + 2).coerceIn(3, s.width - 4)
                val cy = s.y.coerceIn(3, s.height - 4)
                val cells = (cy - 1..cy + 1).flatMap { y -> (cx - 1..cx + 1).map { x -> x to y } }
                if (cells.any { (x,y) -> s.cell(x,y) !in listOf(0,4) ||
                        (x == s.x && y == s.y) || s.friends.any { it.x==x && it.y==y } ||
                        s.mobs.any { it.hp>0 && it.x==x && it.y==y } })
                    return s.copy(notice = "这块地放不下房子，换个宽敞的地方")
                var changed = s
                cells.forEach { (x,y) ->
                    changed = changed.changeTile(x,y, if ((x == cx - 1 && y == cy) || (x == cx && y == cy)) 4 else 3)
                }
                next = changed.copy(wood=s.wood-6,stone=s.stone-2)
                message = "盖好一间带门的小屋"
            }
            "fight" -> {
                if (!s.adjacent(tx,ty) || mob == null) return s.copy(notice = "先靠近怪物再攻击")
                next = s.copy(mobs=s.mobs.map { if(it.id==mob.id) it.copy(hp=(it.hp-2).coerceAtLeast(0)) else it })
                message = if (mob.hp<=2) "击退怪物 ${mob.id}！" else "攻击怪物 ${mob.id}，造成2点伤害"
            }
            "open" -> {
                if (s.mode != ExpeditionMode.RELIC_RUN || !s.adjacent(tx,ty) || target!=5 ||
                    s.index(tx,ty) in s.opened) return s.copy(notice = "靠近未打开的宝箱才可以搜索")
                val value = 60 + (s.index(tx,ty)*37+s.seed).let { (it and Int.MAX_VALUE)%180 }
                next = s.changeTile(tx,ty,0).copy(opened=s.opened+s.index(tx,ty),
                    relics=s.relics+1,lootValue=s.lootValue+value)
                message = "搜出一件藏品，价值 $value，带回撤离点才算真正获得"
            }
            "exit" -> {
                val exit = s.tiles.indexOf(6)
                if (s.mode != ExpeditionMode.RELIC_RUN || exit<0 ||
                    abs(s.x-exit%s.width)+abs(s.y-exit/s.width)>1)
                    return s.copy(notice = "请先到绿色撤离门附近")
                if (s.relics==0) return s.copy(notice = "至少找到一件藏品才能撤离")
                return s.copy(finished=true,escaped=true,notice="成功撤离！带回 ${s.relics} 件藏品，价值 ${s.lootValue}",
                    events=s.events+"成功撤离：${s.relics}件藏品、价值${s.lootValue}")
            }
            else -> return s
        }
        next = next.copy(turns=next.turns+1,notice=message,
            events=(next.events+message).takeLast(120))
        return advanceOthers(next)
    }

    private fun ExpeditionState.changeTile(x:Int,y:Int,t:Int) =
        copy(tiles=tiles.toMutableList().also { it[index(x,y)]=t })

    private fun advanceOthers(s:ExpeditionState):ExpeditionState {
        var state=s
        val logs=mutableListOf<String>()
        // Actual team turns: attack nearby threats; gather/build supplies or search nearby chests.
        val movedFriends=state.friends.map { friend ->
            if(friend.hp<=0) return@map friend
            val nearby=state.mobs.firstOrNull {it.hp>0 && dist(it.x,it.y,friend.x,friend.y)<=1}
            when {
                nearby!=null -> {
                    state=state.copy(mobs=state.mobs.map { if(it.id==nearby.id) it.copy(hp=(it.hp-1).coerceAtLeast(0)) else it })
                    logs+="队友 ${friend.id.take(12)} 击中了怪物"
                    friend
                }
                state.mode==ExpeditionMode.HOMESTEAD &&
                    listOf(friend.x+1 to friend.y,friend.x to friend.y+1,friend.x-1 to friend.y,friend.x to friend.y-1).any { (x,y) -> state.cell(x,y) in listOf(1,2) } -> {
                    val (x,y)=listOf(friend.x+1 to friend.y,friend.x to friend.y+1,friend.x-1 to friend.y,friend.x to friend.y-1)
                        .first { (x,y) -> state.cell(x,y) in listOf(1,2) }
                    val t=state.cell(x,y)
                    state=state.changeTile(x,y,0).copy(wood=state.wood+if(t==1)1 else 0,
                        stone=state.stone+if(t==2)1 else 0)
                    logs+="队友 ${friend.id.take(12)} 帮忙采集了资源"
                    friend
                }
                state.mode==ExpeditionMode.RELIC_RUN &&
                    listOf(friend.x+1 to friend.y,friend.x to friend.y+1,friend.x-1 to friend.y,friend.x to friend.y-1).any { (x,y) -> state.cell(x,y)==5 } -> {
                    val (x,y)=listOf(friend.x+1 to friend.y,friend.x to friend.y+1,friend.x-1 to friend.y,friend.x to friend.y-1)
                        .first { (x,y) -> state.cell(x,y)==5 }
                    val value=70+((state.index(x,y)*17+state.seed) and Int.MAX_VALUE)%130
                    state=state.changeTile(x,y,0).copy(opened=state.opened+state.index(x,y),
                        relics=state.relics+1,lootValue=state.lootValue+value)
                    logs+="队友 ${friend.id.take(12)} 搜到了价值 $value 的藏品"
                    friend
                }
                dist(friend.x,friend.y,state.x,state.y)>2 -> {
                    val choices=listOf(1 to 0,0 to 1,-1 to 0,0 to -1)
                        .map {(dx,dy)-> friend.copy(x=friend.x+dx,y=friend.y+dy)}
                        .filter { it.x in 1 until state.width-1 && it.y in 1 until state.height-1 &&
                            state.cell(it.x,it.y) in listOf(0,4,6,7) &&
                            state.mobs.none { m -> m.hp>0 && m.x==it.x && m.y==it.y } }
                    choices.minByOrNull {dist(it.x,it.y,state.x,state.y)}?.takeIf{
                        dist(it.x,it.y,state.x,state.y)<dist(friend.x,friend.y,state.x,state.y)
                    } ?: friend
                }
                else -> friend
            }
        }
        state=state.copy(friends=movedFriends)
        var damage=0
        val mobs=state.mobs.map { mob ->
            if(mob.hp<=0)return@map mob
            val targets=listOf(state.x to state.y)+movedFriends.filter{it.hp>0}.map{it.x to it.y}
            val nearest=targets.minByOrNull{(x,y)->dist(x,y,mob.x,mob.y)} ?: return@map mob
            if(dist(mob.x,mob.y,nearest.first,nearest.second)<=1){
                if(nearest.first==state.x && nearest.second==state.y)damage++
                return@map mob
            }
            if(dist(mob.x,mob.y,nearest.first,nearest.second)>5)return@map mob
            val moves=listOf(1 to 0,0 to 1,-1 to 0,0 to -1).map{(dx,dy)->
                mob.copy(x=mob.x+dx,y=mob.y+dy)}
                .filter{it.x in 1 until state.width-1 && it.y in 1 until state.height-1 &&
                    state.cell(it.x,it.y) in listOf(0,4,7) &&
                    (state.x!=it.x || state.y!=it.y)}
            moves.minByOrNull{dist(it.x,it.y,nearest.first,nearest.second)} ?: mob
        }
        val hp=(state.hp-damage).coerceAtLeast(0)
        if(damage>0) logs+="受到怪物攻击，生命 -$damage"
        state=state.copy(mobs=mobs,hp=hp,events=(state.events+logs).takeLast(120),
            notice=if(logs.isEmpty())state.notice else state.notice+" · "+logs.take(2).joinToString(" · "))
        if(hp==0){
            state= if(state.mode==ExpeditionMode.RELIC_RUN)
                state.copy(finished=true,escaped=false,relics=0,lootValue=0,
                    notice="队伍倒下了，本局藏品全部遗失",events=state.events+"撤离失败：藏品遗失")
            else state.copy(x=3,y=4,hp=12,notice="你在小岛营地复活了，家园和资源都保留",
                events=state.events+"在营地复活")
        }
        return state
    }

    private fun dist(ax:Int,ay:Int,bx:Int,by:Int)=abs(ax-bx)+abs(ay-by)
}
