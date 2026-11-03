package com.jiacimu.lulu.data

/** An exact view into the original event, never a replacement for its durable timeline. */
internal data class OriginalEvidenceSpan(
    val start: Int,
    val endExclusive: Int,
    val text: String,
    val relevance: Int,
)

/**
 * Bounded candidate ranges. Search tokens rank raw excerpts, not user intent or action choice.
 * The head, middle and tail remain discoverable when the current query has no lexical overlap.
 */
internal object OriginalEvidenceSpans {
    private const val CHUNK_SIZE = 900
    private const val OVERLAP = 120

    fun ranked(content: String, focus: String, maxSpans: Int = 12): List<OriginalEvidenceSpan> {
        if (content.isEmpty() || maxSpans <= 0) return emptyList()
        if (content.length <= CHUNK_SIZE)
            return listOf(OriginalEvidenceSpan(0, content.length, content, 0))

        val chunks = mutableListOf<OriginalEvidenceSpan>()
        var start = 0
        while (start < content.length) {
            val end = minOf(content.length, start + CHUNK_SIZE)
            chunks += OriginalEvidenceSpan(start, end, content.substring(start, end), 0)
            if (end == content.length) break
            start = end - OVERLAP
        }
        val terms = memorySearchTerms(focus.take(2_000))
        val scored = chunks.map { span ->
            span.copy(relevance = if (terms.isEmpty()) 0 else
                terms.intersect(memorySearchTerms(span.text)).size)
        }
        val anchors = listOf(scored.first(), scored[scored.size / 2], scored.last())
        val matches = scored.filter { it.relevance > 0 }
            .sortedWith(compareByDescending<OriginalEvidenceSpan> { it.relevance }.thenBy { it.start })
        return (matches.take((maxSpans - anchors.size).coerceAtLeast(0)) + anchors + matches + scored)
            .distinctBy { it.start }.take(maxSpans)
    }
}
