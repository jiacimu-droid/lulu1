package com.jiacimu.lulu

/**
 * Player-owned exploration progression. It never decides what a character feels,
 * says or wants: it only makes real, persistent landmark discoveries meaningful.
 */
internal object DigitalStreetJourney {
    val clueIds = listOf("old-book-stall", "wishing-tree", "viewpoint")
    val clueNames = listOf("旧书摊", "许愿树", "观景台")
    const val MEMENTO = "memory-postbox"

    /** The following clue is uncovered by the previous confirmed visit. */
    fun nextClue(visited: Set<String>): String? = clueIds.firstOrNull { it !in visited }

    fun visibleClues(visited: Set<String>): Set<String> =
        (visited.intersect(clueIds.toSet()) + listOfNotNull(nextClue(visited))).toSet()

    fun isMementoUnlocked(visited: Set<String>): Boolean = clueIds.all { it in visited }

    fun progressText(visited: Set<String>): String {
        val found = clueIds.count { it in visited }
        val next = nextClue(visited)?.let { clueIds.indexOf(it) }
        return if (next == null) "三处线索已找到 · 去街道中段寻找纪念邮筒"
        else "探索线索 $found/3 · 下一处：${clueNames[next]}"
    }

    /** Distinct real participants can each leave their own lasting group memory. */
    fun participantKey(ids: List<String>): String =
        ids.distinct().sorted().joinToString("|").ifBlank { "solo" }

    fun mementoText(names: List<String>, time: String): String {
        val people = names.filter(String::isNotBlank).distinct().joinToString("、")
            .ifBlank { "我" }
        return "$time · 云上小镇\n$people 一起走过旧书摊、许愿树与观景台，回到街道里的纪念邮筒，留下了这张亲手完成探索的旅行卡。"
    }
}
