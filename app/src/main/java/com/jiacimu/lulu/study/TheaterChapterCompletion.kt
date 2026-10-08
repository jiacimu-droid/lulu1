package com.jiacimu.lulu.study

/**
 * Long-form models can return a valid HTTP 200 with an unfinished chapter.
 * Keep a separate hidden completion marker so a natural sentence-ending
 * punctuation mark cannot be mistaken for the end of the planned chapter.
 */
internal object TheaterChapterCompletion {
    const val END_MARKER = "【本章正文结束】"
    const val MAX_CONTINUATIONS = 3

    fun clean(raw: String): String = raw.substringBefore(END_MARKER).trim()

    fun cutOffByTokens(finishReason: String?): Boolean {
        val reason = finishReason?.lowercase()?.trim().orEmpty()
        return reason == "length" || reason == "max_tokens" ||
            reason == "max_output_tokens" || reason == "model_length" ||
            reason == "output_limit" || reason == "token_limit"
    }

    fun isFinished(raw: String, finishReason: String?): Boolean =
        raw.contains(END_MARKER) && !cutOffByTokens(finishReason)

    /**
     * A follow-up may repeat the last part of the existing answer rather than
     * starting at the exact next character. Remove suffix/prefix overlap only,
     * never globally replace recurring prose or dialogue.
     */
    fun append(existing: String, continuation: String): String {
        val previous = existing.trimEnd()
        val incoming = continuation.trimStart()
        if (previous.isBlank()) return incoming.trim()
        if (incoming.isBlank()) return previous

        val limit = minOf(previous.length, incoming.length, 800)
        var overlap = 0
        for (size in limit downTo 3) {
            if (previous.regionMatches(previous.length - size, incoming, 0, size)) {
                overlap = size
                break
            }
        }

        val addition = incoming.drop(overlap)
        if (addition.isBlank()) return previous
        // Keep a half-finished sentence joined directly; a new paragraph may
        // begin after a properly punctuated ending.
        val endsSentence = previous.lastOrNull() in setOf('。', '！', '？', '!', '?', '.', '”', '’', '」', '』')
        val separator = if (overlap == 0 && endsSentence && !existing.endsWith("\n")) "\n\n" else ""
        return previous + separator + addition
    }
}
