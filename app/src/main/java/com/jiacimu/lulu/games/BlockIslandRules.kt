package com.jiacimu.lulu.games

import org.json.JSONArray
import org.json.JSONObject
import kotlin.math.abs
import kotlin.random.Random

/**
 * 独立于摸金的方块小岛规则。所有资源与建筑的修改发生在同一个存档快照中。
 * 地块沿用旧版 ExpeditionState 的 0..8 协议，以便无损迁移旧家园。
 */
internal data class BlockIslandState(
    val world: ExpeditionState,
    val fruit: Int = 0,
    val tools: Set<String> = emptySet(),
    val structures: Map<Int, String> = emptyMap(),
    val lastRestTurn: Int = -20,
) {
    fun toJson(): String = JSONObject()
        .put("version", 2)
        .put("world", world.toJson())
        .put("fruit", fruit)
        .put("tools", JSONArray(tools.sorted()))
        .put("structures", JSONObject().apply {
            structures.forEach { (index, item) -> put(index.toString(), item) }
        })
        .put("lastRestTurn", lastRestTurn)
        .toString()

    companion object {
        fun fromJson(raw: String): BlockIslandState? = runCatching {
            val json = JSONObject(raw)
            if (json.optInt("version") != 2) return@runCatching null
            val world = ExpeditionState.fromJson(
                json.getJSONObject("world").toString(), ExpeditionMode.HOMESTEAD,
            ) ?: return@runCatching null
            val list = json.optJSONArray("tools") ?: JSONArray()
            val tools = (0 until list.length()).map { list.getString(it) }.toSet()
            if (tools.any { it !in setOf("axe", "pickaxe", "sword") }) return@runCatching null
            val obj = json.optJSONObject("structures") ?: JSONObject()
            val positions = mutableMapOf<Int, String>()
            val keys = obj.keys()
            while (keys.hasNext()) {
                val key = keys.next()
                val index = key.toIntOrNull() ?: return@runCatching null
                val kind = obj.getString(key)
                if (index !in world.tiles.indices ||
                    kind !in setOf("door", "workbench", "campfire", "bed") ||
                    world.tiles[index] !in listOf(0, 4)) return@runCatching null
                positions[index] = kind
            }
            val fruit = json.optInt("fruit")
            if (fruit < 0) return@runCatching null
            BlockIslandState(world, fruit, tools, positions, json.optInt("lastRestTurn", -20))
        }.getOrNull()

        fun fromLegacy(legacy: ExpeditionState): BlockIslandState? =
            legacy.takeIf { it.mode == ExpeditionMode.HOMESTEAD }?.let(::BlockIslandState)
    }
}

internal object BlockIslandRules {
    const val WORLD_WIDTH = 28
    const val WORLD_HEIGHT = 22

    // Legacy terrain: 0 grass, 1 tree, 2 ore, 3 wall, 4 floor, 7 spawn, 8 water.
    fun newWorld(seed: Int, partners: List<String>): BlockIslandState {
        val rng = Random(seed)
        val tiles = List(WORLD_WIDTH * WORLD_HEIGHT) { index ->
            val x = index % WORLD_WIDTH
            val y = index / WORLD_WIDTH
            val roll = rng.nextInt(100)
            when {
                x == 0 || y == 0 || x == WORLD_WIDTH - 1 || y == WORLD_HEIGHT - 1 -> 8
                roll < 16 -> 1
                roll < 27 -> 2
                roll < 31 -> 8
                else -> 0
            }
        }.toMutableList()
        // Start clearing + connected hiking trails. No resource may block the spawn.
        for (y in 2..7) for (x in 2..8) tiles[y * WORLD_WIDTH + x] = 0
        for (x in 2 until WORLD_WIDTH - 1) tiles[5 * WORLD_WIDTH + x] = 0
        for (y in 2 until WORLD_HEIGHT - 1) tiles[y * WORLD_WIDTH + 5] = 0
        for (x in 2 until WORLD_WIDTH - 1) tiles[(WORLD_HEIGHT - 4) * WORLD_WIDTH + x] = 0
        tiles[4 * WORLD_WIDTH + 4] = 7
        tiles[10 * WORLD_WIDTH + 13] = 0
        tiles[17 * WORLD_WIDTH + 23] = 0
        val mobs = listOf(
            ExpeditionMob(1, 13, 10, 4),
            ExpeditionMob(2, 23, 17, 4),
        )
        return BlockIslandState(
            ExpeditionState(
                mode = ExpeditionMode.HOMESTEAD,
                seed = seed,
                width = WORLD_WIDTH,
                height = WORLD_HEIGHT,
                tiles = tiles,
                x = 4,
                y = 5,
                friends = emptyList(),
                mobs = mobs,
                events = listOf("来到方块小岛，新的家园从这里开始"),
            ),
        ).withPartners(partners)
    }

