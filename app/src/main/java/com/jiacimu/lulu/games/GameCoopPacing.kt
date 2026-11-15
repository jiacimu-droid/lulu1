package com.jiacimu.lulu.games

import kotlin.math.abs

/**
 * Shared game-only pacing. A phone turn needs a new utterance; a co-op game
 * also has a real world and a previously chosen companion goal.
 *
 * The timer is only a clock for already-owned physical plans. It never infers
 * what a speaker "meant", creates a target, or buys a Game-model request.
 */
internal object GameCoopPacing {
    const val PLANNED_STEP_MS: Long = 6_000L

    fun canTick(
        hasPlan: Boolean,
        isForeground: Boolean,
        navigating: Boolean,
        thinking: Boolean,
        playerSpeaking: Boolean,
        nearbyThreat: Boolean,
    ): Boolean = hasPlan && isForeground && !navigating && !thinking &&
        !playerSpeaking && !nearbyThreat

    fun islandMemberHasPlan(s: BlockIslandState, id: String): Boolean =
        id in s.companionTravel ||
            s.houseProject?.let { it.ownerId == id &&
                it.phase in setOf("building", "demolishing") } == true

    fun islandHasPlan(s: BlockIslandState, autonomous: Boolean): Boolean {
        if (!autonomous || s.world.finished) return false
        val activeIds = s.world.friends.filter { it.hp > 0 }.map { it.id }.toSet()
        if (activeIds.isEmpty()) return false
        return s.companionTravel.keys.any { it in activeIds } ||
            s.houseProject?.let { it.ownerId in activeIds &&
                it.phase in setOf("building", "demolishing") } == true
    }

    fun relicHasPlan(s: RelicRun): Boolean =
        s.phase in setOf(RelicPhase.EXPLORE, RelicPhase.EXTRACT, RelicPhase.DOWNED) &&
            s.coopPlans.keys.any { id -> s.allies.any { it.id == id && it.hp > 0 } }

    /** Idle camera time must never silently kill an AFK player. */
    fun islandThreat(s: BlockIslandState): Boolean =
        s.world.hp <= 3 || s.world.mobs.any { it.hp > 0 &&
            abs(it.x - s.world.x) + abs(it.y - s.world.y) <= 2 }

    fun relicThreat(s: RelicRun): Boolean {
        // Do not freeze the last remaining rescue opportunity just because the
        // owner is downed. The actor explicitly chose rescue; the real rules
        // still enforce medicine, distance, danger and rescue timeout.
        if (s.phase == RelicPhase.DOWNED &&
            s.coopPlans.any { (id, plan) -> plan.kind == "rescue" &&
                s.allies.any { it.id == id && it.hp > 0 && it.kits > 0 } })
            return false
        return s.hero.hp <= 3 || s.mobs.any { it.hp > 0 && s.visible(it.x, it.y) &&
            RelicEscapeRules.distance(it.x, it.y, s.hero.x, s.hero.y) <= 2 }
    }
}
