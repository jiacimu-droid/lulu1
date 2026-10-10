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
                (event.source in setOf("digital-world", "digital-environment", "meeting", InteractionSignalBridge.SOURCE) ||
                    event.channel.startsWith("数字世界事件")) &&
                !event.occurredAt.isAfter(now) &&
                Duration.between(event.occurredAt, now) <= Duration.ofHours(24)
        }.takeLast(12)

    fun pending(context: Context, characterId: String, now: Instant): List<SharedTimelineEvent> =
        recent(characterId, now).filterNot { PerceptionStimulusLedger.hasSeen(context, characterId, it.id) }

    fun stimulus(event: SharedTimelineEvent): PerceptionStimulus = PerceptionStimulus(
        event.id, "[${event.occurredAt}] ${event.content.take(260)}",
        if (event.source == "meeting" || event.id.startsWith("world-touch-") ||
            event.id.startsWith("world-visit-") ||
            (event.source == InteractionSignalBridge.SOURCE && event.content.contains("由用户点击挂断"))) setOf("user") else emptySet(),
    )

    fun render(events: List<SharedTimelineEvent>): String = if (events.isEmpty()) "" else buildString {
        appendLine("【真实感知到的互动与世界事件｜按先后一起理解，不只盯着聊天】")
        events.forEach { appendLine("- evidenceId=${it.id}；[${it.occurredAt}] ${it.content.take(300)}") }
        appendLine("先区分客观事实与主观解释。对沉默、挂断、设备收音不确定等行为可以有多种情绪和判断，不能直接断言用户的内心意图。可以继续想、改变感受、联系、等待或不做什么；均由人格和关系决定。过去的互动与到访不等于此刻仍在进行。")
    }.trim()
}
