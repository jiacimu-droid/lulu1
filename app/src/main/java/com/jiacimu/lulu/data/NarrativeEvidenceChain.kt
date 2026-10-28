package com.jiacimu.lulu.data

import java.time.Duration
import java.time.Instant

/**
 * Read-only, evidence-linked episode projection. "Related" means a shared
 * authoritative task or a close, explicit session, never inferred causality.
 * No new store, paid summarizer, keyword-based personality, or history pruning.
 *
 * Every candidate must still exist with its SAME revision in the live,
 * recall-authorized timeline supplied by UnifiedMemoryOrchestrator.
 */
internal object NarrativeEvidenceChain {
    fun expand(
        seeds: List<SharedTimelineEvent>,
        live: List<SharedTimelineEvent>,
        limit: Int = 32,
        now: Instant = Instant.now(),
    ): List<SharedTimelineEvent> {
        if (limit <= 0) return emptyList()
        val current = live.asSequence()
            .filter { !it.occurredAt.isAfter(now) }
            .distinctBy { it.id }
            .associateBy { it.id }
        val validSeeds = seeds.distinctBy { it.id }.mapNotNull { seed ->
            current[seed.id]?.takeIf { it == seed }
        }
        if (validSeeds.isEmpty()) return emptyList()
        val chosen = linkedMapOf<String, SharedTimelineEvent>()
        // Keep room for linked outcomes when a query saturates the input
        // budget; give unspent room back to ranked raw hits at the end.
        val reserve = if (validSeeds.size >= limit && limit >= 4)
            minOf(4, limit / 4) else 0
        validSeeds.take(limit - reserve).forEach { chosen[it.id] = it }
        if (chosen.size >= limit) return chosen.values.toList()
        val ordered = compareBy<SharedTimelineEvent> { it.occurredAt }.thenBy { it.id }
        val taskGroups = current.values.asSequence().filter { it.taskId.isNotBlank() }
            .groupBy { it.characterId to it.taskId }
            .mapValues { (_, group) -> group.sortedWith(ordered) }
        val sessionGroups = current.values.asSequence().filter { it.sessionId.isNotBlank() }
            .groupBy { it.characterId to it.sessionId }
            .mapValues { (_, group) -> group.sortedWith(ordered) }

        fun add(event: SharedTimelineEvent) {
            if (chosen.size < limit) chosen.putIfAbsent(event.id, event)
        }
        validSeeds.take(8).forEach { seed ->
            // Strong, stable task IDs bridge phone, chat, tools and later
            // feedback across days without inventing a semantic connection.
            if (seed.taskId.isNotBlank()) {
                val related = taskGroups[seed.characterId to seed.taskId].orEmpty()
                val index = related.indexOfFirst { it.id == seed.id }
                if (index >= 0) {
                    listOf(index - 2, index - 1, index + 1, index + 2)
                        .mapNotNull(related::getOrNull).forEach(::add)
                    // A later factual result must not disappear just because
                    // there are many intermediate thoughts/messages.
                    related.lastOrNull {
                        it.occurredAt >= seed.occurredAt &&
                            it.evidenceKind == EventEvidenceKind.ToolResult
                    }?.let(::add)
                }
            }
            // A session is weaker than a task. Use a strict time neighborhood
            // even across scene channels; never join events days apart solely
            // because they reused a session label.
            if (seed.sessionId.isNotBlank()) {
                val related = sessionGroups[seed.characterId to seed.sessionId].orEmpty()
                    .filter {
                        Duration.between(seed.occurredAt, it.occurredAt).abs().seconds <= 900
                    }
                val index = related.indexOfFirst { it.id == seed.id }
                if (index >= 0) {
                    listOf(index - 1, index + 1)
                        .mapNotNull(related::getOrNull).forEach(::add)
                }
            }
        }
        validSeeds.forEach(::add)
        return chosen.values.toList()
    }

    /**
     * These are explicit ordering hints for the SAME raw evidence already
     * delivered to the role. Never label a speech promise as a completed task.
     * A removed, edited, or revoked row cannot survive the next projection.
     */
    fun annotations(events: List<SharedTimelineEvent>): Map<String, String> {
        val ordered = compareBy<SharedTimelineEvent> { it.occurredAt }.thenBy { it.id }
        val distinct = events.distinctBy { it.id }.sortedWith(ordered)
        val result = mutableMapOf<String, String>()
        val byTask = distinct.filter { it.taskId.isNotBlank() }
            .groupBy { it.characterId to it.taskId }
        for ((key, group) in byTask) {
            if (group.size < 2) continue
            group.forEachIndexed { index, event ->
                result[event.id] = "〔同任务事件链 ${index + 1}/${group.size} taskId=${key.second.take(72)}；同编号不等于已完成〕"
            }
        }
        val bySession = distinct.filter { it.sessionId.isNotBlank() && it.id !in result }
            .groupBy { it.characterId to it.sessionId }
        for ((_, group) in bySession) {
            // Consecutive 15-minute links are one local session episode,
            // not evidence that the speakers agreed or the task succeeded.
            val clusters = mutableListOf<MutableList<SharedTimelineEvent>>()
            for (event in group) {
                val last = clusters.lastOrNull()?.lastOrNull()
                if (last == null ||
                    Duration.between(last.occurredAt, event.occurredAt).seconds > 900) {
                    clusters += mutableListOf(event)
                } else clusters.last() += event
            }
            for (cluster in clusters.filter { it.size >= 2 }) {
                cluster.forEachIndexed { index, event ->
                    result[event.id] = "〔同会话先后记录 ${index + 1}/${cluster.size}；只证明记录相邻，不推定因果〕"
                }
            }
        }
        return result
    }
}