    fun BlockIslandState.withPartners(ids: List<String>): BlockIslandState {
        val old = world.friends.associateBy { it.id }
        val spawn = listOf(5 to 4, 6 to 4, 6 to 6, 5 to 6)
        return copy(world = world.copy(friends = ids.distinct().filter(String::isNotBlank).take(3)
            .mapIndexed { index, id ->
                old[id] ?: ExpeditionFriend(id, spawn[index].first, spawn[index].second, mode = "follow")
            }))
    }

    fun setCompanionMode(s: BlockIslandState, id: String, mode: String): BlockIslandState {
        if (mode !in setOf("follow", "gather", "guard")) return s
        if (s.world.friends.none { it.id == id }) return s
        return s.copy(world = s.world.copy(
            friends = s.world.friends.map { if (it.id == id) it.copy(mode = mode) else it },
            notice = "已经为队友设置：${when (mode) { "gather" -> "采集"; "guard" -> "守卫"; else -> "跟随" }}",
        ))
    }

    fun isWalkable(s: BlockIslandState, x: Int, y: Int): Boolean {
        val w = s.world
        if (!w.inBounds(x, y) || w.cell(x, y) !in setOf(0, 4, 7)) return false
        return s.structures[w.index(x, y)] !in setOf("workbench", "campfire", "bed")
    }

