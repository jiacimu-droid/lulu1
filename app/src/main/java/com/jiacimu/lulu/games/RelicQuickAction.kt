package com.jiacimu.lulu.games

/** Visible tile suggestions only. The existing rules and persistent ledger decide outcomes. */
internal data class RelicQuickAction(
    val action: String,
    val label: String,
    val hint: String,
    val x: Int = 0,
    val y: Int = 0,
    val id: String = "",
)

/** Deterministic target for the combat key and the primary action.
 * The selected enemy wins when attackable; otherwise a hittable nearby threat is used.
 * This never spends a turn or attacks automatically.
 */
internal fun relicAttackTarget(run: RelicRun, selected: Pair<Int, Int>): RelicMob? {
    if (run.phase !in setOf(RelicPhase.EXPLORE, RelicPhase.EXTRACT)) return null
    val chosen = run.mobs.firstOrNull {
        it.hp > 0 && it.x == selected.first && it.y == selected.second &&
            RelicArsenal.canHit(run, it.x, it.y)
    }
    if (chosen != null) return chosen
    return run.mobs.filter { it.hp > 0 && RelicArsenal.canHit(run, it.x, it.y) }
        .minWithOrNull(compareBy<RelicMob> {
            RelicEscapeRules.distance(run.hero.x, run.hero.y, it.x, it.y)
        }.thenBy { it.id })
}

/** Explain a disabled attack key without inventing a successful hit. */
internal fun relicAttackHint(run: RelicRun, selected: Pair<Int, Int>): String {
    if (run.phase == RelicPhase.DOWNED) return "已倒地，无法攻击；等待伙伴救援"
    if (relicAttackTarget(run, selected) != null) return "敌人进入射程，点攻击即可出手"
    val mob = run.mobs.firstOrNull {
        it.hp > 0 && it.x == selected.first && it.y == selected.second
    }
    return when {
        mob != null && !run.visible(mob.x, mob.y) -> "目标在迷雾中，需要先探索"
        mob != null -> "当前"+RelicArsenal.label(run.equipped)+"："+RelicArsenal.description(run.equipped)+"；靠近或换武器"
        else -> "没有进入武器射程的怪物；短剑只能打上下左右相邻1格"
    }
}

