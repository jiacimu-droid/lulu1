package com.jiacimu.lulu

import java.util.Locale
import kotlin.math.abs
import kotlin.math.log10
import kotlin.math.max
import kotlin.math.sqrt

/**
 * Optional, on-device clues from the SAME 16 kHz / mono / PCM16 recording used for STT.
 * Not an emotion classifier, speaker recognizer, diagnosis, or a substitute for the user's words.
 * No audio is retained, no request is sent, and the accumulator is discarded after each turn.
 *
 * dBFS is a recording level affected by microphone gain, not real-world loudness.
 * F0 is estimated only on periodic, adequately loud frames; whispers/noisy audio often have none.
 */
internal class VoiceAcousticAnalyzer {
    private val window = ShortArray(1600) // 100 ms of 16 kHz PCM, ~3 KB
    private var position = 0
    private var totalFrames = 0
    private var activeFrames = 0
    private var levelSum = 0.0
    private var minLevelDb = Double.POSITIVE_INFINITY
    private var maxLevelDb = Double.NEGATIVE_INFINITY
    private var pitchCount = 0
    private var pitchSum = 0.0
    private var pitchMin = Double.POSITIVE_INFINITY
    private var pitchMax = Double.NEGATIVE_INFINITY
    private var seenVoice = false
    private var quietFrames = 0
    private var internalPauses = 0

    @Synchronized
    fun consume(pcm: ByteArray) {
        // Real AudioRecord frames and the existing STT chunks contain complete samples.
        if (pcm.size % 2 != 0) return
        var at = 0
        while (at < pcm.size) {
            val low = pcm[at].toInt() and 255
            val high = pcm[at + 1].toInt()
            window[position++] = ((high shl 8) or low).toShort()
            at += 2
            if (position == window.size) {
                analyseFrame()
                position = 0
            }
        }
    }

    private fun analyseFrame() {
        totalFrames++
        var power = 0.0
        for (sample in window) {
            val value = sample.toDouble() / 32768.0
            power += value * value
        }
        val rms = sqrt(power / window.size)
        // A relative gain estimate, NOT a speech detector. Unvoiced consonants,
        // low-volume speech and background noise may be excluded.
        if (rms < 0.012) {
            if (seenVoice) quietFrames++
            return
        }
        activeFrames++
        if (seenVoice && quietFrames >= 2) internalPauses++
        quietFrames = 0
        seenVoice = true
        val db = 20 * log10(rms)
        levelSum += db
        minLevelDb = minOf(minLevelDb, db)
        maxLevelDb = maxOf(maxLevelDb, db)
        val pitch = periodicPitchHz()
        if (pitch != null) {
            pitchCount++
            pitchSum += pitch
            pitchMin = minOf(pitchMin, pitch)
            pitchMax = maxOf(pitchMax, pitch)
        }
    }

    /** Normalized autocorrelation over 4 kHz downsampled samples; avoid claiming pitch when uncertain. */
    private fun periodicPitchHz(): Double? {
        val stride = 4
        val count = window.size / stride
        var mean = 0.0
        for (i in 0 until count) mean += window[i * stride]
        mean /= count
        var bestLag = 0
        var bestScore = 0.0
        // 77–444 Hz: no gender or personality inference from the result.
        for (lag in 9..52) {
            var numerator = 0.0
            var left = 0.0
            var right = 0.0
            for (i in 0 until count - lag) {
                val x = window[i * stride] - mean
                val y = window[(i + lag) * stride] - mean
                numerator += x * y
                left += x * x
                right += y * y
            }
            if (left < 1.0 || right < 1.0) continue
            val score = numerator / sqrt(left * right)
            if (score > bestScore) { bestScore = score; bestLag = lag }
        }
        if (bestLag == 0 || bestScore < 0.78) return null
        // Pick the FIRST comparably strong periodic peak to reduce octave errors.
        for (lag in 9 until bestLag) {
            var numerator = 0.0
            var left = 0.0
            var right = 0.0
            for (i in 0 until count - lag) {
                val x = window[i * stride] - mean
                val y = window[(i + lag) * stride] - mean
                numerator += x * y
                left += x * x
                right += y * y
            }
            if (left > 1.0 && right > 1.0 &&
                numerator / sqrt(left * right) >= max(0.78, bestScore - 0.05)) {
                return 4000.0 / lag
            }
        }
        return 4000.0 / bestLag
    }

    /**
     * Use only for the SAME confirmed STT turn. Failure, short/noisy audio and
     * a reset all yield null, so no acoustic guess can create a fake user turn.
     */
    @Synchronized
    fun finish(): String? {
        val output = if (activeFrames >= 6) {
            val facts = mutableListOf<String>()
            facts += "录音中有效声音约" + String.format(Locale.ROOT, "%.1f", activeFrames / 10.0) + "秒"
            facts += "平均录音电平约" + String.format(Locale.ROOT, "%.0f", levelSum / activeFrames) + "dBFS"
            if (activeFrames >= 10 && maxLevelDb - minLevelDb >= 7.0)
                facts += "这段录音的声音强度有起伏"
            if (pitchCount >= 5) {
                val meanPitch = pitchSum / pitchCount
                facts += "部分有声帧可估计基频约" + meanPitch.toInt() + "Hz"
                if (pitchCount >= 10 && (pitchMax - pitchMin) / meanPitch >= 0.28)
                    facts += "音高存在变化"
            }
            if (internalPauses > 0 && totalFrames >= 15)
                facts += "中间检测到" + internalPauses + "处可能的短暂停顿"
            "【本轮通话的声学观察，不是用户说的话】" +
                facts.joinToString("；") +
                "。这些只是麦克风录音线索，受噪声、音量增益和算法误差影响；" +
                "无法单凭它们判断生气、嘴硬、撒娇、疲劳、意图或真实身份。" +
                "转写也可能有误；用户明确的话优先，必要时自然确认，不要代替用户编造内容。" +
                "不要朗读这些技术信息，也不要将推断固化为长期事实。"
        } else null
        clear()
        return output
    }

    @Synchronized
    fun clear() {
        position = 0
        totalFrames = 0
        activeFrames = 0
        levelSum = 0.0
        minLevelDb = Double.POSITIVE_INFINITY
        maxLevelDb = Double.NEGATIVE_INFINITY
        pitchCount = 0
        pitchSum = 0.0
        pitchMin = Double.POSITIVE_INFINITY
        pitchMax = Double.NEGATIVE_INFINITY
        seenVoice = false
        quietFrames = 0
        internalPauses = 0
    }
}
