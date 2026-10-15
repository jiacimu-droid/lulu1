package com.jiacimu.lulu

/**
 * One long bedtime reply should remain one transcript and one call turn, but it
 * must not become one enormous PCM WebSocket generation. Split only the AUDIO
 * input at natural speech pauses; the caller keeps one AudioTrack/recording.
 */
internal object ElevenLabsSpeechSegments {
    private const val MAX_CHARS = 480
    private val sentenceEnds = "。！？!?；;\n"
    private val softEnds = "，,、 "

    fun split(text: String): List<String> {
        val source = text.trim()
        if (source.isEmpty()) return emptyList()
        if (source.length <= MAX_CHARS) return listOf(source)

        val parts = mutableListOf<String>()
        var start = 0
        while (start < source.length) {
            val limit = (start + MAX_CHARS).coerceAtMost(source.length)
            if (limit == source.length) {
                source.substring(start).trim().takeIf(String::isNotEmpty)?.let(parts::add)
                break
            }
            var insideTag = false
            var tagStart = -1
            var natural = -1
            var fallback = -1
            for (i in start until limit) {
                when (source[i]) {
                    '[' -> { insideTag = true; tagStart = i }
                    ']' -> { insideTag = false; tagStart = -1 }
                    else -> if (!insideTag && i >= start + MAX_CHARS / 2) {
                        if (source[i] in sentenceEnds) natural = i + 1
                        else if (source[i] in softEnds) fallback = i + 1
                    }
                }
            }
            // Never split an ElevenLabs performance tag such as [long pause].
            val boundary = (if (natural > start) natural else if (fallback > start) fallback
                else if (insideTag && tagStart > start) tagStart else limit)
                .coerceIn(start + 1, limit)
            source.substring(start, boundary).trim().takeIf(String::isNotEmpty)?.let(parts::add)
            start = boundary
            while (start < source.length && source[start].isWhitespace()) start++
        }
        return parts
    }
}