    fun act(s: BlockIslandState, action: String, tx: Int = s.world.x, ty: Int = s.world.y): BlockIslandState {
        val w = s.world
        fun reject(reason: String) = s.copy(world = w.copy(notice = reason))
        fun near(x: Int = tx, y: Int = ty) = abs(w.x - x) + abs(w.y - y) <= 1
        if (w.finished) return reject("这个存档已结算")
        if (!w.inBounds(tx, ty)) return reject("目标在地图外")
        val cell = w.cell(tx, ty)
        val index = w.index(tx, ty)
        val mob = w.mobs.firstOrNull { it.hp > 0 && it.x == tx && it.y == ty }
        var next = s
        val message: String
        when (action) {
            "move" -> {
                if (!near() || tx == w.x && ty == w.y || !isWalkable(s, tx, ty) || mob != null)
                    return reject("这格不能走，可以先清除资源或绕路")
                next = s.copy(world = w.copy(x = tx, y = ty))
                message = "走到了 ($tx,$ty)"
            }
            "gather" -> {
                if (!near() || cell !in setOf(1, 2)) return reject("靠近树林或石矿才能采集")
                val extra = if (cell == 1 && "axe" in s.tools || cell == 2 && "pickaxe" in s.tools) 2 else 0
                val amount = 2 + extra
                val berry = if (cell == 1 && (index + w.seed).and(Int.MAX_VALUE) % 3 == 0) 1 else 0
                next = s.copy(world = w.setTile(tx, ty, 0).copy(
                    wood = w.wood + if (cell == 1) amount else 0,
                    stone = w.stone + if (cell == 2) amount else 0,
                ), fruit = s.fruit + berry)
                message = if (cell == 1) "伐木 +$amount${if (berry > 0) "，找到野果 +1" else ""}"
                else "开采矿石 +$amount"
            }
            "wall", "floor", "door", "workbench", "campfire", "bed" -> {
                if (!near() || (tx == w.x && ty == w.y) ||
                    action == "floor" && cell == 4 ||
                    w.friends.any { it.hp > 0 && it.x == tx && it.y == ty } || mob != null ||
                    (cell !in setOf(0, 4) && !(action == "door" && cell == 3)) ||
                    index in s.structures) return reject("这里无法放置，靠近空地后再试")
                val (woodCost, stoneCost) = when (action) {
                    "wall" -> 2 to 0
                    "floor" -> 1 to 0
                    "door" -> 2 to 0
                    "workbench" -> 4 to 2
                    "bed" -> 3 to 1
                    else -> 2 to 1
                }
                if (w.wood < woodCost || w.stone < stoneCost)
                    return reject("材料不足：需要木材$woodCost、石料$stoneCost")
                val newTile = when (action) {
                    "wall" -> 3
                    "floor", "door" -> 4
                    else -> cell
                }
                val updated = w.setTile(tx, ty, newTile).copy(wood = w.wood - woodCost, stone = w.stone - stoneCost)
                val added = if (action in setOf("door", "workbench", "campfire", "bed")) s.structures + (index to action)
                    else s.structures
                next = s.copy(world = updated, structures = added)
                message = "建造成功：${actionName(action)}"
            }
            "plant" -> {
                if (!near() || cell != 0 || index in s.structures ||
                    (tx == w.x && ty == w.y) ||
                    w.friends.any { it.hp > 0 && it.x == tx && it.y == ty } ||
                    mob != null) return reject("在身边的空草地种树")
                if (w.wood < 1) return reject("种植树苗需要1木材")
                next = s.copy(world = w.setTile(tx, ty, 1).copy(wood = w.wood - 1))
                message = "种下一棵树，未来可以再次采集木材"
            }
            "remove" -> {
                if (!near() || cell == 7 || cell !in setOf(3, 4) && index !in s.structures)
                    return reject("只能拆除附近自己建造的设施")
                val structure = s.structures[index]
                val refundWood = when (structure) {
                    "workbench" -> 2
                    "campfire", "door", "bed" -> 1
                    else -> if (cell == 3 || cell == 4) 1 else 0
                }
                val refundStone = if (structure == "workbench") 1 else 0
                next = s.copy(
                    world = w.setTile(tx, ty, 0).copy(wood = w.wood + refundWood, stone = w.stone + refundStone),
                    structures = s.structures - index,
                )
                message = "拆除了${if (structure == null) "建筑" else actionName(structure)}，返还部分材料"
            }
            "craft_axe", "craft_pickaxe", "craft_sword" -> {
                val tool = action.removePrefix("craft_")
                if (tool in s.tools) return reject("已经制作过这件工具")
                val tableNear = s.structures.any { (location, kind) ->
                    kind == "workbench" && distance(w.x, w.y, location % w.width, location / w.width) <= 2
                }
                if (!tableNear) return reject("靠近工作台才能制作工具")
                val cost = when (tool) {
                    "axe" -> 2 to 1
                    "pickaxe" -> 2 to 2
                    else -> 1 to 3
                }
                if (w.wood < cost.first || w.stone < cost.second)
                    return reject("需要木材${cost.first}、石料${cost.second}")
                next = s.copy(world = w.copy(wood = w.wood - cost.first, stone = w.stone - cost.second),
                    tools = s.tools + tool)
                message = "制作成功：${actionName(tool)}"
            }
            "revive" -> {
                val companion = w.friends.firstOrNull { it.hp <= 0 && it.x == tx && it.y == ty }
                    ?: return reject("选中倒下的队友才能救援")
                if (!near()) return reject("需要靠近倒下的队友")
                if (s.fruit < 2) return reject("救援需要2颗野果")
                next = s.copy(fruit = s.fruit - 2, world = w.copy(
                    friends = w.friends.map { if (it.id == companion.id) it.copy(hp = 4) else it },
                ))
                message = "救起了队友[${companion.id}]，恢复生命至4"
            }
            "eat" -> {
                if (s.fruit < 1) return reject("背包里没有野果，砍树时可能找到")
                if (w.hp >= 12) return reject("现在生命值是满的")
                next = s.copy(world = w.copy(hp = (w.hp + 3).coerceAtMost(12)), fruit = s.fruit - 1)
                message = "吃下野果，恢复生命"
            }
            "rest" -> {
                val campNear = s.structures.any { (location, kind) ->
                    kind in setOf("campfire", "bed") &&
                        distance(w.x, w.y, location % w.width, location / w.width) <= 1
                }
                if (!campNear) return reject("需要站在篝火或床铺旁边")
                if (w.hp == 12) return reject("生命已满")
                if (w.turns - s.lastRestTurn < 8) return reject("刚休息过，探索几步后再休息")
                next = s.copy(world = w.copy(hp = (w.hp + 4).coerceAtMost(12)), lastRestTurn = w.turns)
                message = "在篝火旁恢复了体力"
            }
            "fight" -> {
                if (!near() || mob == null) return reject("靠近怪物才能攻击")
                val damage = if ("sword" in s.tools) 4 else 2
                val dead = mob.hp <= damage
                next = s.copy(
                    world = w.copy(mobs = w.mobs.map {
                        if (it.id == mob.id) it.copy(hp = (it.hp - damage).coerceAtLeast(0)) else it
                    }),
                    fruit = s.fruit + if (dead) 1 else 0,
                )
                message = if (dead) "击退怪物，得到野果 +1" else "攻击怪物，造成$damage点伤害"
            }
            else -> return reject("未知操作")
        }
        return advance(next, message)
    }

