package com.jiacimu.lulu

/**
 * Approximate caption reveal, started only when PCM becomes audible.
 * Visual presentation does not alter TTS, stored speech, or the transcript.
 */
internal object PhoneSubtitleProgress {
    fun pauseBeforeNextLine(line: String): Long {
        // Reveal each sentence at a conversational pace. Sentence-level segmentation
        // (PhoneSubtitleLayout.liveLines) prevents revealing sentence five during
        // sentence two; slightly leading the voice is acceptable.
        val count = line.count { !it.isWhitespace() }
        val punctuationPause = line.count { it in "。！？!?；;" } * 200L +
            line.count { it in "，," } * 100L
        return (count * 240L + punctuationPause).coerceIn(650L, 14_000L)
    }
}