/** Selecting a tile never spends a turn; the player must explicitly tap the action button. */
internal fun relicQuickAction(run: RelicRun, target: Pair<Int, Int>): RelicQuickAction? {
    val (x, y) = target
    val hero = run.hero
    if (run.phase == RelicPhase.DOWNED)
        return RelicQuickAction("wait", "等待伙伴救援", "倒地期间无法移动，等待伙伴靠近")
    if (run.phase !in setOf(RelicPhase.EXPLORE, RelicPhase.EXTRACT)) return null
    val near = RelicEscapeRules.distance(hero.x, hero.y, x, y) <= 1
    val mob = run.mobs.firstOrNull { it.hp > 0 && it.x == x && it.y == y }
    if (mob != null && RelicArsenal.canHit(run,x,y))
        return RelicQuickAction("attack", RelicArsenal.label(run.equipped)+"攻击 · "+mob.hp+"生命",
            RelicArsenal.monsterName(mob.kind)+" · "+RelicArsenal.description(run.equipped)+
                if(run.equipped=="bow")" · 剩余"+run.bolts+"箭" else "", x, y)
    if (run.visible(x, y) && near) {
        val ally = run.allies.firstOrNull { it.hp <= 0 && it.x == x && it.y == y }
        if (ally != null && hero.kits > 0) return RelicQuickAction("revive", "使用药剂救援队友",
            "消耗一瓶药剂救起附近倒地伙伴", id = ally.id)
        val chest = run.chests.firstOrNull { it.x == x && it.y == y && !it.opened }
        if (chest != null) return RelicQuickAction("open",
            "撬开"+RelicQuality.at(chest.visibleGrade).label+"宝箱 "+
                (run.chestProgress[chest.id] ?: 0)+"/"+RelicEscapeRules.openSteps(chest),
            "需要多次互动，每次世界都会行动；成功撤离才能永久收藏", x, y)
        val site = run.sites.firstOrNull { it.x == x && it.y == y && !it.spent }
        if (site?.kind == "trap" && run.tools > 0) return RelicQuickAction("disarm",
            "解除附近机关", "消耗一份拆解工具，避免触发陷阱", x, y)
        if (site?.kind == "altar") return RelicQuickAction("invoke", "唤醒古老祭坛",
            "可获取珍宝，也会惊动守卫", x, y)
        if(site?.kind=="alarm")return RelicQuickAction("silence",
            "拆除遗迹钟楼"+if(run.tools>0)" · 工具-1" else " · 缺少工具",
            "拆除后开箱不再触发追兵；保留工具也可冒险继续搜宝", x, y)
        if (site?.kind == "supply") return RelicQuickAction("resupply", "搜寻战地补给",
            "拾取药剂和弩箭，储备有限", x, y)
        if (site?.kind == "fountain" && hero.hp<10) return RelicQuickAction("fountain", "汲取恢复灵泉",
            "回复生命，灵泉仅能使用一次", x, y)
        val drop = run.drops.firstOrNull {
            it.x == x && it.y == y && hero.load + it.loot.weight <= RelicEscapeRules.CAPACITY
        }
        if (drop != null) return RelicQuickAction("pickup", "拾取 ${drop.loot.name}",
            "拾取进背包，不会立即增加永久仓库", id = drop.loot.id)
        if (x == run.exitX && y == run.exitY && run.phase == RelicPhase.EXPLORE)
            return RelicQuickAction("extract", "启动撤离 · 守住三回合",
                "守在出口附近，经过三回合才真正带走宝物")
    }
    // Initial selection is the hero; still allow a touching monster to be attacked.
    if (target == (hero.x to hero.y)) {
        val threat = relicAttackTarget(run, target)
        if (threat != null) return RelicQuickAction(
            "attack", RelicArsenal.label(run.equipped)+"攻击附近怪物 · "+threat.hp+"生命",
            RelicArsenal.monsterName(threat.kind)+" · "+RelicArsenal.description(run.equipped),
            threat.x, threat.y,
        )
    }
    if (run.phase == RelicPhase.EXTRACT)
        return RelicQuickAction("wait", "原地守住撤离门", "留在出口附近，推进撤离倒数")
    if (target == (hero.x to hero.y)) {
        if (hero.kits > 0 && hero.hp < 10)
            return RelicQuickAction("heal", "使用一瓶药剂", "回复生命，敌人也会行动")
        return null
    }
    if (!run.visible(x, y) || !run.walk(x, y)) return null
    val step = RelicEscapeRules.path(run, hero.x, hero.y, x, y) ?: return null
    if (!run.visible(step.first, step.second) ||
        run.mobs.any { it.hp > 0 && it.x == step.first && it.y == step.second }) return null
    return RelicQuickAction("move", "朝目标前进一步",
        "只移动一格，之后可以重新选择行动", step.first, step.second)
}

/**
 * A held directional gesture only requests movement. Chests and landmarks
 * require an explicit centre-button or HUD interaction, never auto-prying.
 * The existing authoritative movement rules still decide collisions.
 */
internal fun relicDirectionAction(run:RelicRun,dx:Int,dy:Int):RelicQuickAction? {
    if(run.phase !in setOf(RelicPhase.EXPLORE,RelicPhase.EXTRACT) ||
        kotlin.math.abs(dx)+kotlin.math.abs(dy)!=1) return null
    return RelicQuickAction("move","移动","只移动，不自动开箱或触发机关",
        run.hero.x+dx,run.hero.y+dy)
}

/**
 * Nearby-object interaction for the D-pad. Direction wins, then the other
 * adjacent cells. Combat remains explicit so holding movement cannot spam it.
 */
internal fun relicNearbyAction(run: RelicRun, facing: Pair<Int, Int>): RelicQuickAction? {
    if (run.phase !in setOf(RelicPhase.EXPLORE, RelicPhase.EXTRACT)) return null
    val h = run.hero
    val directions = listOf(facing, 0 to -1, 1 to 0, 0 to 1, -1 to 0)
        .filter { (dx, dy) -> kotlin.math.abs(dx) + kotlin.math.abs(dy) == 1 }.distinct()
    val interactions = setOf("open", "pickup", "resupply", "fountain",
        "disarm", "invoke", "silence", "revive", "extract")
    for ((dx, dy) in directions) {
        val x = h.x + dx
        val y = h.y + dy
        if (!run.visible(x, y)) continue
        val action = relicQuickAction(run, x to y)
        if (action?.action in interactions) return action
    }
    // Movement can stand on a chest or landmark tile; the centre action
    // must remain usable from the same tile, not just the four neighbors.
    return relicQuickAction(run, h.x to h.y)?.takeIf { it.action in interactions }
}