    private fun advance(s: BlockIslandState, message: String): BlockIslandState {
        var result = s.copy(world = s.world.copy(
            turns = s.world.turns + 1,
            notice = message,
            events = (s.world.events + message).takeLast(150),
        ))
        result = companionTurn(result)
        result = monstersTurn(result)
        result = respawnMonsters(result)
        result = replenishResources(result)
        return result
    }

    private fun companionTurn(s: BlockIslandState): BlockIslandState {
        var world = s.world
        val updates = mutableListOf<String>()
        val friends = world.friends.map { friend ->
            if (friend.hp <= 0) return@map friend
            val threat = world.mobs.firstOrNull { it.hp > 0 && distance(it.x, it.y, friend.x, friend.y) <= 1 }
            if (threat != null) {
                world = world.copy(mobs = world.mobs.map {
                    if (it.id == threat.id) it.copy(hp = (it.hp - 1).coerceAtLeast(0)) else it
                })
                updates += "队友[${friend.id}] 击中了怪物"
                return@map friend
            }
            if (friend.mode == "gather") {
                val resource = (0 until world.height).flatMap { y -> (0 until world.width).map { x -> x to y } }
                    .filter { (x, y) -> world.cell(x, y) in setOf(1, 2) &&
                        distance(friend.x, friend.y, x, y) <= 7 }
                    .sortedBy { (x, y) -> distance(friend.x, friend.y, x, y) }
                    .firstOrNull { (x, y) ->
                        distance(friend.x, friend.y, x, y) <= 1 ||
                            nextStep(s.copy(world = world), friend.x, friend.y, x, y) != null
                    }
                if (resource != null) {
                    val (tx, ty) = resource
                    if (distance(friend.x, friend.y, tx, ty) <= 1) {
                        val tile = world.cell(tx, ty)
                        world = world.setTile(tx, ty, 0).copy(
                            wood = world.wood + if (tile == 1) 1 else 0,
                            stone = world.stone + if (tile == 2) 1 else 0,
                        )
                        updates += "队友[${friend.id}] 采集了${if (tile == 1) "木材" else "石料"}"
                        return@map friend
                    }
                    val step = nextStep(s.copy(world = world), friend.x, friend.y, tx, ty)
                    if (step != null) return@map friend.copy(x = step.first, y = step.second)
                }
            }
            if (friend.mode == "guard") {
                val target = world.mobs.filter { it.hp > 0 && distance(friend.x, friend.y, it.x, it.y) <= 6 }
                    .minByOrNull { distance(friend.x, friend.y, it.x, it.y) }
                if (target != null) {
                    val step = nextStep(s.copy(world = world), friend.x, friend.y, target.x, target.y)
                    if (step != null) return@map friend.copy(x = step.first, y = step.second)
                }
            }
            if (distance(friend.x, friend.y, world.x, world.y) > 2) {
                val step = nextStep(s.copy(world = world), friend.x, friend.y, world.x, world.y)
                if (step != null) return@map friend.copy(x = step.first, y = step.second)
            }
            friend
        }
        world = world.copy(friends = friends, events = (world.events + updates).takeLast(150),
            notice = world.notice + if (updates.isNotEmpty()) " · " + updates.take(1).joinToString() else "")
        return s.copy(world = world)
    }

