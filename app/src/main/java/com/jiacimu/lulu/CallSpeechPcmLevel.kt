package com.jiacimu.lulu

import kotlin.math.sqrt

/** Measures PCM16 samples actually queued to AudioTrack; never samples the microphone as lip sync. */
internal object CallSpeechPcmLevel {
    fun measure(bytes: ByteArray, offset: Int, length: Int): Float {
        if (offset < 0 || length < 2 || offset.toLong() + length > bytes.size) return 0f
        val frames = length / 2
        if (frames == 0) return 0f
        var sum = 0.0
        var samples = 0
        // Take a bounded number of true audio samples even for large provider chunks.
        val stride = (frames / 256).coerceAtLeast(1)
        for (i in 0 until frames step stride) {
            val pos = offset + i * 2
            val sample = ((bytes[pos + 1].toInt() shl 8) or (bytes[pos].toInt() and 0xff)).toShort().toInt()
            sum += sample.toDouble() * sample
            samples++
        }
        val rms = sqrt(sum / samples)
        return ((rms - 250.0) / 9_000.0).toFloat().coerceIn(0f, 1f)
    }
}
