package com.jiacimu.lulu

/**
 * Upload chunks and speech endpoints are different things:
 * an upload chunk ending MUST NOT mean the human has finished talking.
 */
internal object PhoneMicSegmentPolicy {
    const val SAMPLE_RATE = 16_000
    const val MAX_UPLOAD_BYTES = SAMPLE_RATE * 2 * 12

    fun silenceMs(utteranceFrames: Int, configured: Int): Int = when {
        utteranceFrames >= 50 -> configured.coerceIn(1_700, 3_500)
        utteranceFrames >= 20 -> configured.coerceIn(1_200, 3_500)
        else -> configured.coerceIn(850, 3_500)
    }

    fun finishedBySilence(silentFrames: Int, utteranceFrames: Int, configured: Int): Boolean =
        silentFrames >= (silenceMs(utteranceFrames, configured) + 99) / 100

    fun uploadChunkFull(bytes: Int): Boolean = bytes >= MAX_UPLOAD_BYTES
}
