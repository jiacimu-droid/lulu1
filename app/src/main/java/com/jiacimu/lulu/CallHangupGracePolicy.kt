package com.jiacimu.lulu

/**
 * The goodbye is a decision, not an instantly executed command.
 *
 * Give the listener six full seconds AFTER audible playback to change her
 * mind. A recognizer may need extra time to confirm a quietly spoken "等等",
 * so a plausible in-flight speech capture postpones the hangup, but never
 * indefinitely. Only real text/typed user input cancels permanently.
 */
internal object CallHangupGracePolicy {
    const val RESPONSE_WINDOW_MS = 6_000L
    const val MAX_SPEECH_RECOGNITION_WINDOW_MS = 18_000L

    fun shouldWait(elapsedMs: Long, recognitionInProgress: Boolean): Boolean {
        if (elapsedMs < RESPONSE_WINDOW_MS) return true
        return recognitionInProgress && elapsedMs < MAX_SPEECH_RECOGNITION_WINDOW_MS
    }
}
