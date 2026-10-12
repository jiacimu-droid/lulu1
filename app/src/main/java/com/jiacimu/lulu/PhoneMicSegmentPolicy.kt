package com.jiacimu.lulu

/**
 * Upload chunks and speech endpoints are different things:
 * an upload chunk ending MUST NOT mean the human has finished talking.
 */
internal object PhoneMicSegmentPolicy {
    const val SAMPLE_RATE = 16_000
    const val MAX_UPLOAD_BYTES = SAMPLE_RATE * 2 * 12

    fun silenceMs(utteranceFrames: Int, configured: Int): Int = when {
        utteranceFrames >= 50 -> configured.coerceIn(2_200, 4_500)
        utteranceFrames >= 20 -> configured.coerceIn(1_900, 4_500)
        else -> configured.coerceIn(1_450, 4_500)
    }

    fun finishedBySilence(silentFrames: Int, utteranceFrames: Int, configured: Int): Boolean =
        silentFrames >= (silenceMs(utteranceFrames, configured) + 99) / 100

    /** Room-noise-adaptive start and stop levels; no remote VAD model/API. */
    fun startThreshold(configured: Float, ambientRms: Double): Double =
        maxOf(configured.toDouble(), ambientRms * 1.15)

    /**
     * Quiet handset/headset mics can stay below a fixed 195-RMS threshold.
     * The secondary gate follows the *observed* room floor while keeping
     * a minimum delta from ambient. It is not a transcript or a speaker ID.
     */
    fun softSpeechThreshold(configured: Float, ambientRms: Double): Double =
        maxOf(55.0, minOf(configured * 0.70, ambientRms * 1.30 + 20.0))

    fun softSpeechOnset(rms: Double, ambientRms: Double,
        threshold: Float, changingFrames: Int): Boolean =
        rms >= softSpeechThreshold(threshold, ambientRms) &&
            rms >= ambientRms + 24.0 && changingFrames >= 1

    /** Once a very quiet voice is acquired, the high fixed threshold must not
     * be reused to treat every one of its syllables as silence. */
    fun softVoiceQuietThreshold(ambientRms: Double, peakRms: Double): Double =
        maxOf(26.0, ambientRms * 1.32, peakRms * 0.40)

    /** A logarithmic *real PCM* level used only for the visible mic meter. */
    fun displayLevel(rms: Double): Float = if (rms < 12.0) 0f else
        (kotlin.math.ln(1.0 + rms / 70.0) / kotlin.math.ln(1.0 + 4_000.0 / 70.0))
            .toFloat().coerceIn(0f, 1f)

    fun quietThreshold(configured: Float, ambientRms: Double, peakRms: Double): Double =
        maxOf(configured * 0.72, ambientRms * 1.28, peakRms * 0.20)

    /** If the mic keeps reporting flat background noise, submit the utterance
     * instead of leaving ASR waiting indefinitely for an end-of-turn marker. */
    fun stableBackgroundNoise(variation: Double, averageRms: Double): Boolean =
        averageRms > 0.0 && variation <= maxOf(30.0, averageRms * 0.14)

    fun stationaryNoiseEnded(steadyFrames: Int, utteranceFrames: Int): Boolean =
        steadyFrames >= 36 && utteranceFrames >= 40

    /** A clear but very short whispered name can be a complete valid onset. */
    fun onsetFrames(rms: Double, threshold: Double): Int =
        if (rms >= threshold * 1.65) 1 else 2

    /** Even an empty PCM buffer needs a final marker when earlier chunks
     * already left the recorder and the user pressed mute.
     */
    fun finalizeOnMute(captureActive: Boolean, bufferedBytes: Int): Boolean =
        captureActive && bufferedBytes >= 0

    fun uploadChunkFull(bytes: Int): Boolean = bytes >= MAX_UPLOAD_BYTES
}
