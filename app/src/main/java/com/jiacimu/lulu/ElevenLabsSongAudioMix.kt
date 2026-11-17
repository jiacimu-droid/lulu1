package com.jiacimu.lulu

import android.media.AudioFormat
import android.media.MediaCodec
import android.media.MediaExtractor
import android.media.MediaFormat
import java.io.BufferedInputStream
import java.io.BufferedOutputStream
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import kotlin.math.max
import kotlin.math.roundToInt

/**
 * Mix the two ElevenLabs 44.1kHz MP3 stems into one locally playable WAV.
 * Decode one stem at a time into temporary PCM files, rather than keeping
 * minutes of audio in the Android heap. Never overwrite the original song.
 */
internal object ElevenLabsSongAudioMix {
    const val SAMPLE_RATE = 44_100
    private const val CHANNELS = 2
    private const val FRAME_BYTES = 4
    private const val MAX_AUDIO_BYTES = SAMPLE_RATE * FRAME_BYTES * 185L

    fun render(instrumental: File, roleVocals: File, targetPart: File) {
        val backingPcm = File(targetPart.parentFile, targetPart.name + ".backing.pcm")
        val voicePcm = File(targetPart.parentFile, targetPart.name + ".voice.pcm")
        try {
            decodeToStereoPcm(instrumental, backingPcm)
            decodeToStereoPcm(roleVocals, voicePcm)
            writeMixedWav(backingPcm, voicePcm, targetPart)
        } finally {
            backingPcm.delete()
            voicePcm.delete()
        }
    }

    internal fun blend(backing: Int, vocal: Int): Short =
        (backing * 0.65 + vocal * 0.90).roundToInt().coerceIn(-32768, 32767).toShort()

    private fun decodeToStereoPcm(input: File, pcmOutput: File) {
        val extractor = MediaExtractor()
        var decoder: MediaCodec? = null
        try {
            extractor.setDataSource(input.absolutePath)
            val track = (0 until extractor.trackCount).firstOrNull { index ->
                extractor.getTrackFormat(index).getString(MediaFormat.KEY_MIME)?.startsWith("audio/") == true
            } ?: error("返回的分轨文件没有音频轨")
            extractor.selectTrack(track)
            val format = extractor.getTrackFormat(track)
            val mime = format.getString(MediaFormat.KEY_MIME) ?: error("音频格式未知")
            val codec = MediaCodec.createDecoderByType(mime)
            decoder = codec
            codec.configure(format, null, null, 0)
            codec.start()

            var inputEnd = false
            var outputEnd = false
            var channelCount = 0
            var idle = 0
            val info = MediaCodec.BufferInfo()
            BufferedOutputStream(FileOutputStream(pcmOutput), 32768).use { out ->
                var written = 0L
                while (!outputEnd) {
                    var progressed = false
                    if (!inputEnd) {
                        val inputIndex = codec.dequeueInputBuffer(10_000)
                        if (inputIndex >= 0) {
                            val buffer = codec.getInputBuffer(inputIndex)
                                ?: error("音频解码缓冲区不可用")
                            buffer.clear()
                            val bytes = extractor.readSampleData(buffer, 0)
                            if (bytes < 0) {
                                codec.queueInputBuffer(inputIndex, 0, 0, 0,
                                    MediaCodec.BUFFER_FLAG_END_OF_STREAM)
                                inputEnd = true
                            } else {
                                codec.queueInputBuffer(inputIndex, 0, bytes,
                                    extractor.sampleTime.coerceAtLeast(0), 0)
                                extractor.advance()
                            }
                            progressed = true
                        }
                    }
                    when (val outputIndex = codec.dequeueOutputBuffer(info, 10_000)) {
                        MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> {
                            val decodedFormat = codec.outputFormat
                            check(decodedFormat.getInteger(MediaFormat.KEY_SAMPLE_RATE) == SAMPLE_RATE) {
                                "转换后的歌曲不是44.1kHz，不能安全混音"
                            }
                            channelCount = decodedFormat.getInteger(MediaFormat.KEY_CHANNEL_COUNT)
                            check(channelCount in 1..2) { "暂不支持超过双声道的音轨" }
                            val encoding = if (decodedFormat.containsKey(MediaFormat.KEY_PCM_ENCODING))
                                decodedFormat.getInteger(MediaFormat.KEY_PCM_ENCODING)
                                else AudioFormat.ENCODING_PCM_16BIT
                            check(encoding == AudioFormat.ENCODING_PCM_16BIT) {
                                "解码器没有输出16位PCM音频"
                            }
                            progressed = true
                        }
                        in 0..Int.MAX_VALUE -> {
                            if (info.size > 0 && info.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG == 0) {
                                if (channelCount == 0) {
                                    val decodedFormat = codec.outputFormat
                                    check(decodedFormat.getInteger(MediaFormat.KEY_SAMPLE_RATE) == SAMPLE_RATE)
                                    channelCount = decodedFormat.getInteger(MediaFormat.KEY_CHANNEL_COUNT)
                                }
                                check(channelCount in 1..2) { "分轨音频的声道数不可用" }
                                val buffer = codec.getOutputBuffer(outputIndex)
                                    ?: error("没有收到解码后的音频")
                                buffer.position(info.offset)
                                buffer.limit(info.offset + info.size)
                                val raw = ByteArray(info.size)
                                buffer.get(raw)
                                check(raw.size % (channelCount * 2) == 0) {
                                    "解码音频帧不完整"
                                }
                                if (channelCount == 2) {
                                    out.write(raw)
                                    written += raw.size
                                } else {
                                    for (i in raw.indices step 2) {
                                        out.write(raw, i, 2)
                                        out.write(raw, i, 2)
                                    }
                                    written += raw.size * 2L
                                }
                                check(written <= MAX_AUDIO_BYTES) { "角色歌声超过本地混音时长上限" }
                            }
                            outputEnd = info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0
                            codec.releaseOutputBuffer(outputIndex, false)
                            progressed = true
                        }
                    }
                    idle = if (progressed) 0 else idle + 1
                    check(idle < 1500) { "音频解码超时" }
                }
                check(written > 0) { "分轨没有可播放的音频" }
            }
        } finally {
            runCatching { decoder?.stop() }
            decoder?.release()
            extractor.release()
        }
    }

