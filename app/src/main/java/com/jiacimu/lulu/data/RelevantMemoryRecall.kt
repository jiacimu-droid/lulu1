package com.jiacimu.lulu.data

import com.jiacimu.lulu.LuluRepositories
import com.jiacimu.lulu.ai.ModelConnection
import com.jiacimu.lulu.core.MemoryEntry
import java.time.Duration
import java.time.Instant
import kotlin.math.ln

/**
 * Associative memory recall.
 *
 * Retrieval intentionally combines lexical, vector, recency and pinned signals instead of letting
 * one mechanism replace the others. This matters for natural follow-ups such as an older vague
 * memory ("肚子不舒服") being clarified by a newer utterance ("昨天拉肚子").
 */
object RelevantMemoryRecall {
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
        val queryTerms = terms(cleanQuery)
        val memories = LuluRepositories.memory.snapshot(characterId)
            .asSequence()
            .filter { memory ->
                DigitalLifeProfileStore.allowsTimestamp(
                    characterId,
                    memory.occurredAt ?: memory.createdAt,
                )
            }
            .take(MAX_MEMORY_POOL)
            .toList()
        if (memories.isEmpty()) return emptyList()

        val lexicalRanked = memories
            .asSequence()
            .map { memory -> memory to score(memory, queryTerms, cleanQuery, now) }
            .filter { (memory, score) -> memory.pinned || score > MIN_RELEVANCE_SCORE }
            .sortedWith(
                compareByDescending<Pair<MemoryEntry, Double>> { (memory, _) -> memory.pinned }
                    .thenByDescending { (_, score) -> score }
                    .thenByDescending { (memory, _) -> memory.occurredAt ?: memory.createdAt },
            )
            .take(LEXICAL_CANDIDATES)
            .map { it.first }
            .toList()

        // Recent memories are a weak but important independent lane. A user saying "昨天/前天/刚才"
        // should not lose the relevant event merely because its wording changed substantially.
        val recentRanked = memories
            .sortedByDescending { memory -> memory.occurredAt ?: memory.createdAt }
            .take(RECENT_CANDIDATES)

        val pinnedRanked = memories.filter(MemoryEntry::pinned)

        val vectorPool = buildList {
            addAll(lexicalRanked)
            addAll(recentRanked)
            addAll(pinnedRanked)
            // Keep a broader high-strength/recency tail so vector search can discover genuinely
            // semantic matches that share few or no literal words with the new message.
            addAll(memories.take(VECTOR_POOL_LIMIT))
        }.distinctBy(MemoryEntry::id).take(VECTOR_POOL_LIMIT)

        val vectorRanked = if (MemoryModelRuntime.vectorEnabled()) {
            val connection = MemoryModelRuntime.embeddingConnection()
            if (connection == null || cleanQuery.isBlank() || vectorPool.isEmpty()) emptyList() else {
                rankByEmbedding(connection, cleanQuery, vectorPool)
            }
        } else emptyList()

        // Reciprocal-rank fusion: vector retrieval supplements lexical retrieval rather than
        // replacing it. Recency is deliberately weaker so it helps temporal continuity without
        // flooding the prompt with unrelated recent details.
        var candidates = fuseRankings(
            memories = memories,
            rankings = listOf(
                lexicalRanked to 3.2,
                vectorRanked to 4.0,
                recentRanked to 1.15,
                pinnedRanked to 5.0,
            ),
        ).take(RERANK_POOL)

        if (MemoryModelRuntime.rerankEnabled() && cleanQuery.isNotBlank() && candidates.isNotEmpty()) {
            val connection = MemoryModelRuntime.rerankConnection()
            if (connection != null) {
                val order = com.jiacimu.lulu.ai.LuluAiServices.gateway
                    .rerank(connection, cleanQuery, candidates.map { it.content })
                    .getOrNull()
                if (!order.isNullOrEmpty()) {
                    val reranked = order.mapNotNull { index -> candidates.getOrNull(index) }
                    candidates = fuseRankings(
                        memories = candidates,
                        rankings = listOf(
                            candidates to 2.4,
                            reranked to 5.0,
                        ),
                    ).take(RERANK_POOL)
                }
            }
        }

        val requested = limit.coerceIn(1, 24)
        // Older callers explicitly passed 12. Keep compatibility while raising normal companion
        // recall breadth so associative context has enough room to survive final ranking.
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
                val eventTerms = terms("${event.channel} ${event.speaker} ${event.content}")
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
            "[${event.occurredAt}] [${event.channel}] ${event.speaker}：${event.content.take(1_200)}"
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
                append("] ")
                appendLine(memory.content.take(MAX_MEMORY_CHARS))
            }
        }.trim()
    }

    private suspend fun rankByEmbedding(
        connection: ModelConnection,
        cleanQuery: String,
        memories: List<MemoryEntry>,
    ): List<MemoryEntry> {
        val cachedVectors = arrayOfNulls<FloatArray>(memories.size)
        val missingIndices = mutableListOf<Int>()
        synchronized(embeddingCache) {
            memories.forEachIndexed { index, memory ->
                val cached = embeddingCache[embeddingKey(connection, memory)]
                if (cached == null) missingIndices += index else cachedVectors[index] = cached
            }
        }

        // Query vectors are intentionally not cached: the current utterance changes every turn.
        // Existing memory vectors are cached, so a warm recall normally embeds only the query plus
        // memories that were newly created or edited since the previous turn.
        val inputs = buildList {
            add(cleanQuery)
            missingIndices.forEach { index -> add(memories[index].content) }
        }
        val vectors = com.jiacimu.lulu.ai.LuluAiServices.gateway
            .embed(connection, inputs)
            .getOrNull()
            ?.takeIf { result -> result.size == inputs.size }
            ?: return emptyList()
        val queryVector = vectors.first()
        val newVectors = vectors.drop(1)
        missingIndices.forEachIndexed { position, memoryIndex ->
            val vector = newVectors.getOrNull(position) ?: return@forEachIndexed
            cachedVectors[memoryIndex] = vector
            synchronized(embeddingCache) {
                embeddingCache[embeddingKey(connection, memories[memoryIndex])] = vector
            }
        }

        return memories
            .mapIndexedNotNull { index, memory -> cachedVectors[index]?.let { vector -> memory to vector } }
            .sortedWith(
                compareByDescending<Pair<MemoryEntry, FloatArray>> { pair -> pair.first.pinned }
                    .thenByDescending { pair -> cosine(queryVector, pair.second) },
            )
            .take(VECTOR_CANDIDATES)
            .map { pair -> pair.first }
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
        if (rankings.all { (items, _) -> items.isEmpty() }) return memories.take(RERANK_POOL)
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
     * The gateway historically passed `facts + instruction` as the retrieval query. Long generic
     * response instructions can dominate embeddings/rerank and drown out the actual user topic.
     * Prefer explicit user/current-event lines; otherwise remove obvious instruction boilerplate
     * and keep a compact recent semantic window.
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

    private const val MAX_MEMORY_POOL = 160
    private const val VECTOR_POOL_LIMIT = 96
    private const val LEXICAL_CANDIDATES = 56
    private const val VECTOR_CANDIDATES = 56
    private const val RECENT_CANDIDATES = 28
    private const val RERANK_POOL = 48
    private const val MAX_QUERY_CHARS = 1800
    private const val MAX_MEMORY_CHARS = 520
    private const val MAX_EMBEDDING_CACHE = 600
    private const val RRF_K = 50.0
    private const val MIN_RELEVANCE_SCORE = 0.72
}
