package com.jiacimu.lulu

/** Assemble independently transcribed PCM chunks without discarding repeated Chinese syllables. */
internal object PhoneTranscriptAssembler {
    fun combine(earlier: String, latest: String): String {
        val left = earlier.trim()
        val right = latest.trim()
        if (left.isBlank()) return right
        if (right.isBlank()) return left
        // No overlap removal: consecutive chunks have no overlapping audio,
        // and repeated "哈、哈、哈" must not collapse to one character.
        val needsSpace = left.last().isLetterOrDigit() && right.first().isLetterOrDigit() &&
            left.last().code < 128 && right.first().code < 128
        return left + if (needsSpace) " $right" else right
    }
}
