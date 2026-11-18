package com.jiacimu.lulu.games

import org.json.JSONArray
import org.json.JSONObject

private fun button(action: String, label: String, x: Int = 0, y: Int = 0, id: String = "",
    enabled: Boolean = true, hint: String = "", section: String = "main") =
    JSONObject().put("action", action).put("label", label).put("x", x).put("y", y)
        .put("id", id).put("enabled", enabled).put("hint", hint).put("section", section)

private fun options(actorOptions: List<IslandActorOption>, actor: String) =
    actorOptions.filter { it.kind != "none" }
        .map { button("partner", it.name, id = it.id, section = "partner").put("actor",actor) }

internal fun arcadeIslandView(s: BlockIslandState, selected: Pair<Int, Int>?, facing: Pair<Int, Int>,
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
    for (friend in w.friends) actions += options(IslandActorPlay.options(s, friend.id,
        w.events.none { it.startsWith(IslandActorPlay.eventPrefix(friend.id, w.turns)) }),friend.id)
    if (GameCoopPacing.islandHasPlan(s, true))
        actions += button("companion_tick", "推进队友计划一步", section = "partner")
    return JSONObject(s.toJson()).apply {
        put("kind", "island")
        put("swimming", BlockIslandRules.isSwimmable(s, w.x, w.y))
        put("objective", IslandMilestones.next(s))
        put("notice", w.notice)
        put("actorEvent", w.events.lastOrNull { it.startsWith("队友[") } ?: "")
        put("crystalTiles", JSONArray(w.tiles.indices.filter {
            w.tiles[it] == 2 && IslandProgression.crystalVein(w.seed, it)
        }))
        put("actions", JSONArray(actions))
        put("nextHouse", IslandHouseProjects.nextPlayerPart(s)?.let { (index,_) ->
            JSONArray(listOf(index%w.width,index/w.width))
        } ?: JSONObject.NULL)
    }
}

internal fun arcadeRelicView(s: RelicRun, selected: Pair<Int, Int>?, facing: Pair<Int, Int>): JSONObject {
    val hero = s.hero
    val chosen = if (selected != null) relicQuickAction(s, selected) else
        relicNearbyAction(s, facing) ?: relicQuickAction(s, hero.x to hero.y)
    val actions = mutableListOf<JSONObject>()
    if (chosen != null) actions += button(chosen.action, when (chosen.action) {
        "extract" -> "撤离 · ${RelicEscapeRules.extractionSteps}秒"
        "open" -> "检查宝箱"
        else -> chosen.label
    }, chosen.x, chosen.y, chosen.id, hint = when (chosen.action) {
        "extract" -> "点一次，留在门边等倒计时。"
        "open" -> "靠近后按住撬锁，留意守卫。"
        else -> chosen.hint
    })
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
        for (ally in s.allies) actions += options(RelicActorPlay.options(s,ally.id),ally.id)
        if (GameCoopPacing.relicTickActors(s).isNotEmpty())
            actions += button("companion_tick", "推进队友计划一步", section = "partner")
    }
    val guide = RelicObjectiveGuide.nextVisible(s)
    val route = selected?.let { RelicObjectiveGuide.selectedVisible(s, it.first, it.second) } ?: guide
    return s.json().apply {
        put("kind", "relic")
        put("tiles", JSONArray(s.tiles.mapIndexed { i, cell -> if (i in s.seen) cell else -1 }))
        put("chests", JSONArray(s.chests.filter { s.visible(it.x, it.y) }.map {
            it.json().put("openSteps", RelicEscapeRules.openSteps(it))
        }))
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
        put("extractionSteps", RelicEscapeRules.extractionSteps)
        put("capacity", RelicEscapeRules.heroCapacity(s))
        put("load", hero.load)
        put("carried", s.carried.size)
        put("value", s.value)
        put("actorEvent", s.events.lastOrNull { event -> s.allies.any { it.id == event.who } && event.action != "speech" }?.text ?: "")
        put("actions", JSONArray(actions))
    }
}

private fun interruptedChest(before: RelicRun, after: RelicRun, box: RelicChest): RelicRun {
    val ceiling=before.mobs.maxOfOrNull { it.id } ?: -1
    return after.copy(
        hero=after.hero.copy(bag=before.hero.bag),chests=before.chests,
        drops=before.drops,weapons=before.weapons,bolts=before.bolts,
        chestProgress=after.chestProgress-box.id,
        mobs=after.mobs.filter { it.id<=ceiling },
        events=after.events.filterNot { it.turn==before.turns && it.action in setOf("open","pry","alarm") },
    ).log("player","chest_interrupted","受到攻击，开箱中断。宝箱仍然关闭。")
}

