package com.jiacimu.lulu

import android.content.Context
import android.media.MediaPlayer
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import java.util.Locale

internal class LuluSpeechEngine(context: Context) {
    private data class PendingSynthesis(
        val utteranceId: String,
        val generation: Long,
        val file: File,
        val fallbackText: String,
    )

    private val appContext = context.applicationContext
    private val prefs = appContext.getSharedPreferences("lulu_advanced_settings", Context.MODE_PRIVATE)
    private var player: MediaPlayer? = null
    @Volatile private var playbackGeneration = 0L
    @Volatile private var activeUtteranceId: String? = null
    @Volatile private var pendingSynthesis: PendingSynthesis? = null
    @Volatile private var completionCallback: (() -> Unit)? = null
    private val systemTts = TextToSpeech(appContext) {}

    init {
        systemTts.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
            override fun onStart(utteranceId: String?) = Unit

            override fun onDone(utteranceId: String?) {
                val synthesis = pendingSynthesis
                if (utteranceId != null && synthesis?.utteranceId == utteranceId) {
                    pendingSynthesis = null
                    if (synthesis.generation == playbackGeneration && synthesis.file.exists() && synthesis.file.length() > 0L) {
                        playAudioFile(synthesis.file, synthesis.generation, deleteAfterPlayback = false)
                    } else if (synthesis.generation == playbackGeneration) {
                        finishPlayback()
                    }
                    return
                }
                if (utteranceId != null && utteranceId == activeUtteranceId) finishPlayback()
            }

            @Deprecated("Deprecated in Java")
            override fun onError(utteranceId: String?) {
                handleTtsError(utteranceId)
            }

            override fun onError(utteranceId: String?, errorCode: Int) {
                handleTtsError(utteranceId)
            }
        })
    }

    fun speak(text: String, scope: CoroutineScope, onFinished: (() -> Unit)? = null) {
        if (!prefs.getBoolean("tts_enabled", true) || text.isBlank()) {
            onFinished?.invoke()
            return
        }
        stop()
        completionCallback = onFinished
        val requestGeneration = ++playbackGeneration
        if (prefs.getString("tts_provider", "system") == "minimax") {
            scope.launch {
                runCatching { requestMiniMaxAudio(text) }
                    .onSuccess { bytes ->
                        if (requestGeneration == playbackGeneration) {
                            val audioFile = File.createTempFile("minimax-", ".mp3", appContext.cacheDir)
                            audioFile.writeBytes(bytes)
                            playAudioFile(audioFile, requestGeneration, deleteAfterPlayback = true)
                        }
                    }
                    .onFailure {
                        if (requestGeneration == playbackGeneration) speakWithSystem(text, requestGeneration)
                    }
            }
        } else {
            speakWithSystem(text, requestGeneration)
        }
    }

    /** Generates speech once, persists the audio file, then plays that exact file. */
    fun speakAndCache(
        text: String,
        cacheBaseFile: File,
        scope: CoroutineScope,
        onFinished: (() -> Unit)? = null,
    ) {
        if (!prefs.getBoolean("tts_enabled", true) || text.isBlank()) {
            onFinished?.invoke()
            return
        }
        stop()
        completionCallback = onFinished
        val requestGeneration = ++playbackGeneration
        cacheBaseFile.parentFile?.mkdirs()

        val existing = cachedAudioFile(cacheBaseFile)
        if (existing != null) {
            existing.setLastModified(System.currentTimeMillis())
            playAudioFile(existing, requestGeneration, deleteAfterPlayback = false)
            return
        }

        if (prefs.getString("tts_provider", "system") == "minimax") {
            val target = File(cacheBaseFile.parentFile, "${cacheBaseFile.name}.mp3")
            scope.launch {
                runCatching { requestMiniMaxAudio(text) }
                    .onSuccess { bytes ->
                        if (requestGeneration == playbackGeneration) {
                            target.writeBytes(bytes)
                            playAudioFile(target, requestGeneration, deleteAfterPlayback = false)
                        }
                    }
                    .onFailure {
                        if (requestGeneration == playbackGeneration) {
                            synthesizeSystemToCache(
                                text = text,
                                target = File(cacheBaseFile.parentFile, "${cacheBaseFile.name}.wav"),
                                generation = requestGeneration,
                            )
                        }
                    }
            }
        } else {
            synthesizeSystemToCache(
                text = text,
                target = File(cacheBaseFile.parentFile, "${cacheBaseFile.name}.wav"),
                generation = requestGeneration,
            )
        }
    }

    fun playCached(file: File, onFinished: (() -> Unit)? = null): Boolean {
        if (!file.exists() || file.length() <= 0L) return false
        stop()
        completionCallback = onFinished
        val generation = ++playbackGeneration
        file.setLastModified(System.currentTimeMillis())
        playAudioFile(file, generation, deleteAfterPlayback = false)
        return true
    }

    fun cachedAudioFile(cacheBaseFile: File): File? {
        val mp3 = File(cacheBaseFile.parentFile, "${cacheBaseFile.name}.mp3")
        if (mp3.exists() && mp3.length() > 0L) return mp3
        val wav = File(cacheBaseFile.parentFile, "${cacheBaseFile.name}.wav")
        if (wav.exists() && wav.length() > 0L) return wav
        return null
    }

    suspend fun previewMiniMax(text: String): Result<Unit> = runCatching {
        stop()
        val requestGeneration = ++playbackGeneration
        val audio = requestMiniMaxAudio(text.trim().ifBlank { "你好，我是露露。这个声音听起来还合适吗？" })
        check(requestGeneration == playbackGeneration) { "试听已取消" }
        val audioFile = File.createTempFile("minimax-preview-", ".mp3", appContext.cacheDir)
        audioFile.writeBytes(audio)
        playAudioFile(audioFile, requestGeneration, deleteAfterPlayback = true)
    }

    fun stop() {
        playbackGeneration++
        activeUtteranceId = null
        pendingSynthesis?.file?.delete()
        pendingSynthesis = null
        systemTts.stop()
        player?.release()
        player = null
        val callback = completionCallback
        completionCallback = null
        callback?.invoke()
    }

    fun shutdown() {
        stop()
        systemTts.shutdown()
    }

    private fun configureSystemTts() {
        val locale = Locale.forLanguageTag(prefs.getString("tts_language", "zh-CN") ?: "zh-CN")
        systemTts.language = locale
        systemTts.setSpeechRate(prefs.getFloat("tts_rate", 1f))
        systemTts.setPitch(prefs.getFloat("tts_pitch", 1f))
    }

    private fun speakWithSystem(text: String, generation: Long) {
        configureSystemTts()
        val utteranceId = "lulu-$generation-${System.nanoTime()}"
        activeUtteranceId = utteranceId
        val result = systemTts.speak(text, TextToSpeech.QUEUE_FLUSH, null, utteranceId)
        if (result == TextToSpeech.ERROR) finishPlayback()
    }

    private fun synthesizeSystemToCache(text: String, target: File, generation: Long) {
        configureSystemTts()
        target.parentFile?.mkdirs()
        target.delete()
        val utteranceId = "lulu-cache-$generation-${System.nanoTime()}"
        pendingSynthesis = PendingSynthesis(utteranceId, generation, target, text)
        val result = systemTts.synthesizeToFile(text, null, target, utteranceId)
        if (result == TextToSpeech.ERROR) {
            pendingSynthesis = null
            target.delete()
            if (generation == playbackGeneration) speakWithSystem(text, generation)
        }
    }

    private fun handleTtsError(utteranceId: String?) {
        val synthesis = pendingSynthesis
        if (utteranceId != null && synthesis?.utteranceId == utteranceId) {
            pendingSynthesis = null
            synthesis.file.delete()
            if (synthesis.generation == playbackGeneration) {
                speakWithSystem(synthesis.fallbackText, synthesis.generation)
            }
            return
        }
        if (utteranceId != null && utteranceId == activeUtteranceId) finishPlayback()
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

    private fun playAudioFile(file: File, generation: Long, deleteAfterPlayback: Boolean) {
        player?.release()
        player = MediaPlayer().apply {
            setDataSource(file.absolutePath)
            setOnCompletionListener {
                it.release()
                if (player === it) player = null
                if (deleteAfterPlayback) file.delete()
                if (generation == playbackGeneration) finishPlayback()
            }
            setOnErrorListener { mediaPlayer, _, _ ->
                mediaPlayer.release()
                if (player === mediaPlayer) player = null
                if (deleteAfterPlayback) file.delete()
                if (generation == playbackGeneration) finishPlayback()
                true
            }
            prepare()
            start()
        }
    }

    private fun finishPlayback() {
        activeUtteranceId = null
        val callback = completionCallback
        completionCallback = null
        callback?.invoke()
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