    /** Public to package tests: deterministic, clipped PCM mixing and standard WAV headers. */
    internal fun writeMixedWav(backing: File, voice: File, output: File) {
        val length = max(backing.length(), voice.length())
        require(length > 0 && length <= MAX_AUDIO_BYTES && length % FRAME_BYTES == 0L) {
            "分轨时长不合法"
        }
        require(backing.length() % FRAME_BYTES == 0L && voice.length() % FRAME_BYTES == 0L) {
            "分轨帧不完整"
        }
        val outLength = length.toInt()
        BufferedInputStream(FileInputStream(backing), 32768).use { backingIn ->
            BufferedInputStream(FileInputStream(voice), 32768).use { voiceIn ->
                BufferedOutputStream(FileOutputStream(output), 32768).use { wav ->
                    wav.write("RIFF".toByteArray(Charsets.US_ASCII))
                    write32(wav, 36 + outLength)
                    wav.write("WAVEfmt ".toByteArray(Charsets.US_ASCII))
                    write32(wav, 16)
                    write16(wav, 1)
                    write16(wav, CHANNELS)
                    write32(wav, SAMPLE_RATE)
                    write32(wav, SAMPLE_RATE * FRAME_BYTES)
                    write16(wav, FRAME_BYTES)
                    write16(wav, 16)
                    wav.write("data".toByteArray(Charsets.US_ASCII))
                    write32(wav, outLength)
                    repeat(outLength / 2) {
                        val b = nextSample(backingIn)
                        val v = nextSample(voiceIn)
                        write16(wav, blend(b, v).toInt())
                    }
                }
            }
        }
    }

    private fun nextSample(input: BufferedInputStream): Int {
        val lo = input.read()
        if (lo == -1) return 0
        val hi = input.read()
        check(hi != -1) { "截断的PCM音频" }
        return ((hi shl 8) or lo).toShort().toInt()
    }

    private fun write16(out: BufferedOutputStream, value: Int) {
        out.write(value and 0xff)
        out.write((value ushr 8) and 0xff)
    }

    private fun write32(out: BufferedOutputStream, value: Int) {
        out.write(value and 0xff)
        out.write((value ushr 8) and 0xff)
        out.write((value ushr 16) and 0xff)
        out.write((value ushr 24) and 0xff)
    }
}
