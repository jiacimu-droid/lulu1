package com.jiacimu.lulu.data

import android.content.Context
import java.time.Duration
import java.time.Instant

/** Only this character's witnessed events. No omniscient reads of other rooms or private chats. */
internal object CharacterPerceptionContext {
    fun recent(characterId: String, now: Instant = Instant.now()): List<SharedTimelineEvent> =
        selectRecent(SharedExperienceTimeline.recentEvents(characterId, 80), now)

    fun selectRecent(events: List<SharedTimelineEvent>, now: Instant): List<SharedTimelineEvent> =
        events.filter { event ->
            event.evidenceKind == EventEvidenceKind.Observation &&
                (event.source in setOf("digital-world", "digital-environment", "meeting") ||
                    event.channel.startsWith("数字世界事件")) &&
                !event.occurredAt.isAfter(now) &&
                Duration.between(event.occurredAt, now) <= Duration.ofHours(24)
        }.takeLast(12)

    fun pending(context: Context, characterId: String, now: Instant): List<SharedTimelineEvent> =
        recent(characterId, now).filterNot { PerceptionStimulusLedger.hasSeen(context, characterId, it.id) }

    fun stimulus(event: SharedTimelineEvent): PerceptionStimulus = PerceptionStimulus(
        event.id, "[${event.occurredAt}] ${event.content.take(260)}",
        if (event.source == "meeting" || event.id.startsWith("world-touch-") ||
            event.id.startsWith("world-visit-")) setOf("user") else emptySet(),
    )

    fun render(events: List<SharedTimelineEvent>): String = if (events.isEmpty()) "" else buildString {
        appendLine("【实际目睹的现场变化｜按先后一起理解，不只盯着聊天】")
        events.forEach { appendLine("- evidenceId=${it.id}；[${it.occurredAt}] ${it.content.take(300)}") }
        appendLine("可以形成感受、联系记忆、主动表达或不提；已经过去的到访/接触不等于现在仍在场，当前权威状态优先。")
    }.trim()
}
