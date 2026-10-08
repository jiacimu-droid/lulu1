package com.jiacimu.lulu

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioTrack
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import java.io.File

/** HTTP SSE PCM audio is written to AudioTrack as it arrives, without whole-file buffering. */
internal class MiniMaxStreamingSpeech(context: Context) {
    private val prefs = context.getSharedPreferences("lulu_advanced_settings", Context.MODE_PRIVATE)
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

    suspend fun speak(text: String, voiceId: String?, onAudioStarted: () -> Unit = {}, recordingTarget: File? = null): Boolean = withContext(Dispatchers.IO) {
        val recording = recordingTarget?.let { runCatching { CallSpeechRecording(it) }.getOrNull() }
        var playedToEnd = false
        val epoch = generation
        val key = prefs.getString("minimax_api_key", "").orEmpty()
        val voice = voiceId?.takeIf(String::isNotBlank) ?: prefs.getString("minimax_voice_id", "").orEmpty()
        require(key.isNotBlank() && voice.isNotBlank()) { "MiniMax API Key 或 Voice ID 未配置" }
        val endpoint = prefs.getString("minimax_endpoint", LuluSpeechEngine.DEFAULT_MINIMAX_ENDPOINT).orEmpty()
        val group = prefs.getString("minimax_group_id", "").orEmpty()
        val url = endpoint + if (group.isBlank()) "" else "${if ('?' in endpoint) '&' else '?'}GroupId=${URLEncoder.encode(group, "UTF-8")}" 
        val request = (URL(url).openConnection() as HttpURLConnection).apply {
            requestMethod = "POST"; connectTimeout = 15_000; readTimeout = 60_000; doOutput = true
            setRequestProperty("Authorization", "Bearer $key")
            setRequestProperty("Content-Type", "application/json")
            setRequestProperty("Accept", "text/event-stream")
        }
        connection = request
        val rate = 24_000
        val audio = AudioTrack.Builder().setAudioAttributes(AudioAttributes.Builder()
            .setUsage(AudioAttributes.USAGE_VOICE_COMMUNICATION).setContentType(AudioAttributes.CONTENT_TYPE_SPEECH).build())
            .setAudioFormat(AudioFormat.Builder().setSampleRate(rate).setChannelMask(AudioFormat.CHANNEL_OUT_MONO)
                .setEncoding(AudioFormat.ENCODING_PCM_16BIT).build())
            .setBufferSizeInBytes(maxOf(AudioTrack.getMinBufferSize(rate, AudioFormat.CHANNEL_OUT_MONO, AudioFormat.ENCODING_PCM_16BIT), rate))
            .setTransferMode(AudioTrack.MODE_STREAM).build()
        track = audio
        val payload = JSONObject().put("model", prefs.getString("minimax_model", "speech-2.8-turbo"))
            .put("text", text).put("stream", true).put("output_format", "hex")
            .put("stream_options", JSONObject().put("exclude_aggregated_audio", true))
            .put("language_boost", prefs.getString("minimax_language_boost", "auto"))
            .put("voice_setting", JSONObject().put("voice_id", voice).put("speed", prefs.getFloat("minimax_speed", 1f).toDouble())
                .put("vol", prefs.getFloat("minimax_volume", 1f).toDouble()).put("pitch", prefs.getInt("minimax_pitch", 0)))
            .put("audio_setting", JSONObject().put("format", "pcm").put("sample_rate", rate).put("channel", 1))
        var bytesWritten = 0L
        var complete = false
        try {
            CallAudioRoute.preferredOutput?.let { check(audio.setPreferredDevice(it)) { "系统未接受电话声音输出设备" } }
            request.outputStream.use { it.write(payload.toString().toByteArray()) }
            check(request.responseCode in 200..299) { "MiniMax流式请求 HTTP ${request.responseCode}" }
            audio.play()
            request.inputStream.bufferedReader().use { reader ->
                while (epoch == generation) {
                    val line = reader.readLine() ?: break
                    if (!line.startsWith("data:")) continue
                    val raw = line.removePrefix("data:").trim()
                    if (raw == "[DONE]") break
                    val event = JSONObject(raw)
                    val status = event.optJSONObject("base_resp")
                    check(status == null || status.optInt("status_code", 0) == 0) { status?.optString("status_msg").orEmpty() }
                    val data = event.optJSONObject("data") ?: continue
                    val hex = data.optString("audio")
                    if (hex.isNotBlank()) {
                        require(hex.length % 4 == 0) { "MiniMax PCM 音频块格式无效" }
                        val bytes = ByteArray(hex.length / 2) { index -> hex.substring(index * 2, index * 2 + 2).toInt(16).toByte() }
                        var offset = 0
                        while (offset < bytes.size && epoch == generation) {
                            CallAudioRoute.preferredOutput?.let {
                                if (audio.preferredDevice?.id != it.id) check(audio.setPreferredDevice(it)) { "系统未接受电话声音输出设备" }
                            }
                            val count = audio.write(bytes, offset, bytes.size - offset, AudioTrack.WRITE_BLOCKING)
                            check(count > 0) { "流式音频播放失败" }
                            if (bytesWritten == 0L) onAudioStarted()
                            recording?.write(bytes, offset, count)
                            offset += count; bytesWritten += count
                        }
                    }
                    if (data.optInt("status") == 2) { complete = true; break }
                }
            }
            check(epoch != generation || (complete && bytesWritten > 0)) { "MiniMax流式音频未完整返回" }
            val drainDeadline = android.os.SystemClock.elapsedRealtime() + bytesWritten * 1000 / (rate * 2) + 5_000
            while (epoch == generation && (audio.playbackHeadPosition.toLong() and 0xffffffffL) < bytesWritten / 2) {
                check(android.os.SystemClock.elapsedRealtime() < drainDeadline) { "音频播放超时，请重试" }
                delay(20)
            }
            playedToEnd = epoch == generation && complete
            playedToEnd
        } finally {
            recording?.finish(playedToEnd)
            request.disconnect()
            if (connection === request) connection = null
            if (track === audio) { track = null; runCatching { audio.release() } }
        }
    }
}
