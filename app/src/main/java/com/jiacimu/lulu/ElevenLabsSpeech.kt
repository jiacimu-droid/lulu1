package com.jiacimu.lulu

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioTrack
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import java.net.HttpURLConnection
import java.net.URL
import java.io.InputStream
import java.io.File

/** Personal TTS credentials stay in the phone configuration, independent of the Agents service. */
internal class ElevenLabsSpeech(context: Context) {
    private val prefs = context.getSharedPreferences("lulu_advanced_settings", Context.MODE_PRIVATE)
    @Volatile private var epoch = 0L
    @Volatile private var connection: HttpURLConnection? = null
    @Volatile private var track: AudioTrack? = null
    @Volatile private var dialogueStream: ElevenLabsDialogueStream? = null

    fun stop() {
        epoch++
        connection?.disconnect(); connection = null
        dialogueStream?.close(); dialogueStream = null
        val old = track; track = null
        runCatching { old?.pause(); old?.flush(); old?.release() }
    }

    private fun request(text: String, voiceOverride: String?, format: String): HttpURLConnection {
        val key = prefs.getString("eleven_api_key", "").orEmpty().trim()
        val voice = voiceOverride?.takeIf(String::isNotBlank) ?: prefs.getString("eleven_voice_id", "").orEmpty().trim()
        require(key.isNotBlank() && voice.isNotBlank()) { "请填写 ElevenLabs API Key 和 Voice ID" }
        val model = selectedModel()
        val url = "https://api.elevenlabs.io${ElevenLabsModels.httpPath(model, voice)}?output_format=$format"
        val result = (URL(url).openConnection() as HttpURLConnection).apply {
            requestMethod = "POST"; connectTimeout = 15_000; readTimeout = 60_000; doOutput = true
            setRequestProperty("xi-api-key", key)
            setRequestProperty("Content-Type", "application/json")
        }
        connection = result
        val body = ElevenLabsModels.body(model, text, voice, prefs.getFloat("eleven_stability", .5f), prefs.getFloat("eleven_similarity", .75f))
        try {
            result.outputStream.use { it.write(body.toString().toByteArray()) }
            if (result.responseCode !in 200..299) {
                error("ElevenLabs HTTP ${result.responseCode}：请检查模型权限、声线和余额")
            }
            return result
        } catch (error: Throwable) {
            result.disconnect()
            if (connection === result) connection = null
            throw error
        }
    }

    private fun selectedModel() = prefs.getString("eleven_tts_model", ElevenLabsModels.DEFAULT).orEmpty().trim().ifBlank { ElevenLabsModels.DEFAULT }

    private class AudioSource(val input: InputStream, val cleanup: () -> Unit) {
        fun close() { runCatching { input.close() }; cleanup() }
    }
    private fun openAudio(text: String, voiceOverride: String?, format: String): AudioSource {
        if (ElevenLabsModels.websocket(selectedModel())) {
            val key = prefs.getString("eleven_api_key", "").orEmpty().trim()
            val voice = voiceOverride?.takeIf(String::isNotBlank) ?: prefs.getString("eleven_voice_id", "").orEmpty().trim()
            require(key.isNotBlank() && voice.isNotBlank()) { "请填写 ElevenLabs API Key 和 Voice ID" }
            val stream = ElevenLabsDialogueStream(key, selectedModel(), voice, text, format)
            dialogueStream = stream
            return AudioSource(stream) { stream.close(); if (dialogueStream === stream) dialogueStream = null }
        }
        val call = request(text, voiceOverride, format)
        return try { AudioSource(call.inputStream) { call.disconnect(); if (connection === call) connection = null } }
        catch (error: Throwable) { call.disconnect(); if (connection === call) connection = null; throw error }
    }

