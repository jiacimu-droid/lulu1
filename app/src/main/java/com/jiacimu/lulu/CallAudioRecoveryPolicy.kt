package com.jiacimu.lulu

/** Local audio-output recovery. Neither a missing playback head nor a TTS timeout
 * authorizes another paid synthesis request. */
internal object CallAudioRecoveryPolicy {
    const val STARTUP_TIMEOUT_MS = 5_000L
    const val DRAIN_STALL_TIMEOUT_MS = 8_000L

    /** Replay only when no frame was heard and every generated PCM byte is present. */
    fun canReplay(playedFrames: Long, pcmBytes: Long, cachedBytes: Long, cacheValid: Boolean): Boolean =
        playedFrames == 0L && pcmBytes > 0L && pcmBytes % 2L == 0L &&
            cacheValid && cachedBytes == pcmBytes
}
