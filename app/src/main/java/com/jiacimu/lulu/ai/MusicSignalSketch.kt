package com.jiacimu.lulu.ai

import kotlin.math.log10
import kotlin.math.sqrt

/**
 * Lightweight local music dynamics, inspired by whale-listen's temporal maps.
 * No MIDI/pitch inference: this reports only measurable waveform energy.
 */
internal object MusicSignalSketch {
    private fun u16(b: ByteArray, p: Int) =
        (b[p].toInt() and 255) or ((b[p + 1].toInt() and 255) shl 8)

    private fun u32(b: ByteArray, p: Int) =
        u16(b, p).toLong() or (u16(b, p + 2).toLong() shl 16)

    fun describe(bytes: ByteArray): String? {
        if (bytes.size < 44 ||
            String(bytes, 0, 4, Charsets.US_ASCII) != "RIFF" ||
            String(bytes, 8, 4, Charsets.US_ASCII) != "WAVE") return null
        var pos = 12
        var channels = 0
        var rate = 0
        var align = 0
        var audioStart = -1
        var audioLength = 0
        while (pos <= bytes.size - 8) {
            val type = String(bytes, pos, 4, Charsets.US_ASCII)
            val length = u32(bytes, pos + 4)
            if (length > bytes.size.toLong() - pos - 8) return null
            val start = pos + 8
            if (type == "fmt " && length >= 16) {
                if (u16(bytes, start) != 1 || u16(bytes, start + 14) != 16) return null
                channels = u16(bytes, start + 2)
                val hz = u32(bytes, start + 4)
                if (hz > Int.MAX_VALUE) return null
                rate = hz.toInt()
                align = u16(bytes, start + 12)
            } else if (type == "data" && audioStart < 0) {
                audioStart = start
                audioLength = length.toInt()
            }
            val next = start.toLong() + length + (length and 1L)
            if (next > bytes.size || next <= pos) return null
            pos = next.toInt()
        }
        if (channels !in 1..2 || rate !in 8000..192000 ||
            align != channels * 2 || audioStart < 0 || audioLength < align) return null
        val frames = audioLength / align
        val windowFrames = rate * 2
        var frame = 0
        val rms = mutableListOf<Double>()
        while (frame < frames) {
            val count = minOf(windowFrames, frames - frame)
            var squared = 0.0
            for (i in 0 until count) {
                for (ch in 0 until channels) {
                    val at = audioStart + (frame + i) * align + ch * 2
                    val v = u16(bytes, at)
                    val normalized = (if (v >= 32768) v - 65536 else v) / 32768.0
                    squared += normalized * normalized
                }
            }
            rms += sqrt(squared / (count.toDouble() * channels))
            frame += count
        }
        val maximum = rms.maxOrNull() ?: return null
        val step = ((rms.size + 19) / 20).coerceAtLeast(1)
        val windows = rms.indices.step(step).joinToString("、") { i ->
            val v = rms[i]
            val db = if (v < 0.000001) -120 else (20 * log10(v)).toInt()
            val energy = when {
                v < 0.001 -> "接近静默"
                maximum > 0 && v > maximum * 0.75 -> "较强"
                maximum > 0 && v < maximum * 0.25 -> "较弱"
                else -> "中等"
            }
            "${i * 2}秒:${energy}(${db}dBFS)"
        }
        return "真实 PCM16 WAV 波形，长度约${"%.1f".format(java.util.Locale.ROOT, frames.toDouble() / rate)}秒。" +
            "每2秒的音频能量变化：$windows。能量不是音符、节拍、乐器或歌词；不能凭这份数据判断作品主题或人的心情。"
    }
}