    suspend fun synthesize(text: String, voiceId: String?): ByteArray = withContext(Dispatchers.IO) {
        val token = epoch
        val call = openAudio(text, voiceId, "mp3_44100_128")
        try {
            val bytes = call.input.use { input ->
                val output = java.io.ByteArrayOutputStream()
                val buffer = ByteArray(8192)
                while (token == epoch) {
                    val count = input.read(buffer)
                    if (count < 0) break
                    check(output.size() + count <= 20 * 1024 * 1024) { "语音超过单段大小限制" }
                    output.write(buffer, 0, count)
                }
                output.toByteArray()
            }
            check(token == epoch) { "试听或合成已取消" }
            check(bytes.isNotEmpty() && bytes.size <= 20 * 1024 * 1024) { "语音为空或超过单段大小限制" }
            bytes
        } finally { call.close() }
    }

    suspend fun speak(text: String, voiceId: String?, onStarted: () -> Unit, recordingTarget: File? = null): Boolean = withContext(Dispatchers.IO) {
        val token = epoch
        val call = openAudio(text, voiceId, "pcm_24000")
        var audio: AudioTrack? = null
        val recording = recordingTarget?.let { runCatching { CallSpeechRecording(it) }.getOrNull() }
        var playedToEnd = false
        try {
            val rate = 24_000
            val output = AudioTrack.Builder().setAudioAttributes(AudioAttributes.Builder()
                .setUsage(AudioAttributes.USAGE_VOICE_COMMUNICATION).setContentType(AudioAttributes.CONTENT_TYPE_SPEECH).build())
                .setAudioFormat(AudioFormat.Builder().setSampleRate(rate).setChannelMask(AudioFormat.CHANNEL_OUT_MONO)
                    .setEncoding(AudioFormat.ENCODING_PCM_16BIT).build())
                .setBufferSizeInBytes(maxOf(rate, AudioTrack.getMinBufferSize(rate, AudioFormat.CHANNEL_OUT_MONO, AudioFormat.ENCODING_PCM_16BIT)))
                .setTransferMode(AudioTrack.MODE_STREAM).build()
            audio = output; track = output
            CallAudioRoute.preferredOutput?.let { check(output.setPreferredDevice(it)) { "系统未接受电话声音输出设备" } }
            output.play()
            var written = 0L
            var carry = 0
            val buffer = ByteArray(8192)
            call.input.use { input ->
                while (token == epoch) {
                    val count = input.read(buffer, carry, buffer.size - carry)
                    if (count < 0) break
                    val total = count + carry
                    val even = total - total % 2
                    var offset = 0
                    while (offset < even && token == epoch) {
                        CallAudioRoute.preferredOutput?.let {
                            if (output.preferredDevice?.id != it.id) check(output.setPreferredDevice(it)) { "系统未接受电话声音输出设备" }
                        }
                        val sent = output.write(buffer, offset, even - offset, AudioTrack.WRITE_BLOCKING)
                        check(sent > 0) { "ElevenLabs 音频播放失败" }
                        if (written == 0L) onStarted()
                        recording?.write(buffer, offset, sent)
                        offset += sent; written += sent
                    }
                    carry = total % 2
                    if (carry == 1) buffer[0] = buffer[even]
                }
            }
            if (token != epoch) return@withContext false
            check(written > 0 && carry == 0) { "ElevenLabs PCM 音频不完整" }
            val deadline = android.os.SystemClock.elapsedRealtime() + written * 1000 / (rate * 2) + 5_000
            while (token == epoch && (output.playbackHeadPosition.toLong() and 0xffffffffL) < written / 2) {
                check(android.os.SystemClock.elapsedRealtime() < deadline) { "语音播放超时" }
                delay(20)
            }
            playedToEnd = token == epoch
            playedToEnd
        } finally {
            // The recording contains exactly what was sent to AudioTrack, not a
            // second TTS generation. Interrupted or failed speech is discarded.
            recording?.finish(playedToEnd)
            call.close()
            if (track === audio) { track = null; runCatching { audio?.release() } }
        }
    }
}
