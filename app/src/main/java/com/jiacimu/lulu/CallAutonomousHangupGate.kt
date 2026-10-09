package com.jiacimu.lulu

/**
 * Hangup is an executable call-control decision, not something inferred from
 * a spoken "goodbye". The utterance may stream or be queued in several parts.
 * A role can hang up only once the current reply is complete and audio is idle.
 */
internal class CallAutonomousHangupGate {
    private var pendingCallId: String = ""
    private var pendingGeneration: Long = -1L

    fun request(callId: String, generation: Long) {
        if (callId.isBlank()) return
        pendingCallId = callId
        pendingGeneration = generation
    }

    fun cancel() {
        pendingCallId = ""
        pendingGeneration = -1L
    }

    fun consumeWhenReady(
        callId: String, generation: Long, connected: Boolean,
        generating: Boolean, audioBusy: Boolean,
    ): Boolean {
        if (callId != pendingCallId || generation != pendingGeneration) return false
        if (!connected) { cancel(); return false }
        if (generating || audioBusy) return false
        cancel()
        return true
    }
}
