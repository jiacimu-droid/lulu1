package com.jiacimu.lulu.data

import com.jiacimu.lulu.LuluRepositories
import com.jiacimu.lulu.core.MemoryEntry
import java.time.Duration
import java.time.Instant
import kotlin.math.ln

/**
 * Memory recall uses the configured Embedding/Rerank services when enabled and falls back to the
 * deterministic local lexical ranker whenever a service is absent or temporarily unavailable.
 */
object RelevantMemoryRecall {
    suspend fun recall(
        characterId: String,
        query: String,
        limit: Int = 12,
        now: Instant = Instant.now(),
    ): List<MemoryEntry> {
        val cleanQuery = query.trim()
        val queryTerms = terms(cleanQuery)
        val memories = LuluRepositories.memory.snapshot(characterId)
            .asSequence()
            .filter { memory ->
                DigitalLifeProfileStore.allowsTimestamp(
                    characterId,
                    memory.occurredAt ?: memory.createdAt,
                )
            }
            .take(60)
            .toList()
        val lexical = memories
            .asSequence()
            .map { memory -> memory to score(memory, queryTerms, now) }
            .filter { (memory, score) -> memory.pinned || score > MIN_RELEVANCE_SCORE }
            .sortedWith(
                compareByDescending<Pair<MemoryEntry, Double>> { (memory, _) -> memory.pinned }
                    .thenByDescending { (_, score) -> score }
                    .thenByDescending { (memory, _) -> memory.occurredAt ?: memory.createdAt },
            )
            .take(40)
            .map { it.first }
            .toList()
        val vectorRanked = if (MemoryModelRuntime.vectorEnabled()) {
            val connection = MemoryModelRuntime.embeddingConnection()
            if (connection == null || cleanQuery.isBlank() || memories.isEmpty()) null else {
                com.jiacimu.lulu.ai.LuluAiServices.gateway.embed(connection, listOf(cleanQuery) + memories.map { it.content })
                    .getOrNull()
                    ?.takeIf { it.size == memories.size + 1 }
                    ?.let { vectors ->
                        val queryVector = vectors.first()
                        memories.zip(vectors.drop(1))
                            .sortedWith(
                                compareByDescending<Pair<MemoryEntry, FloatArray>> { it.first.pinned }
                                    .thenByDescending { cosine(queryVector, it.second) },
                            )
                            .map { it.first }
                    }
            }
        } else null
        var candidates = (vectorRanked ?: lexical).take(40)
        if (MemoryModelRuntime.rerankEnabled() && cleanQuery.isNotBlank() && candidates.isNotEmpty()) {
            val connection = MemoryModelRuntime.rerankConnection()
            if (connection != null) {
                val order = com.jiacimu.lulu.ai.LuluAiServices.gateway.rerank(connection, cleanQuery, candidates.map { it.content }).getOrNull()
                if (!order.isNullOrEmpty()) {
                    candidates = order.mapNotNull { index -> candidates.getOrNull(index) } +
                        candidates.filterIndexed { index, _ -> index !in order }
                }
            }
        }
        return candidates.distinctBy { it.id }.take(limit.coerceIn(1, 24))
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

    private fun cosine(left: FloatArray, right: FloatArray): Double {
        if (left.size != right.size || left.isEmpty()) return -1.0
        var dot = 0.0
        var leftNorm = 0.0
        var rightNorm = 0.0
        for (index in left.indices) {
            dot += left[index] * right[index]
            leftNorm += left[index] * left[index]
            rightNorm += right[index] * right[index]
        }
        return if (leftNorm == 0.0 || rightNorm == 0.0) -1.0 else dot / kotlin.math.sqrt(leftNorm * rightNorm)
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
