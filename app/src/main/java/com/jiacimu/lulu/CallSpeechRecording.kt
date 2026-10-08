package com.jiacimu.lulu

import java.io.File
import java.io.RandomAccessFile

/**
 * A copy of the exact 24 kHz / mono / 16-bit PCM samples sent to AudioTrack.
 * Recording is best-effort: disk errors never interrupt a live phone call.
 * Only a completely delivered utterance is promoted to its durable WAV file.
 */
internal class CallSpeechRecording(private val target: File) {
    private val temporary = File(target.parentFile, target.name + ".partial")
    private var output: RandomAccessFile? = null
    private var dataBytes = 0L
    private var valid = false

    init {
        target.parentFile?.mkdirs()
        val file = RandomAccessFile(temporary, "rw")
        file.setLength(0)
        file.write(ByteArray(44))
        output = file
        valid = true
    }

    fun write(bytes: ByteArray, offset: Int, length: Int) {
        if (!valid || length <= 0) return
        try {
            check(dataBytes + length <= MAX_PCM_BYTES) { "单句通话音频已达到本地缓存大小限制" }
            output?.write(bytes, offset, length)
            dataBytes += length
        } catch (_: Exception) {
            valid = false // Playback continues even when local recording fails.
        }
    }

    fun finish(delivered: Boolean): Boolean {
        val file = output ?: return false
        output = null
        val completed = delivered && valid && dataBytes > 0L && dataBytes % 2L == 0L
        val saved = runCatching {
            if (completed) {
                file.seek(0)
                file.writeBytes("RIFF")
                file.writeLeInt(dataBytes.toInt() + 36)
                file.writeBytes("WAVE")
                file.writeBytes("fmt ")
                file.writeLeInt(16)
                file.writeLeShort(1) // PCM
                file.writeLeShort(1) // mono
                file.writeLeInt(24_000)
                file.writeLeInt(24_000 * 2)
                file.writeLeShort(2)
                file.writeLeShort(16)
                file.writeBytes("data")
                file.writeLeInt(dataBytes.toInt())
            }
            file.close()
            if (completed) {
                check(!target.exists() || target.delete())
                check(temporary.renameTo(target))
                true
            } else false
        }.getOrDefault(false)
        if (!saved) temporary.delete()
        return saved
    }

    private fun RandomAccessFile.writeLeInt(value: Int) = writeInt(Integer.reverseBytes(value))
    private fun RandomAccessFile.writeLeShort(value: Int) = writeShort(java.lang.Short.reverseBytes(value.toShort()).toInt())

    private companion object {
        // About seven minutes of 24 kHz PCM per spoken message.
        const val MAX_PCM_BYTES = 20L * 1024 * 1024
    }
}
