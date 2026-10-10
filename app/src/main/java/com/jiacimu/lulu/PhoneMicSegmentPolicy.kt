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

    /** Room-noise-adaptive start and stop levels; no remote VAD model/API. */
    fun startThreshold(configured: Float, ambientRms: Double): Double =
        maxOf(configured.toDouble(), ambientRms * 1.22)

    fun quietThreshold(configured: Float, ambientRms: Double, peakRms: Double): Double =
        maxOf(configured * 0.80, ambientRms * 1.35, peakRms * 0.22)

    /** If the mic keeps reporting flat background noise, submit the utterance
     * instead of leaving ASR waiting indefinitely for an end-of-turn marker. */
    fun stableBackgroundNoise(variation: Double, averageRms: Double): Boolean =
        averageRms > 0.0 && variation <= maxOf(30.0, averageRms * 0.14)

    fun stationaryNoiseEnded(steadyFrames: Int, utteranceFrames: Int): Boolean =
        steadyFrames >= 23 && utteranceFrames >= 25

    fun uploadChunkFull(bytes: Int): Boolean = bytes >= MAX_UPLOAD_BYTES
}
