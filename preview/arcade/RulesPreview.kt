package com.jiacimu.lulu.games

import org.json.JSONArray
import org.json.JSONObject
import java.util.UUID

private const val PARTNER = "试玩队友"
private val capacity = 24

private sealed interface GameSession {
    data class Island(var state: BlockIslandState) : GameSession
    data class Relic(var run: RelicRun) : GameSession
}

private val sessions = LinkedHashMap<String, GameSession>(capacity, .75f, true)

private fun button(action: String, label: String, x: Int = 0, y: Int = 0, id: String = "",
    enabled: Boolean = true, hint: String = "", section: String = "main") =
    JSONObject().put("action", action).put("label", label).put("x", x).put("y", y)
        .put("id", id).put("enabled", enabled).put("hint", hint).put("section", section)

private fun options(actorOptions: List<IslandActorOption>) = actorOptions.filter { it.kind != "none" }
    .map { button("partner", it.name, id = it.id, section = "partner") }

private fun islandView(s: BlockIslandState, selected: Pair<Int, Int>?, facing: Pair<Int, Int>,
    build: String): JSONObject {
    val w = s.world
    val (x, y) = selected ?: (w.x to w.y)
    val target = selected?.let { islandTargetAction(s, it) }
    val nearby = islandNearbyAction(s, facing)
    val active = when {
        build.isNotEmpty() -> IslandBuildingControls.preview(s, build, x, y)?.let {
            button(it.action, "放置", x, y, enabled = it.allowed, hint = it.message)
        }
        target != null && target.action != "move" && target.action != "none" ->
            button(target.action, target.label, x, y, enabled = target.enabled)
        target == null && nearby != null -> button(nearby.action, nearby.label, nearby.x, nearby.y)
        else -> null
    }
    val actions = mutableListOf<JSONObject>()
    if (active != null) actions += active
    for (recipe in IslandBuildingControls.recipes)
        actions += button("choose_build", recipe.name, id = recipe.action, hint = recipe.cost, section = "build")
    for (recipe in IslandWorkbenchRecipes.all)
        actions += button(recipe.action, recipe.title, enabled = IslandWorkbenchRecipes.canCraft(s, recipe),
            hint = recipe.description, section = "bench")
    actions += button("eat", "吃野果", enabled = s.fruit > 0, section = "bench")
    actions += options(IslandActorPlay.options(s, PARTNER,
        w.events.none { it.startsWith(IslandActorPlay.eventPrefix(PARTNER, w.turns)) }))
    if (GameCoopPacing.islandHasPlan(s, true))
        actions += button("companion_tick", "推进队友计划一步", section = "partner")
    return JSONObject(s.toJson()).apply {
        put("kind", "island")
        put("objective", IslandMilestones.next(s))
        put("notice", w.notice)
        put("actorEvent", w.events.lastOrNull { it.startsWith("队友[") } ?: "")
        put("crystalTiles", JSONArray(w.tiles.indices.filter {
            w.tiles[it] == 2 && IslandProgression.crystalVein(w.seed, it)
        }))
        put("actions", JSONArray(actions))
    }
}

