package com.jiacimu.lulu.data

import java.time.Duration
import java.time.Instant

/** A bounded working set over the existing, source-validated growth store. No new memory or API. */
internal object CharacterDevelopmentRecall {
    fun select(
        records: List<DevelopmentRecord>,
        query: String,
        limit: Int,
        now: Instant = Instant.now(),
    ): List<DevelopmentRecord> {
        if (limit <= 0) return emptyList()
        val queryTerms = memorySearchTerms(query.take(4_000))
        val candidates = records.filter { it.active && !it.createdAt.isAfter(now) }
            .sortedWith(compareByDescending<DevelopmentRecord> { it.version }.thenByDescending { it.createdAt })
            .distinctBy { it.kind to it.slot }
            .distinctBy { it.content.lowercase().filter(Char::isLetterOrDigit) }
            .map { record ->
                val terms = memorySearchTerms("${record.slot} ${record.content}")
                val relevance = queryTerms.intersect(terms).sumOf { if (it.length >= 3) 1.5 else 1.0 }
                    .coerceAtMost(12.0)
                val age = Duration.between(record.createdAt, now).toHours().coerceAtLeast(0) / 24.0
                val maturity = if (record.maturity == DevelopmentMaturity.Established) 1.5 else 0.0
                val confidence = record.confidence.takeIf { it.isFinite() }?.coerceIn(0.0, 1.0) ?: 0.0
                Candidate(record, terms, relevance * 4.0 + maturity + confidence + 1.0 / (1.0 + age / 30.0))
            }.toMutableList()
        val chosen = mutableListOf<Candidate>()
        while (candidates.isNotEmpty() && chosen.size < limit) {
            // Related old experience outranks unrelated recency. Soft diversity avoids one
            // repeatedly paraphrased habit consuming the entire personality working set.
            val best = candidates.maxWithOrNull(compareBy<Candidate> { candidate ->
                val redundancy = chosen.maxOfOrNull { previous ->
                    candidate.terms.intersect(previous.terms).size.toDouble() /
                        candidate.terms.union(previous.terms).size.coerceAtLeast(1)
                } ?: 0.0
                candidate.score - redundancy * 2.0 - chosen.count { it.record.kind == candidate.record.kind } * 0.35
            }.thenBy { it.record.createdAt }.thenBy { it.record.id }) ?: break
            chosen += best
            candidates.remove(best)
        }
        return chosen.map { it.record }
    }

    private data class Candidate(val record: DevelopmentRecord, val terms: Set<String>, val score: Double)
}
