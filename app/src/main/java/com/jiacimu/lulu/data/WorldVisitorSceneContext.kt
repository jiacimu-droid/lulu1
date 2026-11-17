package com.jiacimu.lulu.data

import java.time.Instant

/**
 * A role-owned projection of the ORIGINAL visit observations, not a second presence store.
 * A remote arrival is a notification, never proof the visitor can still be seen.
 * Explicit location IDs prevent a visit at one venue contaminating another scene.
 */
internal object WorldVisitorSceneContext {
    fun select(
        events: List<SharedTimelineEvent>,
        locationCode: String,
        at: Instant,
        limit: Int = 6,
    ): List<SharedTimelineEvent> {
        if (locationCode.isBlank() || limit <= 0) return emptyList()
        return events.asSequence()
            .filter { event ->
                event.source == "digital-world-arrival" &&
                    event.sessionId == locationCode &&
                    !event.occurredAt.isAfter(at) &&
                    (event.id.startsWith("world-arrival-enter-") ||
                        event.id.startsWith("world-arrival-leave-"))
            }
            .sortedWith(compareBy<SharedTimelineEvent> { it.occurredAt }.thenBy { it.id })
            .toList().takeLast(limit)
    }

    fun eventsFor(characterId: String, locationCode: String, at: Instant): List<SharedTimelineEvent> =
        select(SharedExperienceTimeline.recentEvents(characterId, 240), locationCode, at)

    fun describe(events: List<SharedTimelineEvent>): String = buildString {
        if (events.isEmpty()) {
            append("尚无可核实的本地点主人到访或离场通知；不能断言主人在场或不在场。")
            return@buildString
        }
        appendLine("本角色确实收到的主人到访／离场原始记录（严格按时间；他人未必收到同一通知）：")
        events.forEach { event ->
            val kind = if (event.id.startsWith("world-arrival-leave-")) "离场"
                else "到访"
            appendLine("- ${event.occurredAt} [$kind] ${event.content.take(260)}")
        }
        append("到访通知只证明当时曾到访，不保证现在还在；离场通知证明那次离开，不代表以后没有再来。是否实际碰见，须有本人的现场目击依据。")
    }

    fun forCharacter(characterId: String, locationCode: String, at: Instant): String =
        describe(eventsFor(characterId, locationCode, at))
}
