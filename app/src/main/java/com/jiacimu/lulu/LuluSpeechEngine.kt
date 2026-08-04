package com.jiacimu.lulu

import android.content.Context
import android.media.MediaPlayer
import android.speech.tts.TextToSpeech
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import java.util.Locale

internal class LuluSpeechEngine(context: Context) {
    private val appContext = context.applicationContext
    private val prefs = appContext.getSharedPreferences("lulu_advanced_settings", Context.MODE_PRIVATE)
    private var player: MediaPlayer? = null
    @Volatile private var playbackGeneration = 0L
    private val systemTts = TextToSpeech(appContext) {}

    fun speak(text: String, scope: CoroutineScope) {
        if (!prefs.getBoolean("tts_enabled", true) || text.isBlank()) return
        stop()
        val requestGeneration = ++playbackGeneration
        if (prefs.getString("tts_provider", "system") == "minimax") {
            scope.launch {
                runCatching { requestMiniMaxAudio(text) }
                    .onSuccess { if (requestGeneration == playbackGeneration) playMiniMaxAudio(it) }
                    .onFailure { if (requestGeneration == playbackGeneration) speakWithSystem(text) }
            }
        } else {
            speakWithSystem(text)
        }
    }

    suspend fun previewMiniMax(text: String): Result<Unit> = runCatching {
        stop()
        val requestGeneration = ++playbackGeneration
        val audio = requestMiniMaxAudio(text.trim().ifBlank { "你好，我是露露。这个声音听起来还合适吗？" })
        check(requestGeneration == playbackGeneration) { "试听已取消" }
        playMiniMaxAudio(audio)
    }

    fun stop() {
        playbackGeneration++
        systemTts.stop()
        player?.release()
        player = null
    }

    fun shutdown() {
        stop()
        systemTts.shutdown()
    }

    private fun speakWithSystem(text: String) {
        val locale = Locale.forLanguageTag(prefs.getString("tts_language", "zh-CN") ?: "zh-CN")
        systemTts.language = locale
        systemTts.setSpeechRate(prefs.getFloat("tts_rate", 1f))
        systemTts.setPitch(prefs.getFloat("tts_pitch", 1f))
        systemTts.speak(text, TextToSpeech.QUEUE_FLUSH, null, "lulu-${System.nanoTime()}")
    }

    private suspend fun requestMiniMaxAudio(text: String): ByteArray = withContext(Dispatchers.IO) {
        val apiKey = prefs.getString("minimax_api_key", "").orEmpty().trim()
        val voiceId = prefs.getString("minimax_voice_id", "").orEmpty().trim()
        require(apiKey.isNotBlank()) { "MiniMax API Key 未配置" }
        require(voiceId.isNotBlank()) { "MiniMax Voice ID 未配置" }
        val endpoint = prefs.getString("minimax_endpoint", DEFAULT_MINIMAX_ENDPOINT)
            .orEmpty().trim().ifBlank { DEFAULT_MINIMAX_ENDPOINT }
        val groupId = prefs.getString("minimax_group_id", "").orEmpty().trim()
        val requestUrl = if (groupId.isBlank()) {
            endpoint
        } else {
            val separator = if (endpoint.contains('?')) '&' else '?'
            "$endpoint${separator}GroupId=${URLEncoder.encode(groupId, Charsets.UTF_8.name())}"
        }
        val connection = (URL(requestUrl).openConnection() as HttpURLConnection).apply {
            requestMethod = "POST"
            connectTimeout = 15_000
            readTimeout = 60_000
            doOutput = true
            setRequestProperty("Authorization", "Bearer $apiKey")
            setRequestProperty("Content-Type", "application/json")
        }
        val payload = JSONObject().apply {
            put("model", prefs.getString("minimax_model", "speech-2.8-turbo"))
            put("text", text)
            put("stream", false)
            put("output_format", "hex")
            put("language_boost", prefs.getString("minimax_language_boost", "auto"))
            put("voice_setting", JSONObject().apply {
                put("voice_id", voiceId)
                put("speed", prefs.getFloat("minimax_speed", 1f).toDouble())
                put("vol", prefs.getFloat("minimax_volume", 1f).toDouble())
                put("pitch", prefs.getInt("minimax_pitch", 0))
            })
            put("audio_setting", JSONObject().apply {
                put("sample_rate", 32_000)
                put("bitrate", 128_000)
                put("format", "mp3")
                put("channel", 1)
            })
        }
        try {
            connection.outputStream.use { it.write(payload.toString().toByteArray()) }
            val body = (if (connection.responseCode in 200..299) connection.inputStream else connection.errorStream)
                ?.bufferedReader()?.use { it.readText() }.orEmpty()
            val json = JSONObject(body)
            val statusCode = json.optJSONObject("base_resp")?.optInt("status_code", -1) ?: -1
            if (connection.responseCode !in 200..299 || statusCode != 0) {
                val status = json.optJSONObject("base_resp")?.optString("status_msg").orEmpty()
                val hint = when {
                    endpoint.contains("minimax.io") -> "当前是国际线路；国内账号请改选国内线路并填写 Group ID。"
                    endpoint.contains("minimax.chat") && groupId.isBlank() -> "国内兼容线路需要填写 Group ID。"
                    else -> "请核对 API Key、Group ID、Voice ID 与账号所属区域。"
                }
                error("${status.ifBlank { "MiniMax 语音请求失败" }}（HTTP ${connection.responseCode} / $statusCode）\n$hint")
            }
            json.getJSONObject("data").getString("audio").hexToBytes()
        } finally {
            connection.disconnect()
        }
    }

    private fun playMiniMaxAudio(bytes: ByteArray) {
        val audioFile = java.io.File.createTempFile("minimax-", ".mp3", appContext.cacheDir)
        audioFile.writeBytes(bytes)
        player = MediaPlayer().apply {
            setDataSource(audioFile.absolutePath)
            setOnCompletionListener {
                it.release()
                if (player === it) player = null
                audioFile.delete()
            }
            setOnErrorListener { mediaPlayer, _, _ ->
                mediaPlayer.release()
                if (player === mediaPlayer) player = null
                audioFile.delete()
                true
            }
            prepare()
            start()
        }
    }

    private fun String.hexToBytes(): ByteArray {
        require(length % 2 == 0) { "MiniMax 返回了无效音频" }
        return ByteArray(length / 2) { index ->
            substring(index * 2, index * 2 + 2).toInt(16).toByte()
        }
    }

    companion object {
        const val DEFAULT_MINIMAX_ENDPOINT = "https://api.minimaxi.com/v1/t2a_v2"
    }
}
