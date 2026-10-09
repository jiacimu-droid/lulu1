package com.jiacimu.lulu

/** Pure gate: silence is permission to continue, not evidence of a user reply. */
internal object SleepCallContinuationPolicy {
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
