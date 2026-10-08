package com.jiacimu.lulu.data

import com.jiacimu.lulu.core.MemoryEntry
import com.jiacimu.lulu.core.MemoryTier
import java.time.Duration
import java.time.Instant

/** Core facts are a separate channel: ordinary retrieval quotas cannot evict them. */
internal fun MemoryEntry.isCoreMemory(): Boolean = pinned || tier == MemoryTier.Core

internal fun selectMemoryWithinBudget(
    ranked: List<MemoryEntry>,
    characterBudget: Int = 9_000,
    safetyLimit: Int = 128,
): List<MemoryEntry> {
    var remaining = characterBudget.coerceAtLeast(0)
    return ranked.distinctBy(MemoryEntry::id).filter { memory ->
        val cost = memory.content.length + 80
        if (cost <= remaining) { remaining -= cost; true } else false
    }.take(safetyLimit.coerceAtLeast(0))
}

internal fun shouldFlushMemoryTail(lastEventAt: Instant?, now: Instant = Instant.now()): Boolean =
    lastEventAt != null && Duration.between(lastEventAt, now).seconds >= 600

/** Signals only schedule a contextual model check; they never create facts themselves. */
internal fun requestsImmediateMemory(text: String): Boolean = listOf(
    "记住", "记着", "别忘", "不要忘", "很重要", "以后", "从今", "喜欢", "讨厌", "过敏",
    "不爱吃", "不喜欢", "不要再", "别再", "改成", "改了", "纠正", "其实我是",
).any(text::contains)

internal fun SharedTimelineEvent.isUserMemoryStatement(): Boolean =
    evidenceKind == EventEvidenceKind.UserStatement ||
        (evidenceKind == EventEvidenceKind.Legacy && speaker in setOf("主人", "用户", UserProfileContext.displayLabel()))

internal fun MemoryEntry.sourceIds(): List<String> = when {
    source.startsWith("timeline-events:") -> source.removePrefix("timeline-events:").split('|')
    source.startsWith("timeline-batch:") -> source.removePrefix("timeline-batch:").split('|')
    else -> emptyList()
}.filter(String::isNotBlank)

internal fun memoryHasLiveSources(memory: MemoryEntry, characterId: String): Boolean {
    val ids = memory.sourceIds()
    return ids.isEmpty() || SharedExperienceTimeline.eventsByIds(characterId, ids).size == ids.distinct().size
}
