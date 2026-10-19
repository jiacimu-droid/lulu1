package com.jiacimu.lulu

/**
 * Approximate caption reveal, started only when PCM becomes audible.
 * Visual presentation does not alter TTS, stored speech, or the transcript.
 */
internal object PhoneSubtitleProgress {
    fun pauseBeforeNextLine(line: String): Long {
        // Natural speech is slower than text streaming, especially with emotional
        // pauses. Do not rush through five sentences while audio is on sentence two.
        val count = line.count { !it.isWhitespace() }
        val punctuationPause = line.count { it in "。！？!?；;" } * 260L +
            line.count { it in "，," } * 110L
        return (count * 270L + punctuationPause).coerceIn(700L, 16_000L)
    }
}
