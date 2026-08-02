package com.jiacimu.lulu.data

import com.jiacimu.lulu.LuluRepositories
import com.jiacimu.lulu.core.MemoryEntry
import java.time.Duration
import java.time.Instant
import kotlin.math.ln

/**
 * Lightweight local relevance ranking for chat recall.
 *
 * Lulu1 deliberately keeps retrieval deterministic and offline: pinned/strong memories remain
 * available, while lexical overlap with the current turn decides which ordinary memories enter
 * the prompt. This prevents a growing memory bank from always returning only the newest entries.
 */
object RelevantMemoryRecall {
    fun recall(
        characterId: String,
        query: String,
        limit: Int = 12,
        now: Instant = Instant.now(),
    ): List<MemoryEntry> {
        val cleanQuery = query.trim()
        val queryTerms = terms(cleanQuery)
        return LuluRepositories.memory.snapshot(characterId)
            .asSequence()
            .map { memory -> memory to score(memory, queryTerms, now) }
            .filter { (memory, score) -> memory.pinned || score > MIN_RELEVANCE_SCORE }
            .sortedWith(
                compareByDescending<Pair<MemoryEntry, Double>> { (memory, _) -> memory.pinned }
                    .thenByDescending { (_, score) -> score }
                    .thenByDescending { (memory, _) -> memory.occurredAt ?: memory.createdAt },
            )
            .take(limit.coerceIn(1, 24))
            .map { it.first }
            .toList()
    }

    fun formatForPrompt(memories: List<MemoryEntry>): String = if (memories.isEmpty()) {
        ""
    } else {
        buildString {
            appendLine("与当前对话相关的连续记忆（只能按原文使用，不得补写未发生事实）：")
            memories.forEach { memory ->
                append("- [")
                append(memory.kind.name)
                append("] ")
                appendLine(memory.content)
            }
        }.trim()
    }

    private fun score(memory: MemoryEntry, queryTerms: Set<String>, now: Instant): Double {
        val memoryTerms = terms(memory.content)
        val overlap = if (queryTerms.isEmpty() || memoryTerms.isEmpty()) 0.0 else {
            queryTerms.intersect(memoryTerms).size.toDouble() /
                queryTerms.union(memoryTerms).size.coerceAtLeast(1).toDouble()
        }
        val exactBoost = queryTerms.count { term -> term.length >= 2 && memory.content.contains(term, ignoreCase = true) }
            .coerceAtMost(4) * 0.65
        val strengthBoost = memory.strength.coerceIn(1, 10) / 10.0
        val ageDays = Duration.between(memory.occurredAt ?: memory.createdAt, now)
            .toDays()
            .coerceAtLeast(0)
        val recencyBoost = 1.0 / (1.0 + ln(2.0 + ageDays.toDouble()))
        val pinnedBoost = if (memory.pinned) 3.0 else 0.0
        return overlap * 8.0 + exactBoost + strengthBoost + recencyBoost + pinnedBoost
    }

    private fun terms(text: String): Set<String> {
        val normalized = text.lowercase()
            .replace(Regex("[^\\p{L}\\p{N}]+"), " ")
            .trim()
        if (normalized.isBlank()) return emptySet()
        val result = mutableSetOf<String>()
        normalized.split(Regex("\\s+")).forEach { token ->
            if (token.length >= 2) result += token
            if (token.any { it.code > 127 }) {
                token.windowed(size = 2, step = 1, partialWindows = false).forEach(result::add)
                token.windowed(size = 3, step = 1, partialWindows = false).forEach(result::add)
            }
        }
        return result
    }

    private const val MIN_RELEVANCE_SCORE = 1.15
}
