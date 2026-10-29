package com.jiacimu.lulu

/**
 * Opt-in bedtime pacing. Lack of speech is NOT proof the listener is asleep.
 * Progressive quiet pauses save needless model/TTS calls and the call
 * eventually ends without demanding confirmation from the listener.
 */
internal object SleepCallContinuationPolicy {
    const val FIRST_QUIET_AT_MILLIS = 6 * 60_000L
    const val DEEP_QUIET_AT_MILLIS = 12 * 60_000L
    const val SPARSE_QUIET_AT_MILLIS = 20 * 60_000L
    const val NEAR_SLEEP_AT_MILLIS = 27 * 60_000L
    const val AUTO_END_AT_MILLIS = 30 * 60_000L

    fun nextPauseMillis(sinceUserReplyMillis: Long, story: Boolean = false): Long = if (story) 2_500L else when {
        sinceUserReplyMillis >= NEAR_SLEEP_AT_MILLIS -> 24_000L
        sinceUserReplyMillis >= SPARSE_QUIET_AT_MILLIS -> 12_000L
        sinceUserReplyMillis >= DEEP_QUIET_AT_MILLIS -> 6_000L
        sinceUserReplyMillis >= FIRST_QUIET_AT_MILLIS -> 3_500L
        else -> 2_000L
    }

    /** An incomplete audio segment is not a delivered narration.
     * One same-text retry prevents a brief provider error from jumping
     * straight to a freshly generated story. Further failures back off. */
    fun shouldRetryFailedAudio(enabled: Boolean, remainingAttempts: Int): Boolean =
        enabled && remainingAttempts > 0

    /** A verified microphone turn waits for bedtime narration instead of
     * resetting its active TTS queue. Ordinary chat retains barge-in. */
    fun shouldDeferUserWords(sleepMode: Boolean, audibleSpeech: Boolean, text: String): Boolean =
        sleepMode && audibleSpeech && text.isNotBlank()

    /** Errors are retried conservatively; they never shorten the 30-minute
     * call deadline. Long stories get the same bounded retry protection. */
    fun nextPauseAfterFailureMillis(
        sinceUserReplyMillis: Long,
        story: Boolean = false,
        consecutiveFailures: Int,
    ): Long {
        val cooldown = when {
            consecutiveFailures <= 0 -> 0L
            consecutiveFailures == 1 -> 10_000L
            consecutiveFailures == 2 -> 20_000L
            consecutiveFailures == 3 -> 40_000L
            else -> 90_000L
        }
        return maxOf(nextPauseMillis(sinceUserReplyMillis, story), cooldown)
    }

    /** A hard, monotonic timeout; recognition, TTS, wake plans or narration
     * errors may not keep the paid microphone connection open indefinitely.
     * Null means this is an ordinary phone call without a sleep deadline. */
    fun shouldEndAtDeadline(
        deadlineElapsedMillis: Long?,
        nowElapsedMillis: Long,
        connected: Boolean,
    ): Boolean = connected && deadlineElapsedMillis != null &&
        nowElapsedMillis >= deadlineElapsedMillis

    fun canContinue(
        connected: Boolean,
        sleepMode: Boolean,
        thinking: Boolean,
        speaking: Boolean,
        opening: Boolean,
        userSpeaking: Boolean,
        realtime: Boolean,
    ): Boolean = connected && sleepMode && !thinking && !speaking &&
        !opening && !userSpeaking && !realtime
}
