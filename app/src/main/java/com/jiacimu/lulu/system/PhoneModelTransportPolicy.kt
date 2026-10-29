package com.jiacimu.lulu.system

/**
 * Receiving a model response incrementally is not the same as publishing partial
 * speech. Calls can use SSE while keeping unvalidated JSON/private fields away
 * from subtitles and TTS until the whole character decision has been checked.
 *
 * The callback is deliberately optional. Phone playback is committed only after
 * LuluDeviceToolBridge has validated the full decision.
 */
internal object PhoneModelTransportPolicy {
    fun shouldStream(
        sceneContext: String,
        separateExpression: Boolean,
        disputeNeedsReview: Boolean,
        hasSafeStreamConsumer: Boolean,
    ): Boolean =
        !separateExpression && !disputeNeedsReview &&
            (sceneContext.contains("电话") || hasSafeStreamConsumer)
}
