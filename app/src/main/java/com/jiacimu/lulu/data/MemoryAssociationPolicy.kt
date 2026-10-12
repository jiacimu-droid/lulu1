package com.jiacimu.lulu.data

import com.jiacimu.lulu.core.MemoryEntry
import java.time.Duration
import java.time.Instant
import kotlin.math.exp

internal data class MemoryAssociationHit(val memory: MemoryEntry, val viaId: String, val reason: String, val depth: Int)

/** Evidence graph, reconstructed from valid memories; no guessed causal relationships are stored. */
internal object MemoryAssociationPolicy {
    private val generic = setOf("用户", "角色", "本人", "我", "你", "偏好", "事实", "日常", "其他", "关系", "情绪")
    private fun normalized(value: String) = value.lowercase().filter(Char::isLetterOrDigit)
    private fun keys(memory: MemoryEntry): Set<String> = buildSet {
        // A legacy batch covers many unrelated turns: it must not make a giant clique.
        if (memory.source.startsWith("timeline-events:")) memory.sourceIds().forEach { add("evidence:$it") }
        val subject = normalized(memory.subject)
        val slot = normalized(memory.slot)
        if (subject.isNotBlank() && slot.length >= 2 && slot !in generic) add("slot:$subject:$slot")
    }

    fun expand(memories: List<MemoryEntry>, seeds: List<MemoryEntry>, limit: Int = 16): List<MemoryAssociationHit> {
        if (seeds.isEmpty() || limit <= 0) return emptyList()
        val index = mutableMapOf<String, MutableList<MemoryEntry>>()
        memories.forEach { memory -> keys(memory).forEach { index.getOrPut(it) { mutableListOf() }.add(memory) } }
        val seen = seeds.mapTo(mutableSetOf(), MemoryEntry::id)
        val hits = mutableListOf<MemoryAssociationHit>()
        var frontier = seeds.take(8)
        for (depth in 1..2) {
            val next = mutableListOf<MemoryEntry>()
            frontier.forEach { seed ->
                keys(seed).forEach keyLoop@ { key ->
                    // Frequent broad slots are weak associations, not permission to flood context.
                    val linked = index[key].orEmpty()
                    if (key.startsWith("slot:") && linked.size > 12) return@keyLoop
                    linked.filter { it.characterId == seed.characterId }.sortedWith(compareByDescending<MemoryEntry> { it.strength }
                        .thenByDescending { it.occurredAt ?: it.createdAt }).forEach { memory ->
                        if (hits.size < limit && seen.add(memory.id)) {
                            hits += MemoryAssociationHit(memory, seed.id,
                                if (key.startsWith("evidence:")) "同一原始事件" else "同一对象的具体事实槽位", depth)
                            next += memory
                        }
                    }
                }
            }
            frontier = next
        }
        return hits
    }

    /** Importance and recency compete only among relevant/associated candidates. */
    fun priority(memory: MemoryEntry, relevance: Double, association: Double = 0.0,
                 now: Instant = Instant.now()): Double {
        val ageDays = Duration.between(memory.occurredAt ?: memory.createdAt, now).seconds.coerceAtLeast(0) / 86_400.0
        val recency = exp(-ageDays / 7.0)
        val importance = memory.strength.coerceIn(1, 10) / 10.0
        return relevance.coerceAtLeast(0.0) * 0.65 + importance * 0.20 + recency * 0.15 + association.coerceIn(0.0, 1.0) * 0.20
    }

    fun localRelevant(memories: List<MemoryEntry>, query: String, limit: Int = 18,
                      now: Instant = Instant.now()): List<MemoryEntry> {
        val terms = memorySearchTerms(query)
        if (terms.isEmpty()) return emptyList()
        val direct = memories.mapNotNull { memory ->
            val overlap = terms.intersect(memorySearchTerms(memory.content + " " + memory.subject + " " + memory.slot)).size
            if (overlap == 0) null else memory to priority(memory, overlap.toDouble() / (overlap + 3), now = now)
        }.sortedByDescending { it.second }.take(32)
        val graph = expand(memories, direct.map { it.first }, limit = 12)
        val scores = direct.associate { it.first.id to it.second }.toMutableMap()
        graph.forEach { hit -> scores[hit.memory.id] = maxOf(scores[hit.memory.id] ?: 0.0,
            priority(hit.memory, 0.10, 0.5 / hit.depth, now)) }
        return (direct.map { it.first } + graph.map { it.memory }).distinctBy(MemoryEntry::id)
            .sortedByDescending { scores[it.id] }.take(limit.coerceAtLeast(0))
    }
}
