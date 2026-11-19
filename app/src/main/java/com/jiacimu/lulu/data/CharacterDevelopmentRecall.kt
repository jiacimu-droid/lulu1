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
        val candidates = currentRecords(records, now).map { record ->
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

    /** Keep a detail about the owner in view beyond the current motive's query. */
    fun selectOwnerDetails(
        characterId: String,
        records: List<DevelopmentRecord>,
        query: String,
        now: Instant,
        cadenceSeconds: Long,
    ): List<DevelopmentRecord> {
        val ownerKinds = setOf(DevelopmentKind.UserPreference, DevelopmentKind.UserHabit,
            DevelopmentKind.UserFact, DevelopmentKind.UserImpression)
        val ownerRecords = records.filter { it.characterId == characterId && it.kind in ownerKinds }
        val relevant = select(ownerRecords, query, 4, now)
        if (relevant.size < 4) return relevant

        val primary = relevant.take(3)
        val independent = currentRecords(ownerRecords, now)
            .filter { it.kind != DevelopmentKind.UserImpression && it !in primary }
            .sortedBy { it.id }
        if (independent.isEmpty()) return relevant
        val pulse = Math.floorDiv(now.epochSecond, cadenceSeconds.coerceAtLeast(1))
        val index = Math.floorMod(pulse, independent.size.toLong()).toInt()
        return primary + independent[index]
    }

    private fun currentRecords(records: List<DevelopmentRecord>, now: Instant): List<DevelopmentRecord> =
        records.filter { it.active && !it.createdAt.isAfter(now) }
            .sortedWith(compareByDescending<DevelopmentRecord> { it.version }.thenByDescending { it.createdAt })
            .distinctBy { it.kind to it.slot }
            .distinctBy { it.content.lowercase().filter(Char::isLetterOrDigit) }

    private data class Candidate(val record: DevelopmentRecord, val terms: Set<String>, val score: Double)
}
