package com.jiacimu.lulu.games

/**
 * Read-only encounter presentation. The game is turn based: never invent a
 * wall-clock timer, a companion action, treasure ownership or new turns here.
 */
internal enum class RelicTensionLevel { QUIET, TREASURE, CAUTION, DANGER, EXTRACT, DOWNED }

internal data class RelicTensionPresentation(
    val level: RelicTensionLevel,
    val headline: String,
    val completedExtractionTurns: Int = 0,
) {
    val urgent: Boolean get() = level == RelicTensionLevel.DANGER ||
        level == RelicTensionLevel.EXTRACT || level == RelicTensionLevel.DOWNED
}

internal fun relicTension(run: RelicRun): RelicTensionPresentation {
    if (run.phase == RelicPhase.DOWNED) {
        return RelicTensionPresentation(RelicTensionLevel.DOWNED,
            "等待救援 · 剩余${(4 - run.downTurns).coerceAtLeast(0)}回合")
    }
    if (run.phase !in setOf(RelicPhase.EXPLORE, RelicPhase.EXTRACT)) {
        return RelicTensionPresentation(RelicTensionLevel.QUIET, "")
    }
    val chargingEnemy = run.mobs.any { mob ->
        mob.hp > 0 && mob.windup && run.visible(mob.x, mob.y) &&
            RelicEscapeRules.distance(run.hero.x, run.hero.y, mob.x, mob.y) <= 3
    }
    if (chargingEnemy) {
        return RelicTensionPresentation(RelicTensionLevel.DANGER,
            "危险 · 守卫蓄力！格挡或贴近打断", run.extraction.coerceIn(0, 3))
    }
    if (run.phase == RelicPhase.EXTRACT) {
        return RelicTensionPresentation(RelicTensionLevel.EXTRACT,
            "正在撤离 · 再守住${(3 - run.extraction).coerceAtLeast(0)}回合",
            run.extraction.coerceIn(0, 3))
    }
    if (run.hero.hp <= 3) {
        return RelicTensionPresentation(RelicTensionLevel.CAUTION,
            "生命危险 · 优先治疗或寻找出口")
    }
    if (run.carried.any { it.tier == RelicTier.PRECIOUS }) {
        return RelicTensionPresentation(RelicTensionLevel.TREASURE,
            "珍贵藏品已入袋 · 继续搜寻或撤离，由你决定")
    }
    return RelicTensionPresentation(RelicTensionLevel.QUIET, "")
}
