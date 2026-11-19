package com.jiacimu.lulu

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioTrack
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.coroutines.coroutineContext
import kotlin.math.roundToInt

/** MOSS single-speaker TTS. Voice IDs and credentials never cross providers. */
internal class MossSpeech(context: Context) {
    private val app = context.applicationContext
    private val prefs = app.getSharedPreferences("lulu_advanced_settings", Context.MODE_PRIVATE)
    @Volatile private var generation = 0L
    @Volatile private var connection: HttpURLConnection? = null
    @Volatile private var track: AudioTrack? = null

    fun stop() {
        generation++
        connection?.disconnect()
        connection = null
        val old = track
        track = null
        runCatching { old?.pause(); old?.flush(); old?.release() }
    }

    private suspend fun request(text: String, voiceId: String?, format: String, source: String): ByteArray =
        withContext(Dispatchers.IO) {
            coroutineContext.ensureActive()
            val token = generation
            val key = prefs.getString("moss_api_key", "").orEmpty().trim()
            val voice = voiceId?.trim()?.takeIf(String::isNotBlank)
                ?: prefs.getString("moss_voice_id", "").orEmpty().trim()
            require(key.isNotBlank()) { "请填写 MOSS API Key" }
            require(voice.isNotBlank()) { "请填写 MOSS Voice ID" }
            val model = prefs.getString("moss_tts_model", DEFAULT_MODEL).orEmpty().ifBlank { DEFAULT_MODEL }
            require(model in MODELS) { "不支持的 MOSS 单人语音模型" }
            val call = (URL(ENDPOINT).openConnection() as HttpURLConnection).apply {
                requestMethod = "POST"
                connectTimeout = 15_000
                readTimeout = 90_000
                doOutput = true
                setRequestProperty("Authorization", "Bearer " + key)
                setRequestProperty("Content-Type", "application/json")
            }
            connection = call
            try {
                val body = JSONObject().put("model", model).put("input", text)
                    .put("voice_id", voice).put("response_format", format)
                    .put("delivery_method", "audio").put("stream", false)
                call.outputStream.use { it.write(body.toString().toByteArray(Charsets.UTF_8)) }
                val status = call.responseCode
                if (status !in 200..299) {
                    val detail = call.errorStream?.bufferedReader()?.use { it.readText().take(220) }.orEmpty()
                    error("MOSS HTTP " + status + " " + detail)
                }
                val bytes = call.inputStream.use { it.readBytes() }
                check(token == generation) { "语音生成已取消" }
                check(bytes.isNotEmpty() && bytes.size <= 20 * 1024 * 1024) { "MOSS 音频无效或过大" }
                VoiceUsageAudit.record(app, "MOSS", source, text.length, model)
                bytes
            } finally {
                call.disconnect()
                if (connection === call) connection = null
            }
        }

    suspend fun synthesize(text: String, voiceId: String?, source: String = "unknown"): ByteArray =
        request(text, voiceId, "mp3", source)