    private fun monstersTurn(s: BlockIslandState): BlockIslandState {
        val world = s.world
        val hurtFriends = mutableMapOf<String, Int>()
        var playerHurt = 0
        val mobs = world.mobs.map { mob ->
            if (mob.hp <= 0) return@map mob
            val targets = listOf(world.x to world.y) + world.friends.filter { it.hp > 0 }.map { it.x to it.y }
            val target = targets.minByOrNull { distance(mob.x, mob.y, it.first, it.second) } ?: return@map mob
            if (distance(mob.x, mob.y, target.first, target.second) <= 1) {
                if (target.first == world.x && target.second == world.y) playerHurt++
                else world.friends.firstOrNull { it.x == target.first && it.y == target.second }?.let {
                    hurtFriends[it.id] = (hurtFriends[it.id] ?: 0) + 1
                }
                return@map mob
            }
            if (distance(mob.x, mob.y, target.first, target.second) > 5) return@map mob
            val next = nextStep(s, mob.x, mob.y, target.first, target.second)
            if (next == null || next.first == world.x && next.second == world.y ||
                world.friends.any { it.hp > 0 && it.x == next.first && it.y == next.second }) mob
            else mob.copy(x = next.first, y = next.second)
        }
        val hp = (world.hp - playerHurt).coerceAtLeast(0)
        val allies = world.friends.map { it.copy(hp = (it.hp - (hurtFriends[it.id] ?: 0)).coerceAtLeast(0)) }
        val logs = mutableListOf<String>()
        if (playerHurt > 0) logs += "怪物攻击，生命 -$playerHurt"
        if (hurtFriends.isNotEmpty()) logs += "有队友受伤了"
        var next = s.copy(world = world.copy(hp = hp, mobs = mobs, friends = allies,
            notice = world.notice + if (logs.isNotEmpty()) " · " + logs.joinToString() else "",
            events = (world.events + logs).takeLast(150)))
        if (hp == 0) {
            next = next.copy(world = next.world.copy(x = 4.coerceAtMost(world.width - 2),
                y = 5.coerceAtMost(world.height - 2), hp = 12,
                notice = "在营地复活，家园和物品全部保留",
                events = (next.world.events + "在营地复活").takeLast(150)))
        }
        return next
    }

    private fun respawnMonsters(s: BlockIslandState): BlockIslandState {
        val w = s.world
        if (w.turns % 22 != 0 || w.mobs.count { it.hp > 0 } >= 3) return s
        val rng = Random(w.seed xor w.turns)
        val spots = (0 until 30).map {
            rng.nextInt(2, w.width - 2) to rng.nextInt(2, w.height - 2)
        }
        val place = spots.firstOrNull { (x, y) -> isWalkable(s, x, y) &&
            distance(x, y, w.x, w.y) > 7 &&
            w.friends.none { it.x == x && it.y == y } &&
            w.mobs.none { it.hp > 0 && it.x == x && it.y == y } }
            ?: return s
        val id = (w.mobs.maxOfOrNull { it.id } ?: 0) + 1
        return s.copy(world = w.copy(mobs = w.mobs.filter { it.hp > 0 } +
            ExpeditionMob(id, place.first, place.second, 4),
            events = (w.events + "岛上出现了新的野怪").takeLast(150)))
    }