private fun relicView(s: RelicRun, selected: Pair<Int, Int>?, facing: Pair<Int, Int>): JSONObject {
    val hero = s.hero
    val chosen = if (selected != null) relicQuickAction(s, selected) else
        relicNearbyAction(s, facing) ?: relicQuickAction(s, hero.x to hero.y)
    val actions = mutableListOf<JSONObject>()
    if (chosen != null) actions += button(chosen.action, chosen.label, chosen.x, chosen.y, chosen.id,
        hint = chosen.hint)
    else if (selected == null && s.profession == RelicProfession.SCOUT && RelicCombatSkills.professionAvailable(s))
        actions += button("profession_skill", "远眺", hint = "探明身边五格迷雾")
    if (s.phase in listOf(RelicPhase.EXPLORE, RelicPhase.EXTRACT, RelicPhase.DOWNED)) {
        if (chosen == null || chosen.action != "wait")
            actions += button("wait", if (s.phase == RelicPhase.DOWNED) "等待救援" else "观察一回合",
                section = "combat")
        val enemy = RelicCombatSkills.attackTarget(s, facing)
        if (enemy != null)
            actions += button("attack", "攻击", enemy.x, enemy.y, section = "combat")
        if (s.phase != RelicPhase.DOWNED) {
            actions += button("defend", "格挡", hint = "本回合抵挡一次守卫伤害", section = "combat")
            actions += button("weapon_skill", RelicCombatSkills.weaponSkillName(s), facing.first, facing.second,
                enabled = RelicCombatSkills.canWeaponSkill(s, facing),
                hint = RelicCombatSkills.weaponSkillHint(s, facing), section = "combat")
            actions += button("profession_skill", s.profession.skill,
                enabled = RelicCombatSkills.professionAvailable(s),
                hint = s.profession.detail + " · 剩余" + s.professionCharges + "次", section = "combat")
            actions += button("heal", "用药", enabled = hero.kits > 0 && hero.hp < 10, section = "combat")
            for (weapon in s.weapons)
                actions += button("equip", RelicArsenal.label(weapon), id = weapon,
                    enabled = weapon != s.equipped, hint = RelicArsenal.description(weapon), section = "gear")
        }
        actions += options(RelicActorPlay.options(s, PARTNER).takeIf { s.allies.any { it.id == PARTNER } }
            ?: emptyList())
        if (GameCoopPacing.relicTickActors(s).isNotEmpty())
            actions += button("companion_tick", "推进队友计划一步", section = "partner")
    }
    val guide = RelicObjectiveGuide.nextVisible(s)
    val route = selected?.let { RelicObjectiveGuide.selectedVisible(s, it.first, it.second) } ?: guide
    return s.json().apply {
        put("kind", "relic")
        put("tiles", JSONArray(s.tiles.mapIndexed { i, cell -> if (i in s.seen) cell else -1 }))
        put("chests", JSONArray(s.chests.filter { s.visible(it.x, it.y) }.map { it.json() }))
        put("mobs", JSONArray(s.mobs.filter { it.hp > 0 && s.visible(it.x, it.y) }.map { it.json() }))
        put("sites", JSONArray(s.sites.filter { s.visible(it.x, it.y) }.map { it.json() }))
        put("drops", JSONArray(s.drops.filter { s.visible(it.x, it.y) }.map { it.json() }))
        if (!s.visible(s.exitX, s.exitY)) {
            put("exitX", -1)
            put("exitY", -1)
        }
        put("route", JSONArray(route?.let { RelicObjectiveGuide.knownRouteTiles(s, it) }.orEmpty()
            .map { JSONArray(listOf(it.first, it.second)) }))
        put("objective", RelicObjectiveGuide.prompt(s))
        put("capacity", RelicEscapeRules.heroCapacity(s))
        put("load", hero.load)
        put("carried", s.carried.size)
        put("value", s.value)
        put("actorEvent", s.events.lastOrNull { it.who == PARTNER && it.action != "speech" }?.text ?: "")
        put("actions", JSONArray(actions))
    }
}

