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

    /** Use ONLY when two PCM uploads intentionally share audio at the boundary.
     * Require a nontrivial, varied phrase so repeated laughter/stutters survive.
     */
    fun combineOverlapped(earlier: String, latest: String): String {
        val left = earlier.trim()
        val right = latest.trim()
        if (left.isBlank() || right.isBlank()) return combine(left, right)
        val maximum = minOf(48, left.length, right.length)
        val shared = (maximum downTo 4).firstOrNull { size ->
            left.regionMatches(left.length - size, right, 0, size) &&
                left.takeLast(size).toSet().size >= 3
        } ?: 0
        // Once an audible overlap is proved, the surviving suffix may continue
        // the same Latin word ("hello wor" + "lo world" => "hello world").
        // Do not insert combine()'s normal word-boundary space at this point.
        return if (shared > 0) left + right.drop(shared) else combine(left, right)
    }
}
