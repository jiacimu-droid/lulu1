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

    /** Natural long explanations need more breathing space than one-word replies.
     * This is measured after a final transcript, NOT after each partial callback.
     */
    fun continuationMillis(transcript: String): Long {
        val text = transcript.trim()
        return when {
            text.length <= 2 -> 850L
            text.length <= 12 -> 1_150L
            text.length <= 32 -> 1_650L
            else -> 2_100L
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
