package com.jiacimu.lulu

/**
 * Android SpeechRecognizer is a sequence of short provider-owned sessions,
 * not a reliable continuous stream. Keep the text across session boundaries
 * and delay committing it just enough for natural breathing gaps.
 */
internal object SystemSpeechTurnPolicy {
    /** A microphone mute is an explicit end-of-turn signal. Keep every real
     * recognized word before cancelling Android SpeechRecognizer.
     */
    fun wordsToSubmitOnMute(committed: String, inFlightPartial: String): String {
        val earlier = committed.trim()
        val partial = inFlightPartial.trim()
        // Android may repeat the same recognized phrase in a final result
        // and the pending interim callback. It is ONE audio phrase, unlike
        // adjacent independent PCM chunks where identical words can be real.
        return when {
            earlier.isBlank() -> partial
            partial.isBlank() || earlier == partial || earlier.endsWith(partial) -> earlier
            partial.startsWith(earlier) -> partial
            else -> PhoneTranscriptAssembler.combine(earlier, partial)
        }
    }

    /**
     * Phone SpeechRecognizer is provider-owned: we cannot preprocess its PCM or
     * force it to return a complete transcript. Preserve the best interim
     * hypothesis when a later callback unexpectedly loses the beginning.
     */
    fun bestPartial(previous: String, latest: String): String {
        val earlier = previous.trim()
        val current = latest.trim()
        if (current.isBlank()) return earlier
        if (earlier.isBlank() || current.length >= earlier.length) return current
        // Genuine recognition corrections can replace previous text; only
        // retain older content when the new text is clearly a truncated subset.
        return if (earlier.contains(current) && earlier.length - current.length >= 3) earlier else current
    }

    fun bestFinal(partial: String, final: String): String {
        val earlier = partial.trim()
        val current = final.trim()
        if (earlier.isBlank()) return current
        if (current.isBlank()) return earlier
        if (earlier == current) return current
        if (earlier.length > current.length && earlier.contains(current)) return earlier
        if (current.contains(earlier)) return current
        // The provider sometimes returns only a revised ending of a long
        // hypothesis. Join at a verified multi-character boundary; never use
        // mere similar meaning to concatenate unrelated alternatives.
        val overlap = (minOf(earlier.length, current.length, 24) downTo 3)
            .firstOrNull { size -> earlier.endsWith(current.take(size)) &&
                earlier.takeLast(size).toSet().size > 1 } ?: 0
        return if (overlap > 0) earlier + current.drop(overlap) else current
    }

    /** A recoverable SpeechRecognizer error must not erase a real interim phrase. */
    fun canKeepPartialOnError(confirmedSpeech: Boolean, partial: String): Boolean =
        confirmedSpeech && partial.trim().length >= 2

    /** Natural long explanations need more breathing space than one-word replies.
     * This is measured after a final transcript, NOT after each partial callback.
     */
    fun continuationMillis(transcript: String): Long {
        val text = transcript.trim()
        return when {
            text.length <= 2 -> 900L
            text.length <= 12 -> 1_600L
            text.length <= 32 -> 2_400L
            else -> 3_200L
        }
    }

    /** Android system recognizers may capture the character's own TTS.
     * Genuine two-way speech recognition is therefore enabled only with
     * private audio output (earpiece or headset), never the loudspeaker.
     * Listening while a reply is being *generated* is safe in either mode.
     */
    fun canListen(
        connected: Boolean,
        muted: Boolean,
        opening: Boolean,
        thinking: Boolean,
        speakingOrPending: Boolean,
        privateAudioOutput: Boolean,
    ): Boolean = connected && !muted && !opening &&
        (!speakingOrPending || privateAudioOutput)

    fun shouldInterrupt(
        detectedUserSpeech: Boolean,
        thinking: Boolean,
        speakingOrPending: Boolean,
        privateAudioOutput: Boolean,
    ): Boolean = detectedUserSpeech && (thinking ||
        (speakingOrPending && privateAudioOutput))
}
