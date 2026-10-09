package com.jiacimu.lulu

/**
 * Opt-in bedtime pacing. Lack of speech is NOT proof the listener is asleep.
 * Progressive quiet pauses save needless model/TTS calls and the call
 * eventually ends without demanding confirmation from the listener.
 */
internal object SleepCallContinuationPolicy {
    const val FIRST_QUIET_AT_MILLIS = 6 * 60_000L
    const val DEEP_QUIET_AT_MILLIS = 12 * 60_000L
    const val AUTO_END_AT_MILLIS = 20 * 60_000L

    fun nextPauseMillis(sinceUserReplyMillis: Long): Long = when {
        sinceUserReplyMillis >= DEEP_QUIET_AT_MILLIS -> 35_000L
        sinceUserReplyMillis >= FIRST_QUIET_AT_MILLIS -> 12_000L
        else -> 1_900L
    }

    fun shouldQuietlyEnd(
        sinceUserReplyMillis: Long,
        connected: Boolean,
        sleepMode: Boolean,
        userSpeaking: Boolean,
        audioInProgress: Boolean,
    ): Boolean = connected && sleepMode &&
        sinceUserReplyMillis >= AUTO_END_AT_MILLIS &&
        !userSpeaking && !audioInProgress

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
