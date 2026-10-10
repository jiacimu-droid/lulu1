package com.jiacimu.lulu

/**
 * Approximate caption reveal, started only when PCM becomes audible.
 * Visual presentation does not alter TTS, stored speech, or the transcript.
 */
internal object PhoneSubtitleProgress {
    fun pauseBeforeNextLine(line: String): Long {
        // A little quicker than typical spoken Chinese so words already said
        // are never hidden by a long exact-timing guess.
        val count = line.count { !it.isWhitespace() }
        return (count * 155L).coerceIn(420L, 3_900L)
    }
}
