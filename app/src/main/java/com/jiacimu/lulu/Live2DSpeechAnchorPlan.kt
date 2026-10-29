package com.jiacimu.lulu

/**
 * Exact-text visual beats are boundaries in the audible speech queue, not
 * guesses at word timestamps. Ordinary replies keep their original TTS shape.
 * At most fifteen additional boundaries are permitted in one visual turn.
 * Every boundary comes from an exact phrase chosen by the same role model.
 */
internal object Live2DSpeechAnchorPlan {
    data class Plan(val parts: List<String>, val segmentForAnchor: Map<String, Int>)

    private const val BOUNDARY_CHARS = "。！？!?；;，,：:\n \t"
    private const val STRIP_CHARS = "。！？!?；;，,：:、·…— \t\n\r"
    private val voicePrefix = Regex("(?:\\[[A-Za-z][A-Za-z0-9 ,.'’:_/!()?+\\-]{0,119}\\]\\s*)+$")

    // Preserve audio directions with the clause they describe. Otherwise
    // [warmly]你好 either fails the boundary check or creates a tag-only TTS.
    private fun cueStart(speech: String, position: Int): Int =
        voicePrefix.find(speech.substring(0, position))?.range?.first ?: position

    private fun atBoundary(speech: String, position: Int): Boolean =
        position == 0 || speech[position - 1] in BOUNDARY_CHARS

    /**
     * Punctuation and spacing routinely drift between the anchor the role
     * authored and the final spoken text (a comma the spoken text has, the
     * anchor lacks). Compare with clause punctuation and whitespace removed
     * from both sides; the anchor must still be fully present, occur exactly
     * once, and start at a real clause boundary. Anything else stays a
     * non-match: this fallback never guesses a timestamp either.
     */
    private fun fallbackPosition(speech: String, anchor: String): Int? {
        val normalizedAnchor = anchor.filter { it !in STRIP_CHARS }
        if (normalizedAnchor.isEmpty()) return null
        val origin = ArrayList<Int>(speech.length)
        val normalized = StringBuilder(speech.length)
        for (index in speech.indices) {
            if (speech[index] in STRIP_CHARS) continue
            origin.add(index)
            normalized.append(speech[index])
        }
        val text = normalized.toString()
        val first = text.indexOf(normalizedAnchor)
        if (first < 0 || text.indexOf(normalizedAnchor, first + normalizedAnchor.length) >= 0)
            return null
        val position = cueStart(speech, origin[first])
        return if (atBoundary(speech, position)) position else null
    }

    /**
     * Already-prepared TTS segments must not be split or re-synthesized just
     * to animate. Bind only a unique, literal clause-start match to its
     * actually queued fragment; never invent a timestamp within a fragment.
     */
    fun bindExistingSegments(parts: List<String>, anchors: List<String>): Map<String, Int> =
        anchors.distinct().mapNotNull { anchor ->
            val matching = parts.indices.filter { index ->
                val plan = build(parts[index], listOf(anchor))
                plan.segmentForAnchor[anchor] == 0
            }
            if (matching.size == 1) anchor to matching.single() else null
        }.toMap()

    /** Normal phone replies are already streamed into sentence-sized TTS
     * chunks. Align the same decision's anchors to the original reply, but
     * never match audio that has been rewritten or is only partly present. */
    fun fromQueuedParts(speech: String, queued: List<String>, anchors: List<String>): Plan? {
        if (anchors.isEmpty() || queued.isEmpty() || queued.joinToString("") != speech)
            return null
        return build(speech, anchors)
    }

    fun build(speech: String, anchors: List<String>): Plan {
        if (speech.isBlank()) return Plan(emptyList(), emptyMap())
        val distinct = anchors.distinct()
        val primary = distinct.mapNotNull { anchor ->
            if (anchor.isBlank() || anchor.length > 60) return@mapNotNull null
            val literalPosition = speech.indexOf(anchor)
            if (literalPosition < 0 || speech.indexOf(anchor, literalPosition + anchor.length) >= 0)
                return@mapNotNull null
            val position = cueStart(speech, literalPosition)
            // Don't split a TTS word or fabricate a time for a quote that
            // doesn't begin at a real clause boundary in the final spoken text.
            if (!atBoundary(speech, position)) return@mapNotNull null
            anchor to position
        }
        val found = if (primary.size == distinct.size) primary else {
            val done = primary.map { it.first }.toSet()
            primary + distinct.filter { it !in done }.mapNotNull { anchor ->
                if (anchor.isBlank() || anchor.length > 60) return@mapNotNull null
                fallbackPosition(speech, anchor)?.let { position -> anchor to position }
            }
        }.sortedBy { it.second }
        val boundaries = (listOf(0) + found.map { it.second }.filter { it > 0 }.distinct().take(15))
            .sorted()
        val parts = boundaries.indices.map { index ->
            speech.substring(boundaries[index],
                if (index + 1 < boundaries.size) boundaries[index + 1] else speech.length).trim()
        }
        val segmentForAnchor = found.mapNotNull { (anchor, position) ->
            val index = boundaries.indexOf(position)
            if (index >= 0) anchor to index else null
        }.toMap()
        return Plan(parts, segmentForAnchor)
    }
}
