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
    val fish: Int = 0,
    val lastFishTurn: Int = -20,
    val companionTravel: Map<String, IslandTravelPlan> = emptyMap(),
    val supplyCaches: List<Int> = emptyList(),
    val openedSupplies: Set<Int> = emptySet(),
    val houseProject: IslandHouseProject? = null,
    val guardedBy: String = "",
    val hunger: Int = 10,
    val nightsSurvived: Int = 0,
    val defeatedMonsters: Int = 0,
    val lastKnockoutTurn: Int = -100,
    val animals: List<IslandAnimal> = emptyList(),
    val discoveredSpecies: Set<String> = emptySet(),
    /** Completed one-shot challenges are part of the same authoritative island save. */
    val awardedChallenges: Set<String> = emptySet(),
    /** Defeat is permanent even after the dead boss is cleaned from world.mobs. */
    val guardianCleared: Boolean = false,
    /** Crop progress belongs to the same saved island, never UI or a timer. */
    val gardens: Map<Int, IslandGardenCrop> = emptyMap(),
    /** Mined crystals are real spendable inventory, not decorative sparkles. */
    val crystals: Int = 0,
    /** Lost resources on knockout persist until physically reclaimed. */
    val lostPacks: Map<Int,IslandLostPack> = emptyMap(),
) {
    fun toJson(): String = JSONObject()
        .put("version", 2)
        .put("world", world.toJson())
        .put("fruit", fruit).put("crystals", crystals)
        .put("tools", JSONArray(tools.sorted()))
        .put("structures", JSONObject().apply {
            structures.forEach { (index, item) -> put(index.toString(), item) }
        })
        .put("companionTravel", JSONObject().apply {
            companionTravel.forEach { (id,plan) -> put(id,plan.json()) }
        })
        .put("lastRestTurn", lastRestTurn)
        .put("fish", fish).put("lastFishTurn", lastFishTurn)
        .put("supplyCaches", JSONArray(supplyCaches))
        .put("openedSupplies", JSONArray(openedSupplies.sorted()))
        .put("houseProject", houseProject?.json() ?: JSONObject.NULL)
        .put("guardedBy",guardedBy)
        .put("hunger",hunger).put("nightsSurvived",nightsSurvived)
        .put("defeatedMonsters",defeatedMonsters)
        .put("lastKnockoutTurn",lastKnockoutTurn)
        .put("animals",JSONArray(animals.map { it.json() }))
        .put("discoveredSpecies",JSONArray(discoveredSpecies.sorted()))
        .put("awardedChallenges",JSONArray(awardedChallenges.sorted()))
        .put("guardianCleared",guardianCleared)
        .put("gardens",JSONObject().apply {
            gardens.forEach { (index, crop) -> put(index.toString(),crop.json()) }
        })
        .put("lostPacks",JSONObject().apply {
            lostPacks.forEach { (index, pack) -> put(index.toString(),pack.json()) }
        })
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
            if (tools.any { it !in setOf("axe", "pickaxe", "sword", "rod", "charm") }) return@runCatching null
            val obj = json.optJSONObject("structures") ?: JSONObject()
            val positions = mutableMapOf<Int, String>()
            val keys = obj.keys()
            while (keys.hasNext()) {
                val key = keys.next()
                val index = key.toIntOrNull() ?: return@runCatching null
                val kind = obj.getString(key)
                if (index !in world.tiles.indices ||
                    kind !in setOf("door", "workbench", "campfire", "bed", "bridge", "lantern", "crystal_lantern", "pet_house", "garden") ||
                    (if (kind == "bridge") world.tiles[index] != 8 else world.tiles[index] !in listOf(0, 4))
                ) return@runCatching null
                positions[index] = kind
            }
            val fruit = json.optInt("fruit")
            val fish = json.optInt("fish", 0)
            if (fruit < 0 || fish < 0 || json.optInt("crystals",0) < 0) return@runCatching null
            val base = BlockIslandState(world, fruit, tools, positions,
                json.optInt("lastRestTurn", -20), fish, json.optInt("lastFishTurn", -20),
                IslandTravel.restore(json.optJSONObject("companionTravel"),world))
            val rawCaches = json.optJSONArray("supplyCaches")
            val caches = if (rawCaches == null) IslandSupplyCaches.generate(base)
                else (0 until rawCaches.length()).mapNotNull { i ->
                    rawCaches.optInt(i, -1).takeIf { it in world.tiles.indices }
                }.distinct()
            val opened = json.optJSONArray("openedSupplies")?.let { a ->
                (0 until a.length()).mapNotNull { i ->
                    a.optInt(i, -1).takeIf { it in caches }
                }.toSet()
            } ?: emptySet()
            val restored=base.copy(supplyCaches=caches, openedSupplies=opened,
                houseProject=IslandHouseProject.read(json.optJSONObject("houseProject"),world),
                guardedBy=json.optString("guardedBy","").takeIf { id ->
                    world.friends.any { it.id==id }
                } ?: "",
                hunger=json.optInt("hunger",10).coerceIn(0,10),
                nightsSurvived=json.optInt("nightsSurvived",0).coerceAtLeast(0),
                defeatedMonsters=json.optInt("defeatedMonsters",0).coerceAtLeast(0),
                lastKnockoutTurn=json.optInt("lastKnockoutTurn",-100))
            val savedAnimals=json.optJSONArray("animals")
            val animals=if(savedAnimals == null && !json.has("animals"))
                IslandWildlife.generate(restored)
                else (0 until (savedAnimals?.length() ?: 0)).mapNotNull { i ->
                    savedAnimals?.optJSONObject(i)?.let { IslandAnimal.read(it,restored) }
                }.distinctBy { it.id }
            restored.copy(animals=animals,
                gardens=IslandGarden.restore(json.optJSONObject("gardens"),restored),
                crystals=json.optInt("crystals",0),
                lostPacks=IslandLostPack.restore(json.optJSONObject("lostPacks"),world),
                discoveredSpecies=json.optJSONArray("discoveredSpecies")?.let { a ->
                    (0 until a.length()).mapNotNull { i ->
                        a.optString(i).takeIf { it in IslandWildlife.species }
                    }.toSet()
                } ?: animals.filter { it.befriended }.map { it.species }.toSet(),
                awardedChallenges=json.optJSONArray("awardedChallenges")?.let { a ->
                    (0 until a.length()).mapNotNull { i ->
                        a.optString(i).takeIf { it in IslandChallengeBoard.ids }
                    }.toSet()
                } ?: emptySet(),
                guardianCleared=json.optBoolean("guardianCleared",false))
        }.getOrNull()

        fun fromLegacy(legacy: ExpeditionState): BlockIslandState? =
            legacy.takeIf { it.mode == ExpeditionMode.HOMESTEAD }?.let {
                BlockIslandState(it).let { base ->
                    val withCaches=base.copy(supplyCaches=IslandSupplyCaches.generate(base))
                    withCaches.copy(animals=IslandWildlife.generate(withCaches))
                }
            }
    }
}