internal data class ArcadeInput(
    val selected: Pair<Int, Int>?, val facing: Pair<Int, Int>, val build: String,
    val action: String, val item: String, val actor: String,
    val timedExtraction: Boolean = false,
) {
    companion object {
        fun read(q: JSONObject, defaultActor: String = "") = ArcadeInput(
            if (q.has("x") && q.has("y")) q.getInt("x") to q.getInt("y") else null,
            q.optInt("dx") to q.optInt("dy"), q.optString("build"), q.optString("action"),
            q.optString("id"), q.optString("actor", defaultActor), q.optBoolean("timedExtraction"),
        )
    }
}

internal fun arcadeIslandAct(before: BlockIslandState, input: ArcadeInput, actors: Set<String>): BlockIslandState {
    val (selected, facing, _, action, id, actor) = input
    val w = before.world
    val target = selected ?: (w.x to w.y)
    return when {
        action == "move" -> IslandManualMovement.destination(before, facing.first, facing.second, allowSwimming = true)?.let {
            val movement = if (BlockIslandRules.isSwimmable(before, it.first, it.second)) "swim" else "move"
            BlockIslandRules.act(before, movement, it.first, it.second, actors)
        } ?: before.copy(world = w.copy(notice = IslandManualMovement.blockedHint(
            before, facing.first, facing.second) ?: "这一步走不过去"))
        action == "partner" -> IslandActorPlay.apply(before, actor, w.turns, IslandActorChoice(id, ""))
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
}

internal data class ArcadeRelicStep(val run: RelicRun, val loot: JSONObject?, val interrupted: Boolean) {
    fun annotate(view: JSONObject): JSONObject = view.apply {
        if (loot != null) put("receipt", loot)
        if (interrupted) put("chestInterrupted", true)
    }
}

internal fun arcadeRelicAct(before: RelicRun, input: ArcadeInput, allies: Set<String>): ArcadeRelicStep {
    val (selected, facing, _, action, id, actor) = input
    val (x, y) = selected ?: (before.hero.x to before.hero.y)
    fun play(action: String, x: Int = before.hero.x, y: Int = before.hero.y, item: String = "") =
        RelicEscapeRules.act(before, action, x, y, item, allies,
            advanceExtraction = !input.timedExtraction || input.action == "extract_tick")
    var loot: JSONObject? = null
    var interrupted = false
    val result = when (action) {
        "move" -> {
            relicDirectionAction(before, facing.first, facing.second)?.let {
                play(it.action, it.x, it.y)
            } ?: before
        }
        "partner" -> RelicActorPlay.apply(before, actor, IslandActorChoice(id, ""))
            ?: error("队友现在无法执行这个动作。")
        "open", "cinematic_step" -> {
            val effective = RelicChestPresentation.playerAction(before, "open", x, y)
            val result = play(effective, x, y)
            val box = before.chests.firstOrNull { !it.opened && it.x == x && it.y == y }
            if (box != null && action == "cinematic_step" && result.turns > before.turns &&
                result.events.any { it.turn > before.turns && it.who == "player" && it.action == "hurt" }) {
                interrupted = true
                interruptedChest(before,result,box)
            } else {
                box?.let { RelicChestPresentation.committed(before, result, it.id) }?.let {
                    loot = it.loot.json().put("inBag", it.inBag)
                }
                result
            }
        }
        "cancel_chest" -> {
            val box = before.chests.firstOrNull { !it.opened && it.x == x && it.y == y }
            if (box != null) before.copy(chestProgress=before.chestProgress-box.id)
                .log("player","chest_cancelled","已松开箱盖，可以立刻移动。") else before
        }
        "attack", "weapon_skill", "sneak", "disarm", "invoke", "silence", "resupply", "fountain",
        "observe_lens", "unseal" -> play(action, x, y, id)
        "companion_tick" -> RelicEscapeRules.act(before, action,
            autonomousIds = GameCoopPacing.relicTickActors(before))
        "extract_tick" -> if (before.phase == RelicPhase.EXTRACT) play("wait") else before
        "wait", "defend", "heal", "extract", "equip", "revive", "pickup", "profession_skill" -> play(action, item = id)
        else -> error("未知操作")
    }
    return ArcadeRelicStep(result, loot, interrupted)
}
