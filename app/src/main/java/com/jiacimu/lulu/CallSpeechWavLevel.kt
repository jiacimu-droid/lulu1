package com.jiacimu.lulu

import java.io.Closeable
import java.io.File
import java.io.RandomAccessFile

/**
 * Read a tiny window of the *actual cached PCM16 WAV* at MediaPlayer's current
 * playback position. No microphone, model inference, re-synthesis or full-file
 * decoding. Other WAV encodings safely keep the existing fallback mouth.
 */
internal class CallSpeechWavLevel private constructor(
    private val wav: RandomAccessFile,
    private val dataStart: Long,
    private val dataBytes: Long,
    private val sampleRate: Int,
    private val frameBytes: Int,
) : Closeable {
    private val samples = ByteArray(8192)

    @Synchronized
    fun levelAt(positionMs: Long): Float? {
        if (positionMs < 0) return null
        val frame = positionMs * sampleRate / 1000L
        if (frame < 0 || frame > dataBytes / frameBytes) return 0f
        val byteOffset = frame * frameBytes
        val available = dataBytes - byteOffset
        if (available < frameBytes) return 0f
        // Around 20ms of real samples is sufficient for the current ~100ms
        // MediaPlayer status cadence. Keep I/O independent of file duration.
        val wanted = (sampleRate / 50).coerceAtLeast(1) * frameBytes
        val length = minOf(wanted.toLong(), available, samples.size.toLong())
            .toInt() / frameBytes * frameBytes
        if (length <= 0) return 0f
        return runCatching {
            wav.seek(dataStart + byteOffset)
            wav.readFully(samples, 0, length)
            CallSpeechPcmLevel.measure(samples, 0, length)
        }.getOrNull()
    }

    @Synchronized
    override fun close() { runCatching { wav.close() } }

    companion object {
        private fun shortLE(file: RandomAccessFile): Int {
            val a = file.readUnsignedByte()
            val b = file.readUnsignedByte()
            return a or (b shl 8)
        }

        private fun uintLE(file: RandomAccessFile): Long {
            val a = shortLE(file).toLong()
            val b = shortLE(file).toLong()
            return a or (b shl 16)
        }

        fun open(file: File): CallSpeechWavLevel? {
            if (!file.isFile || !file.extension.equals("wav", ignoreCase = true)) return null
            val wav = runCatching { RandomAccessFile(file, "r") }.getOrNull() ?: return null
            try {
                if (wav.length() < 44 || wav.readTag() != "RIFF") {
                    wav.close()
                    return null
                }
                uintLE(wav) // RIFF container size
                if (wav.readTag() != "WAVE") {
                    wav.close()
                    return null
                }
                var rate = 0
                var block = 0
                var pcm16 = false
                var dataStart = -1L
                var dataBytes = 0L
                var inspected = 0
                while (wav.filePointer + 8 <= wav.length() && inspected++ < 64) {
                    val tag = wav.readTag()
                    val length = uintLE(wav)
                    val from = wav.filePointer
                    if (length > wav.length() - from) break
                    if (tag == "fmt " && length >= 16) {
                        val format = shortLE(wav)
                        val channels = shortLE(wav)
                        rate = uintLE(wav).toInt()
                        uintLE(wav) // byte rate
                        block = shortLE(wav)
                        val bits = shortLE(wav)
                        pcm16 = format == 1 && channels in 1..2 && bits == 16 &&
                            rate in 8000..96000 && block == channels * 2
                    } else if (tag == "data") {
                        dataStart = from
                        dataBytes = length
                        if (pcm16) break
                    }
                    wav.seek(from + length + (length and 1L))
                }
                if (!pcm16 || dataStart < 0 || dataBytes < block) {
                    wav.close()
                    return null
                }
                return CallSpeechWavLevel(wav, dataStart, dataBytes, rate, block)
            } catch (_: Exception) {
                runCatching { wav.close() }
                return null
            }
        }

        private fun RandomAccessFile.readTag(): String {
            val tag = ByteArray(4)
            readFully(tag)
            return String(tag, Charsets.US_ASCII)
        }
    }
}
