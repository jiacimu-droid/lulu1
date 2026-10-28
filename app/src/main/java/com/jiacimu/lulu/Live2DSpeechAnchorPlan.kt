package com.jiacimu.lulu

/**
 * Exact-text visual beats are boundaries in the audible speech queue, not
 * guesses at word timestamps. Ordinary replies keep their original TTS shape.
 * At most three additional boundaries are permitted in one visual turn.
 */
internal object Live2DSpeechAnchorPlan {
    data class Plan(val parts: List<String>, val segmentForAnchor: Map<String, Int>)

    fun build(speech: String, anchors: List<String>): Plan {
        if (speech.isBlank()) return Plan(emptyList(), emptyMap())
        val found = anchors.distinct().mapNotNull { anchor ->
            if (anchor.isBlank() || anchor.length > 60) return@mapNotNull null
            val position = speech.indexOf(anchor)
            if (position < 0 || speech.indexOf(anchor, position + anchor.length) >= 0)
                return@mapNotNull null
            // Don't split a TTS word or fabricate a time for a quote that
            // doesn't begin at a real clause boundary in the final spoken text.
            if (position > 0 && speech[position - 1] !in "。！？!?；;，,：:\n \t")
                return@mapNotNull null
            anchor to position
        }.sortedBy { it.second }
        val boundaries = (listOf(0) + found.map { it.second }.filter { it > 0 }.distinct().take(3))
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
