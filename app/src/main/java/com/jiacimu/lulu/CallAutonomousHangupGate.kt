package com.jiacimu.lulu

/**
 * Hangup is an executable call-control decision, not something inferred from
 * a spoken "goodbye". The utterance may stream or be queued in several parts.
 * A role can hang up only once the current reply is complete and audio is idle.
 */
internal class CallAutonomousHangupGate {
    private var pendingCallId: String = ""
    private var pendingGeneration: Long = -1L
    private var deliveredCallId: String = ""
    private var deliveredGeneration: Long = -1L
    private var expectedSpeech: String = ""
    private var deliveredSpeech: String = ""

    // Compare actual saved voice segments with the planner's finished words.
    // No first-bubble receipt can authorize hanging up before a final bubble.
    private fun digest(value: String): String =
        VoicePerformance.plain(value).filter(Char::isLetterOrDigit)

    fun expectSpoken(callId: String, generation: Long, completeText: String) {
        if (callId == pendingCallId && generation == pendingGeneration)
            expectedSpeech = digest(completeText)
    }

    fun markDelivered(callId: String, generation: Long, part: String = "") {
        if (deliveredCallId != callId || deliveredGeneration != generation) {
            deliveredSpeech = ""
        }
        deliveredCallId = callId
        deliveredGeneration = generation
        deliveredSpeech += digest(part)
    }

    fun request(callId: String, generation: Long) {
        if (callId.isBlank()) return
        pendingCallId = callId
        pendingGeneration = generation
        expectedSpeech = ""
    }

    fun cancel() {
        pendingCallId = ""
        pendingGeneration = -1L
        deliveredCallId = ""
        deliveredGeneration = -1L
        expectedSpeech = ""
        deliveredSpeech = ""
    }

    fun consumeWhenReady(
        callId: String, generation: Long, connected: Boolean,
        generating: Boolean, audioBusy: Boolean,
    ): Boolean {
        if (callId != pendingCallId || generation != pendingGeneration) return false
        if (!connected) { cancel(); return false }
        if (generating || audioBusy) return false
        // A TTS failure is not a real farewell. The role's unspoken end
        // command must not silently tear down the connection.
        if (deliveredCallId != callId || deliveredGeneration != generation) {
            cancel()
            return false
        }
        // A model may stream the first bubble, promise to hang up in the
        // final bubble, and then fail TTS. Never hang up on the first receipt.
        if (expectedSpeech.isNotBlank() && expectedSpeech != deliveredSpeech) {
            cancel()
            return false
        }
        cancel()
        return true
    }
}