    /** Renewable ore/trees: established buildings, water and occupied cells are never overwritten. */
    private fun replenishResources(s: BlockIslandState): BlockIslandState {
        val w = s.world
        if (w.turns % 28 != 0) return s
        val rng = Random(w.seed xor (w.turns * 31))
        val spots = (0 until 35).map {
            rng.nextInt(2, w.width - 2) to rng.nextInt(2, w.height - 2)
        }
        val place = spots.firstOrNull { (x, y) ->
            w.cell(x, y) == 0 && w.index(x, y) !in s.structures &&
                distance(x, y, w.x, w.y) > 5 &&
                w.friends.none { it.x == x && it.y == y } &&
                w.mobs.none { it.hp > 0 && it.x == x && it.y == y }
        } ?: return s
        val material = if (rng.nextInt(3) == 0) 2 else 1
        return s.copy(world = w.setTile(place.first, place.second, material).copy(
            events = (w.events + "野外出现了新的${if (material == 1) "树木" else "矿石"}").takeLast(150),
        ))
    }

    /**
     * Find an actual traversable first step, including the approach to an impassable tree or monster.
     * Used by the player and NPCs, never by the UI to fake movement.
     */
    fun nextStep(s: BlockIslandState, fromX: Int, fromY: Int, toX: Int, toY: Int): Pair<Int, Int>? {
        val w = s.world
        if (!w.inBounds(fromX, fromY) || !w.inBounds(toX, toY)) return null
        val targetOpen = isWalkable(s, toX, toY) &&
            w.mobs.none { it.hp > 0 && it.x == toX && it.y == toY }
        val ends = if (targetOpen) setOf(w.index(toX, toY)) else
            neighbors(toX, toY).filter { (x, y) -> isWalkable(s, x, y) }
                .map { (x, y) -> w.index(x, y) }.toSet()
        val start = w.index(fromX, fromY)
        val seen = IntArray(w.width * w.height) { -1 }
        val queue = java.util.ArrayDeque<Int>()
        seen[start] = start
        queue.add(start)
        var final = -1
        while (queue.isNotEmpty()) {
            val at = queue.removeFirst()
            if (at in ends) { final = at; break }
            neighbors(at % w.width, at / w.width).forEach { (x, y) ->
                if (!isWalkable(s, x, y)) return@forEach
                val next = w.index(x, y)
                if (seen[next] != -1 ||
                    w.mobs.any { it.hp > 0 && it.x == x && it.y == y }) return@forEach
                seen[next] = at
                queue.add(next)
            }
        }
        if (final < 0 || final == start) return null
        var cursor = final
        while (seen[cursor] != start && seen[cursor] != cursor) cursor = seen[cursor]
        return cursor % w.width to cursor / w.width
    }

    private fun neighbors(x: Int, y: Int) = listOf(x + 1 to y, x - 1 to y, x to y + 1, x to y - 1)
    private fun distance(ax: Int, ay: Int, bx: Int, by: Int) = abs(ax - bx) + abs(ay - by)
    private fun ExpeditionState.setTile(x: Int, y: Int, tile: Int) =
        copy(tiles = tiles.toMutableList().also { it[index(x, y)] = tile })
    fun actionName(action: String): String = when (action) {
        "axe" -> "木斧"; "pickaxe" -> "石镐"; "sword" -> "石剑"
        "wall" -> "木墙"; "floor" -> "木地板"; "door" -> "木门"
        "workbench" -> "工作台"; "campfire" -> "篝火"; "bed" -> "木床"
        else -> action
    }
}
