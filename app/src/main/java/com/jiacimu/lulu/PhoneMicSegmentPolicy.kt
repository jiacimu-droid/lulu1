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

    /** Barge-in must be harder to trigger than normal speech, particularly
     * with the handset speaker where uncancelled TTS may leak into the mic.
     * The recorder is allowed to monitor playback, but audio is not submitted
     * as a user utterance until this independent gate is met.
     */
    fun bargeInThreshold(normal: Double, ambientRms: Double, externalSpeaker: Boolean): Double =
        maxOf(normal * if (externalSpeaker) 2.1 else 1.35,
            ambientRms * if (externalSpeaker) 2.6 else 1.55)

    fun startFrames(playbackActive: Boolean): Int = if (playbackActive) 4 else 2

    /** A relatively flat voice is not proof of silence.
     * End-by-stationary-noise is allowed only if energy has actually fallen
     * substantially relative to the phrase's observed peak.
     */
    fun canEndFromStationaryNoise(
        steadyFrames: Int, utteranceFrames: Int,
        currentRms: Double, peakRms: Double,
    ): Boolean = stationaryNoiseEnded(steadyFrames, utteranceFrames) &&
        peakRms > 0.0 && currentRms < peakRms * 0.55

    fun uploadChunkFull(bytes: Int): Boolean = bytes >= MAX_UPLOAD_BYTES
}
