package com.jiacimu.lulu.data

import com.jiacimu.lulu.LuluRepositories
import com.jiacimu.lulu.ai.ModelConnection
import com.jiacimu.lulu.core.MemoryEntry
import java.time.Duration
import java.time.Instant
import kotlin.math.ln

/**
 * Associative memory recall over the character's complete valid memory set.
 *
 * Lexical relevance, persistent full-set vectors, temporal intent and pinned memories contribute
 * candidates. Rerank only reorders already-relevant candidates; it never forces unrelated memory
 * into context. Returning zero memories is an intentional valid result.
 */
object RelevantMemoryRecall {
    private data class EmbeddingRankResult(
        val ranked: List<MemoryEntry> = emptyList(),
        val similarities: Map<String, Double> = emptyMap(),
    )

    private val embeddingCache = object : LinkedHashMap<String, FloatArray>(128, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, FloatArray>?): Boolean =
            size > MAX_EMBEDDING_CACHE
    }

    suspend fun recall(
        characterId: String,
        query: String,
        limit: Int = 18,
        now: Instant = Instant.now(),
    ): List<MemoryEntry> {
        val cleanQuery = focusQuery(query)
        if (cleanQuery.isBlank()) return emptyList()
        val queryTerms = terms(cleanQuery)
        val memories = LuluRepositories.memory.snapshot(characterId)
            .asSequence()
            .filter { memory ->
                DigitalLifeProfileStore.allowsTimestamp(
                    characterId,
                    memory.occurredAt ?: memory.createdAt,
                )
            }
            .toList()
        if (memories.isEmpty()) return emptyList()

        val lexicalScores = memories.associate { memory ->
            memory.id to score(memory, queryTerms, cleanQuery, now)
        }
        val lexicalRanked = memories
            .asSequence()
            .filter { memory ->
                memory.pinned || (
                    hasLexicalSignal(memory, queryTerms, cleanQuery, now) &&
                        (lexicalScores[memory.id] ?: 0.0) > MIN_RELEVANCE_SCORE
                    )
            }
            .sortedWith(
                compareByDescending<MemoryEntry>(MemoryEntry::pinned)
                    .thenByDescending { memory -> lexicalScores[memory.id] ?: 0.0 }
                    .thenByDescending { memory -> memory.occurredAt ?: memory.createdAt },
            )
            .take(LEXICAL_CANDIDATES)
            .toList()

        val embeddingResult = if (MemoryModelRuntime.vectorEnabled()) {
            val connection = MemoryModelRuntime.embeddingConnection()
            if (connection == null) EmbeddingRankResult() else {
                rankByEmbedding(connection, cleanQuery, memories)
            }
        } else EmbeddingRankResult()
        val vectorRanked = embeddingResult.ranked
        val pinnedRanked = memories.filter(MemoryEntry::pinned)
            .sortedByDescending { it.occurredAt ?: it.createdAt }

        // Recency is not an independent excuse to inject unrelated memories. It participates only
        // when the user is explicitly referring to recent time, in which case date proximity itself
        // is semantic evidence.
        val recentRanked = if (hasTemporalIntent(cleanQuery)) {
            memories
                .filter { memory -> recentTemporalMatch(memory, now) }
                .sortedByDescending { memory -> memory.occurredAt ?: memory.createdAt }
                .take(RECENT_CANDIDATES)
        } else emptyList()

        var candidates = fuseRankings(
            memories = memories,
            rankings = listOf(
                lexicalRanked to 3.2,
                vectorRanked to 4.2,
                recentRanked to 1.15,
                pinnedRanked to 5.0,
            ),
        ).take(RERANK_POOL)
        if (candidates.isEmpty()) return emptyList()

        if (MemoryModelRuntime.rerankEnabled() && candidates.size > 1) {
            val connection = MemoryModelRuntime.rerankConnection()
            if (connection != null) {
                val order = com.jiacimu.lulu.ai.LuluAiServices.gateway
                    .rerank(connection, cleanQuery, candidates.map { it.content })
                    .getOrNull()
                if (!order.isNullOrEmpty()) {
                    val reranked = order.mapNotNull { index -> candidates.getOrNull(index) }
                        .distinctBy(MemoryEntry::id)
                    // The reranker supplies ordering, not a calibrated relevance score. Therefore it
                    // can reorder the candidate set but cannot revive memories rejected above.
                    candidates = fuseRankings(
                        memories = candidates,
                        rankings = listOf(
                            candidates to 2.1,
                            reranked to 5.0,
                        ),
                    ).take(RERANK_POOL)
                }
            }
        }

        val requested = limit.coerceIn(1, 24)
        val effectiveLimit = if (requested == 12) 18 else requested
        return candidates
            .distinctBy(MemoryEntry::id)
            .take(effectiveLimit)
    }

    /**
     * A vector hit is only a pointer. Expand its provenance back to raw timeline evidence so the
     * model receives the exact words and chronology instead of treating a summary as the record.
     */
    fun sourceEvidenceEvents(
        characterId: String,
        query: String,
        memories: List<MemoryEntry>,
        limit: Int = 10,
    ): List<SharedTimelineEvent> {
        if (memories.isEmpty()) return emptyList()
        val focused = focusQuery(query)
        val queryTerms = terms(focused)
        val ranked = mutableMapOf<String, Pair<SharedTimelineEvent, Double>>()
        memories.take(12).forEachIndexed { memoryRank, memory ->
            val precise = memory.source.startsWith("timeline-events:")
            val ids = when {
                precise -> memory.source.removePrefix("timeline-events:").split('|')
                memory.source.startsWith("timeline-batch:") -> memory.source.removePrefix("timeline-batch:").split('|')
                else -> emptyList()
            }.filter(String::isNotBlank)
            if (ids.isEmpty()) return@forEachIndexed
            val memoryTerms = terms(memory.content)
            SharedExperienceTimeline.eventsByIds(characterId, ids).forEach { event ->
                val eventTerms = terms("${event.channel} ${event.speaker} ${event.evidenceContent}")
                val semanticTerms = queryTerms + memoryTerms
                val overlap = if (semanticTerms.isEmpty() || eventTerms.isEmpty()) 0.0 else
                    semanticTerms.intersect(eventTerms).size.toDouble() / semanticTerms.size.coerceAtLeast(1)
                val score = (12.0 / (memoryRank + 1.0)) + overlap * 18.0 + if (precise) 8.0 else 0.0
                val previous = ranked[event.id]
                if (previous == null || score > previous.second) ranked[event.id] = event to score
            }
        }
        return ranked.values
            .sortedWith(compareByDescending<Pair<SharedTimelineEvent, Double>> { it.second }.thenByDescending { it.first.occurredAt })
            .take(limit.coerceIn(1, 16))
            .map(Pair<SharedTimelineEvent, Double>::first)
            .sortedBy(SharedTimelineEvent::occurredAt)
    }

    fun sourceEvidenceForPrompt(
        characterId: String,
        query: String,
        memories: List<MemoryEntry>,
        limit: Int = 10,
        characterBudget: Int = 4_200,
    ): String {
        val selected = sourceEvidenceEvents(characterId, query, memories, limit)
        if (selected.isEmpty()) return ""
        val lines = selected.map { event ->
            "[${event.occurredAt}] [${event.channel}] ${event.speaker}：${event.evidenceContent.take(1_200)}"
        }
        val kept = mutableListOf<String>()
        var used = 0
        for (line in lines) {
            if (used + line.length > characterBudget && kept.isNotEmpty()) break
            kept += line
            used += line.length
        }
        return buildString {
            appendLine("召回记忆对应的原始时间线证据：")
            appendLine("以下是摘要所指向的真实原始记录；事实、措辞和时间以原始记录为准，摘要仅用于检索。")
            kept.forEach(::appendLine)
        }.trim()
    }

    fun formatForPrompt(memories: List<MemoryEntry>): String = if (memories.isEmpty()) {
        ""
    } else {
        buildString {
            appendLine("与当前对话相关的连续记忆：")
            appendLine("- 这些记忆不是彼此孤立的关键词。请结合当前新消息理解它们之间的时间、因果、澄清和同一事件关系。")
            appendLine("- 如果当前消息是在补充、解释或明确以前较模糊的信息，要自然联想到旧记忆。例如旧记忆只知道用户某处不舒服，而当前消息说明了具体原因/症状，可以理解为对旧经历的进一步说明。")
            appendLine("- 对已经知道一部分的事情，不要表现得像第一次听说；可以对新增细节有反应，并自然表达‘原来之前那件事是这样’。")
            appendLine("- 可以做有当前消息直接支撑的关联，但不能把没有依据的猜测当作旧事实，也不能虚构未发生经历。")
            memories.forEach { memory ->
                append("- [")
                append(memory.kind.name)
                append("][")
                append(if (memory.occurredAt != null) "发生=" else "记录=")
                append(memory.occurredAt ?: memory.createdAt)
                append("] ")
                appendLine(memory.content.take(MAX_MEMORY_CHARS))
            }
        }.trim()
    }

    private suspend fun rankByEmbedding(
        connection: ModelConnection,
        cleanQuery: String,
        memories: List<MemoryEntry>,
    ): EmbeddingRankResult {
        if (cleanQuery.isBlank() || memories.isEmpty()) return EmbeddingRankResult()
        val queryVector = com.jiacimu.lulu.ai.LuluAiServices.gateway
            .embed(connection, listOf(cleanQuery))
            .getOrNull()
            ?.singleOrNull()
            ?: return EmbeddingRankResult()

        val vectors = arrayOfNulls<FloatArray>(memories.size)
        val missingIndices = mutableListOf<Int>()
        memories.forEachIndexed { index, memory ->
            val key = embeddingKey(connection, memory)
            val hot = synchronized(embeddingCache) { embeddingCache[key] }
            val persisted = hot ?: MemoryEmbeddingIndex.get(key)
            if (persisted == null) {
                missingIndices += index
            } else {
                vectors[index] = persisted
                synchronized(embeddingCache) { embeddingCache[key] = persisted }
            }
        }

        // Fill the complete valid-memory index in bounded requests. Failed batches remain missing
        // and can be retried on a later recall; already-persisted vectors still participate now.
        missingIndices.chunked(EMBEDDING_BATCH_SIZE).forEach { batch ->
            val inputs = batch.map { memoryIndex -> memories[memoryIndex].content }
            val embedded = com.jiacimu.lulu.ai.LuluAiServices.gateway
                .embed(connection, inputs)
                .getOrNull()
                ?.takeIf { result -> result.size == inputs.size }
                ?: return@forEach
            batch.forEachIndexed { position, memoryIndex ->
                val vector = embedded[position]
                val memory = memories[memoryIndex]
                val key = embeddingKey(connection, memory)
                vectors[memoryIndex] = vector
                synchronized(embeddingCache) { embeddingCache[key] = vector }
                MemoryEmbeddingIndex.put(key, vector)
            }
        }

        val similarities = memories.mapIndexedNotNull { index, memory ->
            vectors[index]?.let { vector -> memory.id to cosine(queryVector, vector) }
        }.toMap()
        val ranked = memories
            .asSequence()
            .filter { memory ->
                memory.pinned || (similarities[memory.id] ?: -1.0) >= MIN_VECTOR_SIMILARITY
            }
            .sortedWith(
                compareByDescending<MemoryEntry>(MemoryEntry::pinned)
                    .thenByDescending { memory -> similarities[memory.id] ?: -1.0 }
                    .thenByDescending { memory -> memory.occurredAt ?: memory.createdAt },
            )
            .take(VECTOR_CANDIDATES)
            .toList()
        return EmbeddingRankResult(ranked, similarities)
    }

    private fun embeddingKey(connection: ModelConnection, memory: MemoryEntry): String = buildString {
        append(connection.baseUrl.trimEnd('/'))
        append('|')
        append(connection.model)
        append('|')
        append(memory.id)
        append('|')
        append(memory.content.length)
        append('|')
        append(memory.content.hashCode())
    }

    private fun fuseRankings(
        memories: List<MemoryEntry>,
        rankings: List<Pair<List<MemoryEntry>, Double>>,
    ): List<MemoryEntry> {
        if (rankings.all { (items, _) -> items.isEmpty() }) return emptyList()
        val scores = mutableMapOf<String, Double>()
        rankings.forEach { (items, weight) ->
            items.forEachIndexed { index, memory ->
                val contribution = weight / (RRF_K + index + 1.0)
                scores[memory.id] = scores.getOrDefault(memory.id, 0.0) + contribution
            }
        }
        return memories
            .filter { memory -> memory.id in scores }
            .sortedWith(
                compareByDescending<MemoryEntry> { memory -> memory.pinned }
                    .thenByDescending { memory -> scores[memory.id] ?: 0.0 }
                    .thenByDescending { memory -> memory.strength }
                    .thenByDescending { memory -> memory.occurredAt ?: memory.createdAt },
            )
    }

    private fun hasLexicalSignal(
        memory: MemoryEntry,
        queryTerms: Set<String>,
        cleanQuery: String,
        now: Instant,
    ): Boolean {
        if (memory.pinned) return true
        val memoryTerms = terms(memory.content)
        if (queryTerms.intersect(memoryTerms).isNotEmpty()) return true
        return hasTemporalIntent(cleanQuery) && recentTemporalMatch(memory, now)
    }

    private fun recentTemporalMatch(memory: MemoryEntry, now: Instant): Boolean {
        val ageDays = Duration.between(memory.occurredAt ?: memory.createdAt, now)
            .toDays()
            .coerceAtLeast(0)
        return ageDays <= 3L
    }

    private fun score(
        memory: MemoryEntry,
        queryTerms: Set<String>,
        cleanQuery: String,
        now: Instant,
    ): Double {
        val memoryTerms = terms(memory.content)
        val overlap = if (queryTerms.isEmpty() || memoryTerms.isEmpty()) 0.0 else {
            queryTerms.intersect(memoryTerms).size.toDouble() /
                queryTerms.union(memoryTerms).size.coerceAtLeast(1).toDouble()
        }
        val exactBoost = queryTerms.count { term ->
            term.length >= 2 && memory.content.contains(term, ignoreCase = true)
        }.coerceAtMost(5) * 0.72
        val strengthBoost = memory.strength.coerceIn(1, 10) / 10.0
        val ageDays = Duration.between(memory.occurredAt ?: memory.createdAt, now)
            .toDays()
            .coerceAtLeast(0)
        val recencyBoost = 1.0 / (1.0 + ln(2.0 + ageDays.toDouble()))
        val temporalIntentBoost = if (hasTemporalIntent(cleanQuery)) {
            when (ageDays) {
                0L -> 1.25
                1L -> 1.1
                2L -> 0.8
                3L -> 0.55
                else -> 0.0
            }
        } else {
            0.0
        }
        val pinnedBoost = if (memory.pinned) 3.0 else 0.0
        return overlap * 8.5 + exactBoost + strengthBoost + recencyBoost + temporalIntentBoost + pinnedBoost
    }

    /**
     * Long generic response instructions can dominate embeddings/rerank and drown out the actual
     * user topic. Prefer explicit current-user lines and a compact recent semantic window.
     */
    private fun focusQuery(raw: String): String {
        val lines = raw
            .lineSequence()
            .map(String::trim)
            .filter(String::isNotBlank)
            .toList()
        if (lines.isEmpty()) return raw.trim().takeLast(MAX_QUERY_CHARS)

        val userLines = lines.filter(::looksLikeUserLine)
        val selected = if (userLines.isNotEmpty()) {
            userLines.takeLast(8)
        } else {
            lines.filterNot(::looksLikeInstructionLine).takeLast(12)
        }
        return selected
            .joinToString("\n")
            .takeLast(MAX_QUERY_CHARS)
            .ifBlank { raw.trim().takeLast(MAX_QUERY_CHARS) }
    }

    private fun looksLikeUserLine(line: String): Boolean {
        val lower = line.lowercase()
        return line.startsWith("用户：") ||
            line.startsWith("用户:") ||
            line.startsWith("你：") ||
            line.startsWith("当前用户") ||
            line.startsWith("用户本轮") ||
            line.startsWith("本轮用户") ||
            line.startsWith("这一刻用户") ||
            line.startsWith("用户刚刚") ||
            line.startsWith("当前输入") ||
            lower.startsWith("user:") ||
            lower.startsWith("user：")
    }

    private fun looksLikeInstructionLine(line: String): Boolean {
        val normalized = line.removePrefix("-").trim()
        return normalized.startsWith("请") ||
            normalized.startsWith("必须") ||
            normalized.startsWith("不要") ||
            normalized.startsWith("不得") ||
            normalized.startsWith("回复") ||
            normalized.startsWith("输出") ||
            normalized.startsWith("保持") ||
            normalized.startsWith("只输出") ||
            normalized.startsWith("以角色") ||
            normalized.startsWith("你需要") ||
            normalized.startsWith("规则") ||
            normalized.startsWith("instruction", ignoreCase = true)
    }

    private fun hasTemporalIntent(text: String): Boolean = TEMPORAL_WORDS.any(text::contains)

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
        return if (leftNorm == 0.0 || rightNorm == 0.0) -1.0 else {
            dot / kotlin.math.sqrt(leftNorm * rightNorm)
        }
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

    private val TEMPORAL_WORDS = listOf(
        "今天", "昨天", "前天", "昨晚", "昨夜", "今早", "刚才", "刚刚", "之前", "最近", "那天", "上次",
    )

    private const val LEXICAL_CANDIDATES = 64
    private const val VECTOR_CANDIDATES = 64
    private const val RECENT_CANDIDATES = 24
    private const val RERANK_POOL = 56
    private const val EMBEDDING_BATCH_SIZE = 24
    private const val MAX_QUERY_CHARS = 1800
    private const val MAX_MEMORY_CHARS = 520
    private const val MAX_EMBEDDING_CACHE = 600
    private const val RRF_K = 50.0
    private const val MIN_RELEVANCE_SCORE = 0.72
    private const val MIN_VECTOR_SIMILARITY = 0.34
}
