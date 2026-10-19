package com.jiacimu.lulu

/**
 * Approximate caption reveal, started only when PCM becomes audible.
 * Visual presentation does not alter TTS, stored speech, or the transcript.
 */
internal object PhoneSubtitleProgress {
    fun pauseBeforeNextLine(line: String): Long {
        // A more natural Chinese speech rhythm (about 4.3 characters/second).
        // The old 155 ms/character and 3.9 s cap revealed later lines long
        // before slower, expressive voices actually reached them.
        val count = line.count { !it.isWhitespace() }
        return (count * 230L).coerceIn(650L, 12_000L)
    }
}
