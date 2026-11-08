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

/**
 * Generic tastes are remembered by the normal coalesced extractor, not a second
 * paid "urgent memory" call followed by the very same batch extractor.
 * Explicit remember/correction/safety/boundary requests still get priority.
 * These are scheduling hints only; the model judges meaning in full context.
 */
internal fun requestsImmediateMemory(text: String): Boolean = listOf(
    "记住", "记着", "别忘", "不要忘", "很重要", "以后", "从今", "过敏",
    "不要再", "别再", "改成", "改了", "纠正", "其实我是",
    "叫我", "喊我", "称呼我", "这个称呼", "这种叫法",
).any(text::contains)

/**
 * A compact selection lens from THIS character's actual authored definition.
 * It is NOT a new personality, memory store or report of feelings.
 * Source events (and their revisions) remain the sole evidence for any memory.
 * Never send full character profiles into every inexpensive batch.
 */
internal fun memorySelectionPerspective(characterId: String): String {
    val persona = MigratedDomainStores.characters.get(characterId).persona
    val profile = CharacterLifeStore.state(characterId).optJSONObject("profile")
    val fields = listOf(
        "values" to "珍视的事", "motives" to "长期关切",
        "perception" to "注意方式", "care" to "亲近方式",
        "respect" to "关系边界", "interests" to "兴趣",
    ).mapNotNull { (key, label) ->
        profile?.optString(key)?.trim()?.takeIf(String::isNotBlank)?.let { label to it }
    }
    val relationship = CharacterSocialRelationship.promptSection(characterId)
    return renderMemorySelectionPerspective(persona, fields, relationship)
}

internal fun renderMemorySelectionPerspective(
    persona: String, fields: List<Pair<String, String>>, relationship: String,
): String = buildString {
    appendLine("【当前角色的记忆注意视角｜仅用于判断小事的个人意义，不是事件证据】")
    if (persona.isNotBlank()) appendLine("身份：${persona.trim().take(320)}")
    fields.forEach { (label, value) ->
        if (value.isNotBlank()) appendLine("$label：${value.trim().take(180)}")
    }
    if (relationship.isNotBlank()) appendLine("已知关系：${relationship.trim().take(260)}")
    appendLine("仅以本人的真实可见事件为依据；不把上面的人设或关系描述当作某件事发生过，也不代替本人事后的主观选择。")
}

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
    val live = SharedExperienceTimeline.eventsByIds(characterId, ids).associateBy { it.id }
    return ids.all { id -> live[id]?.let { event ->
        memory.sourceRevisions[id]?.let { it == event.revision } ?: true
    } == true }
}
