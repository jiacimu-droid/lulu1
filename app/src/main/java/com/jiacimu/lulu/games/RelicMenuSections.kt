package com.jiacimu.lulu.games

/**
 * Navigation-only grouping for the real relic ledger screens. Each section renders
 * the existing live state; menu changes never advance a turn or create rewards.
 */
internal object RelicMenuSections {
    private val activePhases=setOf(RelicPhase.EXPLORE,RelicPhase.EXTRACT,RelicPhase.DOWNED)
    val camp=listOf("准备","仓库","图鉴")
    val expedition=listOf("现场","战术","背包","记录")

    fun forPhase(phase:RelicPhase?):List<String> =
        if (phase!=null && phase in activePhases) expedition else camp

    fun defaultFor(phase:RelicPhase?):String=forPhase(phase).first()
}