    suspend fun speak(text: String, voiceId: String?, onAudioStarted: () -> Unit = {},
        recordingTarget: File? = null, source: String = "phone_direct",
        onPcmFrame: (ByteArray, Int, Int) -> Unit = { _, _, _ -> },
        onPosition: (Long) -> Unit = {},
    ): Boolean = withContext(Dispatchers.IO) {
        val token = generation
        // Non-streaming WAV provides the actual sample rate; resample to the 24k phone clock.
        val pcm = decodeWav(request(text, voiceId, "wav", source))
        check(token == generation) { "语音播放已取消" }
        val rate = 24_000
        val audio = AudioTrack.Builder().setAudioAttributes(AudioAttributes.Builder()
            .setUsage(AudioAttributes.USAGE_VOICE_COMMUNICATION)
            .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH).build())
            .setAudioFormat(AudioFormat.Builder().setSampleRate(rate)
                .setChannelMask(AudioFormat.CHANNEL_OUT_MONO)
                .setEncoding(AudioFormat.ENCODING_PCM_16BIT).build())
            .setBufferSizeInBytes(maxOf(rate, AudioTrack.getMinBufferSize(
                rate, AudioFormat.CHANNEL_OUT_MONO, AudioFormat.ENCODING_PCM_16BIT)))
            .setTransferMode(AudioTrack.MODE_STREAM).build()
        track = audio
        val recording = recordingTarget?.let { runCatching { CallSpeechRecording(it) }.getOrNull() }
        val clock = CallAudioPlaybackClock(audio, rate, { token == generation }, onAudioStarted, onPosition)
        var delivered = false
        try {
            CallAudioRoute.preferredOutput?.let {
                check(audio.setPreferredDevice(it)) { "系统未接受电话声音输出设备" }
            }
            audio.play()
            var offset = 0
            while (offset < pcm.size && token == generation) {
                coroutineContext.ensureActive()
                val count = audio.write(pcm, offset, minOf(8192, pcm.size - offset), AudioTrack.WRITE_BLOCKING)
                check(count > 0) { "MOSS 音频播放失败" }
                clock.sample()
                recording?.write(pcm, offset, count)
                runCatching { onPcmFrame(pcm, offset, count) }
                offset += count
            }
            val deadline = android.os.SystemClock.elapsedRealtime() + pcm.size * 1000L / (rate * 2) + 5_000L
            while (token == generation && (audio.playbackHeadPosition.toLong() and 0xffffffffL) < pcm.size / 2) {
                check(android.os.SystemClock.elapsedRealtime() < deadline) { "MOSS 音频播放超时" }
                delay(20)
            }
            delivered = token == generation
            clock.sample()
            delivered
        } finally {
            clock.close()
            recording?.finish(delivered)
            if (track === audio) {
                track = null
                runCatching { audio.release() }
            }
        }
    }

    private fun decodeWav(bytes: ByteArray): ByteArray {
        require(bytes.size >= 44 &&
            String(bytes, 0, 4, Charsets.US_ASCII) == "RIFF" &&
            String(bytes, 8, 4, Charsets.US_ASCII) == "WAVE") { "MOSS 未返回 WAV 音频" }
        val buf = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN)
        var rate = 0
        var format = 0
        var channels = 0
        var bits = 0
        var dataStart = -1
        var dataSize = 0
        var i = 12
        while (i + 8 <= bytes.size) {
            val size = buf.getInt(i + 4).toLong() and 0xffffffffL
            if (size > bytes.size.toLong() - i - 8L) break
            val tag = String(bytes, i, 4, Charsets.US_ASCII)
            if (tag == "fmt " && size >= 16) {
                format = buf.getShort(i + 8).toInt() and 0xffff
                channels = buf.getShort(i + 10).toInt() and 0xffff
                rate = buf.getInt(i + 12)
                bits = buf.getShort(i + 22).toInt() and 0xffff
            }
            if (tag == "data") {
                dataStart = i + 8
                dataSize = size.toInt()
            }
            i += 8 + size.toInt() + (size.toInt() and 1)
        }
        require(format == 1 && channels == 1 && bits == 16 && rate in 8_000..96_000 &&
            dataStart >= 0 && dataSize > 0 && dataSize % 2 == 0) {
            "MOSS WAV 必须是 PCM16 单声道"
        }
        val source = bytes.copyOfRange(dataStart, dataStart + dataSize)
        if (rate == 24_000) return source
        val samples = ByteBuffer.wrap(source).order(ByteOrder.LITTLE_ENDIAN)
        val sourceCount = source.size / 2
        val count = (sourceCount.toLong() * 24_000 / rate).toInt()
        require(count in 1..10_000_000) { "音频长度无效" }
        val output = ByteArray(count * 2)
        val destination = ByteBuffer.wrap(output).order(ByteOrder.LITTLE_ENDIAN)
        for (n in 0 until count) {
            val pos = n.toDouble() * rate / 24_000.0
            val a = pos.toInt().coerceAtMost(sourceCount - 1)
            val b = (a + 1).coerceAtMost(sourceCount - 1)
            val delta = pos - a
            val value = samples.getShort(a * 2) * (1 - delta) + samples.getShort(b * 2) * delta
            destination.putShort(value.roundToInt().coerceIn(-32768, 32767).toShort())
        }
        return output
    }

    companion object {
        const val ENDPOINT = "https://api.mosi.cn/v1/audio/speech"
        const val DEFAULT_MODEL = "moss-tts-1.5-flash"
        // The six single-speaker IDs accepted by MOSS /v1/audio/speech.
        // The selectable catalog and request validation must stay in sync.
        val MODEL_CHOICES = listOf(
            "2.0 Flash Preview · 最新" to "moss-tts-2.0-flash-preview",
            "2.0 Flash Preview · 2026-11-04" to "moss-tts-2.0-flash-preview-2026-11-04",
            "1.5 Flash · 推荐" to DEFAULT_MODEL,
            "1.5 Flash · 2026-06-26" to "moss-tts-1.5-flash-2026-06-26",
            "1.0 Pro" to "moss-tts-1.0-pro",
            "1.0 Pro · 2026-02-07" to "moss-tts-1.0-pro-2026-02-07",
        )
        val MODELS: Set<String> = MODEL_CHOICES.map { it.second }.toSet()
    }
}
