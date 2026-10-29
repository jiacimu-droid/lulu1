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

/** Selecting a tile never spends a turn; the player must explicitly tap the action button. */
internal fun relicQuickAction(run: RelicRun, target: Pair<Int, Int>): RelicQuickAction? {
    val (x, y) = target
    val hero = run.hero
    if (run.phase == RelicPhase.DOWNED)
        return RelicQuickAction("wait", "等待伙伴救援", "倒地期间无法移动，等待伙伴靠近")
    if (run.phase !in setOf(RelicPhase.EXPLORE, RelicPhase.EXTRACT)) return null
    val near = RelicEscapeRules.distance(hero.x, hero.y, x, y) <= 1
    if (run.visible(x, y) && near) {
        val mob = run.mobs.firstOrNull { it.hp > 0 && it.x == x && it.y == y }
        if (mob != null) return RelicQuickAction("attack", "攻击怪物 · 剩余 ${mob.hp} 生命",
            "推进一回合，敌人也可能反击", x, y)
        val ally = run.allies.firstOrNull { it.hp <= 0 && it.x == x && it.y == y }
        if (ally != null && hero.kits > 0) return RelicQuickAction("revive", "使用药剂救援队友",
            "消耗一瓶药剂救起附近倒地伙伴", id = ally.id)
        val chest = run.chests.firstOrNull { it.x == x && it.y == y && !it.opened }
        if (chest != null) return RelicQuickAction("open", "开启${chest.tier.label}宝箱",
            "先放进背包，成功撤离后才能入库", x, y)
        val site = run.sites.firstOrNull { it.x == x && it.y == y && !it.spent }
        if (site?.kind == "trap" && run.tools > 0) return RelicQuickAction("disarm",
            "解除附近机关", "消耗一份拆解工具，避免触发陷阱", x, y)
        if (site?.kind == "altar") return RelicQuickAction("invoke", "唤醒古老祭坛",
            "可获取珍宝，也会惊动守卫", x, y)
        val drop = run.drops.firstOrNull {
            it.x == x && it.y == y && hero.load + it.loot.weight <= RelicEscapeRules.CAPACITY
        }
        if (drop != null) return RelicQuickAction("pickup", "拾取 ${drop.loot.name}",
            "拾取进背包，不会立即增加永久仓库", id = drop.loot.id)
        if (x == run.exitX && y == run.exitY && run.phase == RelicPhase.EXPLORE)
            return RelicQuickAction("extract", "启动撤离 · 守住三回合",
                "守在出口附近，经过三回合才真正带走宝物")
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