private fun request(q: JSONObject): JSONObject {
    val verb = q.optString("verb")
    val kind = q.optString("kind")
    val seed = q.optInt("seed", 4317).coerceIn(1, Int.MAX_VALUE)
    val companions = if (q.optBoolean("partner")) listOf(PARTNER) else emptyList()
    val sessionId = if (verb == "start") UUID.randomUUID().toString() else q.optString("session")
    if (verb == "start") {
        sessions[sessionId] = when (kind) {
            "island" -> GameSession.Island(BlockIslandRules.newWorld(seed, companions))
            "relic" -> {
                val difficulty = RelicDifficulty.entries.firstOrNull { it.name == q.optString("difficulty") }
                    ?: RelicDifficulty.NORMAL
                val weapon = q.optString("weapon", "blade").takeIf { it in setOf("blade", "spear", "bow") } ?: "blade"
                GameSession.Relic(RelicEscapeWorld.create(seed, 1, companions, 2, 1, difficulty = difficulty,
                    purchasedWeapons = setOf(weapon), startingWeapon = weapon).copy(
                    profession = RelicProfession.entries.firstOrNull { it.name == q.optString("profession") }
                        ?: RelicProfession.SCOUT))
            }
            else -> error("未知游戏")
        }
        if (sessions.size > capacity) sessions.remove(sessions.keys.first())
    }
    val facing = q.optInt("dx") to q.optInt("dy")
    val selected = if (q.has("x") && q.has("y")) q.getInt("x") to q.getInt("y") else null
    val (x, y) = selected ?: (0 to 0)
    val build = q.optString("build")
    val action = q.optString("action")
    val id = q.optString("id")
    val view = when (val game = sessions[sessionId] ?: error("试玩已结束，请从大厅重新出发。")) {
        is GameSession.Island -> {
            val before = game.state
            val w = before.world
            val actors = w.friends.map { it.id }.toSet()
            val target = selected ?: (w.x to w.y)
            var display = selected
            if (verb == "act") {
                game.state = when {
                    action == "move" -> IslandManualMovement.destination(before, facing.first, facing.second)?.let {
                        BlockIslandRules.act(before, "move", it.first, it.second, actors)
                    } ?: before.copy(world = w.copy(notice = IslandManualMovement.blockedHint(
                        before, facing.first, facing.second) ?: "这一步走不过去"))
                    action == "partner" -> IslandActorPlay.apply(before, PARTNER, w.turns, IslandActorChoice(id, ""))
                        ?: error("队友现在无法执行这个动作。")
                    action == "companion_tick" && actors.isNotEmpty() ->
                        BlockIslandRules.act(before, action, autonomousIds = actors)
                    action == "eat" || IslandWorkbenchRecipes.all.any { it.action == action } ->
                        BlockIslandRules.act(before, action, w.x, w.y, actors)
                    action in setOf("gather", "fight", "revive", "salvage", "recover_pack", "fish",
                        "tame", "care", "house_step", "rest", "sow_berries", "tend_berries", "harvest_berries") ||
                        IslandBuildingControls.recipes.any { it.action == action } ->
                        BlockIslandRules.act(before, action, target.first, target.second, actors)
                    else -> error("未知操作")
                }
                if (action == "move") display = null
            }
            val placed = verb == "act" && action == build && game.state.world.turns > w.turns
            islandView(game.state, display, facing, if (placed) "" else build)
        }
        is GameSession.Relic -> {
            val before = game.run
            val allies = before.allies.map { it.id }.toSet()
            var display = selected
            var loot: JSONObject? = null
            if (verb == "act") {
                game.run = when (action) {
                    "move" -> {
                        display = null
                        relicDirectionAction(before, facing.first, facing.second)?.let {
                            RelicEscapeRules.act(before, it.action, it.x, it.y, autonomousIds = allies)
                        } ?: before
                    }
                    "partner" -> RelicActorPlay.apply(before, PARTNER, IslandActorChoice(id, ""))
                        ?: error("队友现在无法执行这个动作。")
                    "open" -> {
                        val effective = RelicChestPresentation.playerAction(before, action, x, y)
                        val result = RelicEscapeRules.act(before, effective, x, y, autonomousIds = allies)
                        val box = before.chests.firstOrNull { !it.opened && it.x == x && it.y == y }
                        box?.let { RelicChestPresentation.committed(before, result, it.id) }?.let {
                            loot = it.loot.json().put("inBag", it.inBag)
                        }
                        result
                    }
                    "attack", "weapon_skill", "sneak", "disarm", "invoke", "silence", "resupply", "fountain",
                    "observe_lens", "unseal" -> RelicEscapeRules.act(before, action, x, y, id, allies)
                    "companion_tick" -> RelicEscapeRules.act(before, action,
                        autonomousIds = GameCoopPacing.relicTickActors(before))
                    "wait", "defend", "heal", "extract", "equip", "revive", "pickup", "profession_skill" ->
                        RelicEscapeRules.act(before, action, lootId = id, autonomousIds = allies)
                    else -> error("未知操作")
                }
            }
            relicView(game.run, display, facing).apply { if (loot != null) put("receipt", loot) }
        }
    }
    return view.put("session", sessionId)
}

fun main() {
    System.err.println("Lulu game rules loaded")
    generateSequence(::readLine).forEach { line ->
        val result = runCatching { request(JSONObject(line)) }
            .getOrElse { JSONObject().put("error", it.message ?: "试玩状态无法读取") }
        println(result.toString())
    }
}