/** Derived only from the authoritative island save, not from UI-local quest flags. */
internal object IslandMilestones {
    fun next(s: BlockIslandState): String {
        val w = s.world
        val hasTable = s.structures.values.any { it == "workbench" }
        val hasShelter = s.structures.values.any { it in setOf("campfire", "bed") }
        val nextLandmark=IslandExpeditionTrail.next(s)
        // Survival comes before distant milestones when the player is hungry.
        // This text derives from real inventory, never grants free food.
        if (s.hunger <= 3) return when {
            s.fruit > 0 -> "饥饿警报 · 打开行囊吃野果，补充饱食"
            s.fish > 0 && s.structures.values.any { it=="campfire" } ->
                "饥饿警报 · 回到篝火旁烤鱼吃"
            s.gardens.isNotEmpty() -> "饥饿警报 · 照料菜圃等待收获，同时寻找野果"
            else -> "饥饿警报 · 砍树找野果，或先制作鱼竿钓鱼"
        }
        return when {
            !hasTable -> "生存 1/5 · 收集4木2石，建造工作台"
            "sword" !in s.tools -> "生存 2/5 · 在工作台打造石剑"
            !hasShelter -> "生存 3/5 · 建造篝火或床铺"
            s.defeatedMonsters == 0 -> "生存 4/5 · 击退野怪，保护家园"
            s.nightsSurvived < 3 -> "生存 5/5 · 守住3夜（${s.nightsSurvived}/3）"
            nextLandmark != null ->
                "远航 ${IslandExpeditionTrail.completedCount(s)+1}/${IslandExpeditionTrail.landmarks.size} · 前往${nextLandmark.title}（距离${IslandExpeditionTrail.distance(s,nextLandmark)}格）"
            s.supplyCaches.any { it !in s.openedSupplies } ->
                "探险 1/3 · 搜寻补给宝箱（${s.openedSupplies.size}/${s.supplyCaches.size}）"
            !s.tools.containsAll(setOf("axe", "pickaxe", "sword", "rod")) ->
                "探险 2/3 · 收集全套工具（${s.tools.size}/4）"
            s.structures.values.none { it == "bridge" } ->
                "探险 3/3 · 在非边界水面搭一座木桥"
            s.animals.any { !it.befriended } ->
                "生态收集 · 结识小岛动物（${s.animals.count { it.befriended }}/${s.animals.size}）"
            s.structures.values.none { it=="pet_house" } ->
                "家园 1/2 · 为动物伙伴建造温暖小屋"
            s.structures.values.none { it=="lantern" } ->
                "家园 2/2 · 建造夜灯，点亮自己的家"
            IslandGuardianEncounter.ready(s) ->
                "终章 · ${IslandGuardianEncounter.title} · "+
                (IslandGuardianEncounter.active(s)?.let { "生命${it.hp}/${IslandGuardianEncounter.hp}" } ?: "守卫将在南岭现身")
            else -> "生存、拓荒、生态与家园目标达成！继续创造自己的世界"
        }
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
        ).let { base ->
            val withCaches=base.copy(supplyCaches=IslandSupplyCaches.generate(base))
            val residents=withCaches.withPartners(partners)
            residents.copy(animals=IslandWildlife.generate(residents))
        }
    }

    fun BlockIslandState.withPartners(ids: List<String>): BlockIslandState {
        val old = world.friends.associateBy { it.id }
        val spawn = listOf(5 to 4, 6 to 4, 6 to 6, 5 to 6)
        val participants = ids.distinct().filter(String::isNotBlank).take(3)
        return copy(companionTravel=companionTravel.filterKeys { it in participants },
            world = world.copy(friends = participants
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
        if (!w.inBounds(x, y)) return false
        val structure = s.structures[w.index(x, y)]
        if (w.cell(x, y) == 8) return structure == "bridge"
        if (w.cell(x, y) !in setOf(0, 4, 7)) return false
        return structure !in setOf("workbench", "campfire", "bed", "lantern", "crystal_lantern", "pet_house", "garden")
    }

    private fun buildingCost(action: String): Pair<Int, Int>? = when (action) {
        "wall", "door" -> 2 to 0
        "floor" -> 1 to 0
        "workbench" -> 4 to 2
        "campfire" -> 2 to 1
        "bed" -> 3 to 1
        "bridge" -> 3 to 0
        "lantern" -> 2 to 1
        "crystal_lantern" -> 3 to 2
        "pet_house" -> 4 to 2
        "garden" -> 2 to 0
        else -> null
    }

    /** Same placement policy for the rule engine and the visible building HUD. */
    fun canPlaceBuilding(s: BlockIslandState, action: String, x: Int, y: Int): Boolean {
        val w = s.world
        if (buildingCost(action) == null || w.finished || !w.inBounds(x, y)) return false
        val cell = w.cell(x, y)
        if (abs(w.x - x) + abs(w.y - y) > 1 ||
            (x == w.x && y == w.y) ||
            IslandSupplyCaches.available(s, x, y) ||
            w.index(x,y) in s.lostPacks ||
            (action == "floor" && cell == 4) ||
            w.friends.any { it.hp > 0 && it.x == x && it.y == y } ||
            w.mobs.any { it.hp > 0 && it.x == x && it.y == y } ||
            s.animals.any { it.x == x && it.y == y } ||
            (if (action == "bridge") cell != 8 ||
                x == 0 || y == 0 || x == w.width - 1 || y == w.height - 1
            else cell !in setOf(0, 4) && !(action == "door" && cell == 3)) ||
            w.index(x, y) in s.structures
        ) return false
        return true
    }

    fun canBuild(s: BlockIslandState, action: String, x: Int, y: Int): Boolean {
        val (wood, stone) = buildingCost(action) ?: return false
        return canPlaceBuilding(s, action, x, y) &&
            s.world.wood >= wood && s.world.stone >= stone &&
            (action != "crystal_lantern" || s.crystals >= 1)
    }

    fun canPlantAt(s: BlockIslandState, x: Int, y: Int): Boolean {
        val w = s.world
        if (w.finished || !w.inBounds(x, y)) return false
        return abs(w.x - x) + abs(w.y - y) <= 1 && w.cell(x, y) == 0 &&
            w.index(x, y) !in s.structures &&
            !IslandSupplyCaches.available(s, x, y) && !(x == w.x && y == w.y) &&
            w.friends.none { it.hp > 0 && it.x == x && it.y == y } &&
            w.mobs.none { it.hp > 0 && it.x == x && it.y == y } &&
            s.animals.none { it.x == x && it.y == y }
    }

    fun canRemoveAt(s: BlockIslandState, x: Int, y: Int): Boolean {
        val w = s.world
        if (w.finished || !w.inBounds(x, y)) return false
        val cell = w.cell(x, y)
        return abs(w.x - x) + abs(w.y - y) <= 1 && cell != 7 &&
            (cell in setOf(3, 4) || w.index(x, y) in s.structures) &&
            !(x == w.x && y == w.y) &&
            w.friends.none { it.hp > 0 && it.x == x && it.y == y } &&
            w.mobs.none { it.hp > 0 && it.x == x && it.y == y } &&
            s.animals.none { it.x == x && it.y == y }
    }

    fun act(s: BlockIslandState, action: String, tx: Int = s.world.x, ty: Int = s.world.y,
        autonomousIds: Set<String> = emptySet()): BlockIslandState {
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
            "recover_pack" -> {
                val pack=s.lostPacks[index] ?: return reject("这里没有遗失的背包")
                if(!near() || mob != null) return reject("先靠近背包并清理附近怪物")
                next=s.copy(
                    world=w.copy(wood=w.wood+pack.wood,stone=w.stone+pack.stone),
                    fruit=s.fruit+pack.fruit,crystals=s.crystals+pack.crystals,
                    lostPacks=s.lostPacks-index)
                message="找回遗失物资：木${pack.wood}、石${pack.stone}、野果${pack.fruit}、晶石${pack.crystals}"
            }
            "salvage" -> {
                if (!near() || !IslandSupplyCaches.available(s, tx, ty) ||
                    mob != null || w.friends.any { it.hp <= 0 && it.x == tx && it.y == ty })
                    return reject("需要先靠近补给箱，并清理占用它的危险")
                val woodFound = 2 + index % 3
                val stoneFound = 1 + index % 2
                val berryFound = if (((index xor w.seed) and 1) == 0) 1 else 0
                next = s.copy(
                    openedSupplies = s.openedSupplies + index,
                    world = w.copy(wood = w.wood + woodFound, stone = w.stone + stoneFound),
                    fruit = s.fruit + berryFound,
                )
                message = "找到野外补给：木材+$woodFound、石料+$stoneFound" +
                    if (berryFound > 0) "、野果+$berryFound" else ""
            }
            "move" -> {
                if (!near() || tx == w.x && ty == w.y || !isWalkable(s, tx, ty) ||
                    mob != null || w.friends.any { it.x == tx && it.y == ty } ||
                    s.animals.any { it.x==tx && it.y==ty })
                    return reject("这里有伙伴或动物，需要绕开或进行互动")
                next = s.copy(world = w.copy(x = tx, y = ty))
                message = "走到了 ($tx,$ty)"
            }
            "gather" -> {
                if (!near() || cell !in setOf(1, 2)) return reject("靠近树林或石矿才能采集")
                val richOre = cell == 2 && IslandProgression.crystalVein(w.seed,index)
                if (richOre && "pickaxe" !in s.tools)
                    return reject("这块蓝色晶矿必须用石镐开采。先挖普通石头做石镐")
                val bonus = if (cell == 1 && "axe" in s.tools || cell == 2 && "pickaxe" in s.tools) 2 else 0
                val amount = 2 + bonus
                val berry = if (cell == 1 && (index + w.seed).and(Int.MAX_VALUE) % 3 == 0) 1 else 0
                next = s.copy(world = w.setTile(tx, ty, 0).copy(
                    wood = w.wood + if (cell == 1) amount else 0,
                    stone = w.stone + if (cell == 2) amount else 0,
                ), fruit = s.fruit + berry,
                    crystals = s.crystals + if(richOre) 1 else 0)
                message = if (cell == 1)
                    "伐木 +$amount${if(bonus>0)"（木斧增产）" else "（徒手）"}${if(berry>0)"，野果 +1" else ""}"
                else "采矿 +$amount${if(bonus>0)"（石镐增产）" else "（徒手）"}${if(richOre)"，晶石 +1！可造护夜灯/护符" else ""}"
            }
            "house_warm", "house_pine" -> {
                if (!IslandHouseProjects.canStartPlayer(s,tx,ty))
                    return reject("这里放不下完整的3×3小屋；先找附近九格连续空地")
                val style=if(action=="house_pine")"pine" else "warm"
                next=s.copy(houseProject=IslandHouseProject("player",tx,ty,style=style))
                message="画好了${IslandHouseProjects.styleName(style)}蓝图！走到高亮材料格旁，分9步施工，共需17木"
            }
            "house_step" -> {
                val project=s.houseProject
                val part=IslandHouseProjects.nextPlayerPart(s)
                    ?: return reject("还没有正在施工的个人小屋")
                if(project==null || part.first!=index || !IslandHouseProjects.canPlayerBuildNext(s))
                    return reject("走近下一处施工位，备足1～2木材后再施工")
                val (cellIndex,kind)=part
                val cost=if(kind=="floor")1 else 2
                val newTiles=w.tiles.toMutableList().also { it[cellIndex]=if(kind=="wall")3 else 4 }
                val placed=project.placed+cellIndex
                next=s.copy(
                    world=w.copy(tiles=newTiles,wood=w.wood-cost),
                    structures=if(kind=="door")s.structures+(cellIndex to "door")else s.structures,
                    houseProject=project.copy(placed=placed,
                        phase=if(placed.size==9)"complete" else "building"),
                )
                message=if(placed.size==9)
                    "✦ ${IslandHouseProjects.styleName(project.style)}完工！真正的屋顶和外墙已经出现在地图上"
                else "亲手安放${if(kind=="floor")"地板" else if(kind=="door")"木门" else "墙体"} · 小屋${placed.size}/9"
            }
            "wall", "floor", "door", "workbench", "campfire", "bed", "bridge", "lantern", "crystal_lantern", "pet_house", "garden" -> {
                if (!canPlaceBuilding(s, action, tx, ty))
                    return reject("这里无法放置，靠近空地后再试")
                val (woodCost, stoneCost) = checkNotNull(buildingCost(action))
                if (w.wood < woodCost || w.stone < stoneCost ||
                    (action=="crystal_lantern" && s.crystals<1))
                    return reject("材料不足：需要木材$woodCost、石料$stoneCost" +
                        (if(action=="crystal_lantern")"、晶石1" else ""))
                val newTile = when (action) {
                    "wall" -> 3
                    "floor", "door" -> 4
                    else -> cell
                }
                val updated = w.setTile(tx, ty, newTile).copy(wood = w.wood - woodCost, stone = w.stone - stoneCost)
                val added = if (action in setOf("door", "workbench", "campfire", "bed", "bridge", "lantern", "crystal_lantern", "pet_house", "garden")) s.structures + (index to action)
                    else s.structures
                next = s.copy(world = updated, structures = added,
                    crystals=s.crystals-(if(action=="crystal_lantern")1 else 0))
                message = "建造成功：${actionName(action)}"
            }
            "sow_berries" -> {
                if (!near() || s.structures[index] != "garden" || index in s.gardens)
                    return reject("靠近一块空菜圃才能播种")
                if (s.fruit < 1) return reject("播种需要背包中的1颗野果")
                next = s.copy(fruit=s.fruit-1,
                    gardens=s.gardens + (index to IslandGardenCrop(w.turns)))
                message = "埋下1颗野果种子，至少4步后回来松土"
            }
            "tend_berries" -> {
                val crop=s.gardens[index] ?: return reject("这里还没有种下野果")
                if (!near() || s.structures[index]!="garden" ||
                    crop.tendedTurn>=0 || w.turns-crop.plantedTurn<4)
                    return reject("幼苗还太小，至少生长4步才能松土")
                next=s.copy(gardens=s.gardens+(index to crop.copy(tendedTurn=w.turns)))
                message="亲手给幼苗松土，继续探索，等待成熟"
            }
            "harvest_berries" -> {
                val crop=s.gardens[index] ?: return reject("菜圃还没有果实")
                if (!near() || s.structures[index]!="garden" ||
                    !IslandGarden.isReady(crop,w.turns))
                    return reject("需要先松土并等待野果成熟")
                val amount=IslandGarden.harvestYield(w.turns)
                next=s.copy(fruit=s.fruit+amount,gardens=s.gardens-index)
                message="收获成熟野果 +$amount，菜圃可再次播种"
            }
            "plant" -> {
                if (!canPlantAt(s, tx, ty)) return reject("在身边的空草地种树")
                if (w.wood < 1) return reject("种植树苗需要1木材")
                next = s.copy(world = w.setTile(tx, ty, 1).copy(wood = w.wood - 1))
                message = "种下一棵树，未来可以再次采集木材"
            }
            "remove" -> {
                if (!canRemoveAt(s, tx, ty))
                    return reject("只能拆除附近无人占用的自建设施")
                val structure = s.structures[index]
                val refundWood = when (structure) {
                    "workbench" -> 2
                    "campfire", "door", "bed", "lantern", "crystal_lantern" -> 1
                    "pet_house", "garden" -> 2
                    "bridge" -> 2
                    else -> if (cell == 3 || cell == 4) 1 else 0
                }
                val refundStone = if (structure == "workbench") 1 else 0
                next = s.copy(
                    world = w.setTile(tx, ty, if (structure == "bridge") 8 else 0)
                        .copy(wood = w.wood + refundWood, stone = w.stone + refundStone),
                    structures = s.structures - index,
                    gardens = s.gardens - index,
                    houseProject = s.houseProject?.let { project ->
                        if(project.ownerId=="player" && index in project.placed)
                            project.copy(placed=project.placed-index,phase="building")
                        else project
                    },
                )
                message = "拆除了${if (structure == null) "建筑" else actionName(structure)}，返还部分材料"
            }
            "craft_axe", "craft_pickaxe", "craft_sword", "craft_rod", "craft_charm" -> {
                val tool = action.removePrefix("craft_")
                if (tool in s.tools) return reject("已经制作过这件工具")
                val tableNear = s.structures.any { (location, kind) ->
                    kind == "workbench" && distance(w.x, w.y, location % w.width, location / w.width) <= 2
                }
                if (!tableNear) return reject("靠近工作台才能制作工具")
                val recipe = IslandWorkbenchRecipes.find(tool) ?: return reject("未知的工具配方")
                if (w.wood < recipe.wood || w.stone < recipe.stone || s.crystals < recipe.crystals)
                    return reject("需要木材${recipe.wood}、石料${recipe.stone}、晶石${recipe.crystals}")
                next = s.copy(world = w.copy(wood = w.wood - recipe.wood,
                    stone = w.stone - recipe.stone), tools = s.tools + tool,
                    crystals=s.crystals-recipe.crystals)
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
            "tame" -> {
                val animal=s.animals.firstOrNull { it.x==tx && it.y==ty && !it.befriended }
                    ?:return reject("这里没有等待结识的动物")
                if (!near()) return reject("靠近小动物再送食物")
                if (w.turns-animal.lastFedTurn<4)
                    return reject("${IslandWildlife.name(animal.species)}刚吃饱，陪它探索至少4步再来")
                if (!IslandWildlife.canFeed(s,animal))
                    return reject("结识${IslandWildlife.name(animal.species)}需要${IslandWildlife.feed(animal.species)}")
                val nextTrust=(animal.trust+1).coerceAtMost(IslandWildlife.requiredFeeds(animal.species))
                val friendsNow=nextTrust>=IslandWildlife.requiredFeeds(animal.species)
                next=s.copy(
                    fruit=s.fruit-if(animal.species=="hare")1 else 0,
                    fish=s.fish-when(animal.species){"duck"->1;"fox"->2;else->0},
                    animals=s.animals.map { if(it.id==animal.id)
                        it.copy(trust=nextTrust,lastFedTurn=w.turns,
                            befriended=friendsNow,
                            lastGiftTurn=if(friendsNow)w.turns else it.lastGiftTurn) else it },
                    discoveredSpecies=s.discoveredSpecies+animal.species,
                )
                message=if(friendsNow)
                    "✦ ${IslandWildlife.name(animal.species)}终于信任你了！它愿意跟随你回家"
                else "${IslandWildlife.name(animal.species)}慢慢放下戒心（${nextTrust}/${IslandWildlife.requiredFeeds(animal.species)}），过几步再喂它"
            }
            "care" -> {
                val animal=s.animals.firstOrNull { it.x==tx && it.y==ty && it.befriended }
                    ?:return reject("这里没有可互动的家园伙伴")
                if (!near()) return reject("靠近小动物再互动")
                if (!IslandWildlife.canGift(s,animal))
                    return reject("${IslandWildlife.name(animal.species)}还在寻找礼物，晚一点再来")
                val fruitGift=if(animal.species=="hare")1 else 0
                val fishGift=if(animal.species=="duck")1 else 0
                val stoneGift=if(animal.species=="fox")2 else 0
                next=s.copy(
                    fruit=s.fruit+fruitGift, fish=s.fish+fishGift,
                    world=w.copy(stone=w.stone+stoneGift),
                    animals=s.animals.map { if(it.id==animal.id)
                        it.copy(lastGiftTurn=w.turns) else it },
                )
                message="${IslandWildlife.name(animal.species)}带来礼物："+
                    when(animal.species){"hare"->"野果+1";"duck"->"小鱼+1";else->"石料+2"}
            }
            "fish" -> {
                if (!near() || cell != 8) return reject("靠近水边才能钓鱼")
                if ("rod" !in s.tools) return reject("先在工作台制作鱼竿")
                if (w.turns - s.lastFishTurn < 3) return reject("鱼儿还没回来，稍后再试")
                // Deterministic, state-driven outcome. A failed cast still advances the world.
                val caught = Random(w.seed xor (w.turns * 4271) xor index).nextInt(100) < 70
                next = s.copy(fish = s.fish + if (caught) 1 else 0, lastFishTurn = w.turns)
                message = if (caught) "钓起一条小鱼，已放进背包" else "鱼儿溜走了，换个时机再来"
            }
            "eat_fish" -> {
                if (s.fish < 1) return reject("背包没有鱼，先去水边钓")
                val fireNear = s.structures.any { (location, kind) ->
                    kind == "campfire" &&
                        distance(w.x, w.y, location % w.width, location / w.width) <= 2
                }
                if (!fireNear) return reject("到篝火附近才能烤鱼")
                if (w.hp >= 12 && s.hunger >= 10) return reject("生命与饱食度已满")
                next = s.copy(world = w.copy(hp = (w.hp + 5).coerceAtMost(12)),
                    fish = s.fish - 1, hunger = (s.hunger + 6).coerceAtMost(10))
                message = "吃下烤鱼，恢复生命和饱食度"
            }
            "eat" -> {
                if (s.fruit < 1) return reject("背包里没有野果，砍树时可能找到")
                if (w.hp >= 12 && s.hunger >= 10) return reject("生命与饱食度已满")
                next = s.copy(world = w.copy(hp = (w.hp + 3).coerceAtMost(12)),
                    fruit = s.fruit - 1, hunger = (s.hunger + 4).coerceAtMost(10))
                message = "吃下野果，恢复生命和饱食度"
            }
            "rest" -> {
                if (!islandCampNearby(s)) return reject("需要站在篝火或床铺旁边")
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
                    fruit = s.fruit + if (dead) 2 else 0,
                    defeatedMonsters = s.defeatedMonsters + if (dead) 1 else 0,
                )
                message = if (dead) "击退怪物，得到野果 +2" else "攻击怪物，造成${damage}点伤害"
            }
            else -> return reject("未知操作")
        }
        return advance(next, message, autonomousIds)
    }

    private fun advance(s: BlockIslandState, message: String,
        autonomousIds: Set<String>): BlockIslandState {
        var result = s.copy(world = s.world.copy(
            turns = s.world.turns + 1,
            notice = message,
            events = (s.world.events + message).takeLast(150),
        ))
        result = companionTurn(result, autonomousIds)
        result = IslandTravel.advance(result, autonomousIds)
        result = IslandHouseProjects.advance(result, autonomousIds)
        result = IslandGuardianEncounter.recordVictory(result)
        // Hunger creates a real renewable food loop; old saves start fully fed.
        if (result.world.turns % 12 == 0) {
            val fed = (result.hunger - 1).coerceAtLeast(0)
            val starving = fed == 0
            result = result.copy(hunger = fed, world = result.world.copy(
                hp = if (starving) (result.world.hp - 1).coerceAtLeast(0) else result.world.hp,
                notice = result.world.notice + if (starving) " · 饥饿损失1生命" else " · 饱食度-1",
            ))
        }
        result = monstersTurn(result)
        // One real survival milestone per completed night. A recent knockout voids it.
        if (result.world.turns > 0 && result.world.turns % 48 == 0 &&
            result.lastKnockoutTurn < result.world.turns - 18 &&
            result.world.hp > 0 && result.structures.any { (index, kind) ->
                kind in setOf("campfire", "bed") &&
                    distance(result.world.x, result.world.y,
                        index % result.world.width, index / result.world.width) <= 2
            }) {
            val thirdNight = result.nightsSurvived == 2
            val fruitReward = if (result.nightsSurvived < 3) 2 else 1
            val woodReward = if (thirdNight) 8 else 0
            val stoneReward = if (thirdNight) 6 else 1
            val news = if (thirdNight)
                "★ 第三夜守岛挑战完成！营地获得木材+8、石料+6、野果+2，可继续自由建造"
                else "守住了第${result.nightsSurvived + 1}夜 · 营地补给：野果+$fruitReward、石料+$stoneReward"
            result = result.copy(nightsSurvived = result.nightsSurvived + 1,
                fruit = result.fruit + fruitReward,
                world = result.world.copy(wood = result.world.wood + woodReward,
                    stone = result.world.stone + stoneReward,
                    notice = result.world.notice + " · " + news,
                    events = (result.world.events + news).takeLast(150)))
        }
        result = respawnMonsters(result)
        result = replenishResources(result)
        result = IslandGuardianEncounter.spawnIfReady(result)
        result = IslandWildlife.advance(result)
        val newSpecies=result.animals.filter { animal ->
            animal.species !in result.discoveredSpecies &&
                distance(result.world.x,result.world.y,animal.x,animal.y)<=2
        }.map { it.species }.toSet()
        if(newSpecies.isNotEmpty()) {
            val discovered=newSpecies.joinToString("、") { IslandWildlife.name(it) }
            result=result.copy(
                discoveredSpecies=result.discoveredSpecies+newSpecies,
                world=result.world.copy(
                    notice=result.world.notice+" · ✦ 初次发现"+discovered,
                    events=(result.world.events+"初次发现"+discovered).takeLast(150)))
        }
        return IslandChallengeBoard.awardCompleted(result)
    }

    private fun companionTurn(s: BlockIslandState, autonomousIds: Set<String>): BlockIslandState {
        var world = s.world
        val updates = mutableListOf<String>()
        val friends = world.friends.map { friend ->
            // A model-controlled player acts after the actual user turn; do not secretly
            // override that character with the old follow/gather/guard autopilot.
            if (friend.id in autonomousIds) return@map friend
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
                // The guardian's telegraphed third-turn strike does real damage,
                // not a random invisible critical hit.
                val strike=if(mob.id==IslandGuardianEncounter.id && world.turns%3==0)2 else 1
                if (target.first == world.x && target.second == world.y) playerHurt+=strike
                else world.friends.firstOrNull { it.x == target.first && it.y == target.second }?.let {
                    hurtFriends[it.id] = (hurtFriends[it.id] ?: 0) + strike
                }
                return@map mob
            }
            if (distance(mob.x, mob.y, target.first, target.second) > 5) return@map mob
            val next = nextStep(s, mob.x, mob.y, target.first, target.second)
            if (next == null || next.first == world.x && next.second == world.y ||
                world.friends.any { it.hp > 0 && it.x == next.first && it.y == next.second }) mob
            else mob.copy(x = next.first, y = next.second)
        }
        val protector=world.friends.firstOrNull { it.id==s.guardedBy && it.hp>0 &&
            distance(it.x,it.y,world.x,world.y)<=2 }
        val blocked=if(playerHurt>0 && protector!=null)1 else 0
        if(blocked>0)hurtFriends[protector!!.id]=(hurtFriends[protector.id]?:0)+1
        val lightGuard = if (playerHurt>blocked && IslandProgression.crystalLampProtects(s)) 1 else 0
        val realHurt=(playerHurt-blocked-lightGuard).coerceAtLeast(0)
        val hp = (world.hp - realHurt).coerceAtLeast(0)
        val allies = world.friends.map { it.copy(hp = (it.hp - (hurtFriends[it.id] ?: 0)).coerceAtLeast(0)) }
        val logs = mutableListOf<String>()
        if(blocked>0)logs += "队友"+protector!!.id+"挡住1点伤害，自己承受1点"
        if(lightGuard>0)logs += "护夜晶灯挡下1点夜间伤害"
        if(realHurt>0)logs += "怪物攻击，生命 -$realHurt"
        if (hurtFriends.isNotEmpty()) logs += "有队友受伤了"
        var next = s.copy(guardedBy=if(blocked>0)"" else s.guardedBy,
            lastKnockoutTurn = if (hp == 0) world.turns else s.lastKnockoutTurn,
            world = world.copy(hp = hp, mobs = mobs, friends = allies,
            notice = world.notice + if (logs.isNotEmpty()) " · " + logs.joinToString() else "",
            events = (world.events + logs).takeLast(150)))
        if (hp == 0) {
            if("charm" in next.tools) {
                val saved="晶石护符碎裂，替你挡下致命一击，生命恢复到3"
                next=next.copy(tools=next.tools-"charm",lastKnockoutTurn=s.lastKnockoutTurn,
                    world=next.world.copy(hp=3,notice=saved,
                        events=(next.world.events+saved).takeLast(150)))
            } else {
                // Only portable resources are dropped; buildings, skills and
                // permanent crafted tools remain. Recovering the pack is a real
                // world interaction and cannot be repeated to mint resources.
                val index=world.index(world.x,world.y)
                val lost=IslandLostPack(world.wood/2,world.stone/2,s.fruit/2,s.crystals/2)
                val existing=next.lostPacks[index]
                val packs=if(lost.isEmpty()) next.lostPacks else
                    next.lostPacks+(index to (existing?.merge(lost) ?: lost))
                val note=if(lost.isEmpty())
                    "生命归零！返回营地，生命恢复至6；本次没有散落物资"
                else "生命归零！返回营地，失去一半随身木石、野果、晶石；原地留有可找回的背包"
                next=next.copy(lostPacks=packs,fruit=s.fruit-lost.fruit,
                    crystals=s.crystals-lost.crystals,
                    world=next.world.copy(x=4.coerceAtMost(world.width-2),
                        y=5.coerceAtMost(world.height-2),hp=6,
                        wood=world.wood-lost.wood,stone=world.stone-lost.stone,
                        notice=note,events=(next.world.events+note).takeLast(150)))
            }
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
                !IslandSupplyCaches.available(s, x, y) &&
                distance(x, y, w.x, w.y) > 5 &&
                w.friends.none { it.x == x && it.y == y } &&
                w.mobs.none { it.hp > 0 && it.x == x && it.y == y } &&
                s.animals.none { it.x == x && it.y == y }
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
        // The same occupancy contract applies to the player, friends and monsters.
        // The mover's own starting tile is exempt; everyone else's tile is blocked,
        // including downed friends so the rescuer stops adjacent instead of overlapping.
        fun occupiedByOther(x: Int, y: Int): Boolean {
            if (x == fromX && y == fromY) return false
            return (w.x == x && w.y == y) ||
                w.friends.any { it.x == x && it.y == y } ||
                w.mobs.any { it.hp > 0 && it.x == x && it.y == y } ||
                s.animals.any { it.x==x && it.y==y }
        }
        val targetOpen = isWalkable(s, toX, toY) && !occupiedByOther(toX, toY)
        val ends = if (targetOpen) setOf(w.index(toX, toY)) else
            neighbors(toX, toY).filter { (x, y) ->
                isWalkable(s, x, y) && !occupiedByOther(x, y)
            }.map { (x, y) -> w.index(x, y) }.toSet()
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
                if (seen[next] != -1 || occupiedByOther(x, y)) return@forEach
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
        "axe" -> "木斧"; "pickaxe" -> "石镐"; "sword" -> "石剑"; "rod" -> "鱼竿"; "charm" -> "晶石护符"
        "wall" -> "木墙"; "floor" -> "木地板"; "door" -> "木门"
        "workbench" -> "工作台"; "campfire" -> "篝火"; "bed" -> "木床"; "bridge" -> "木桥"
        "lantern" -> "夜灯"; "crystal_lantern" -> "护夜晶灯"; "pet_house" -> "宠物小屋"
        else -> action
    }
}
