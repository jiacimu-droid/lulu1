package com.jiacimu.lulu

import android.content.Context
import kotlinx.coroutines.*
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.util.UUID

/** Real PCM + segmented cloud ASR, independent of ElevenLabs/MiniMax speech synthesis. */
internal class ProviderCallInput(private val context: Context, private val scope: CoroutineScope,
    private val sttEngine: String,
    private val accept: () -> Boolean, private val onReady: () -> Unit,
    private val onLevel: (Float) -> Unit, private val onSpeech: () -> Unit,
    private val onPartial: (String) -> Unit, private val onText: (String) -> Unit,
    private val onStatus: (String) -> Unit, private val onError: (String) -> Unit) {
    private val microphone = CallAudioInput(scope)
    private var transcription: Job? = null
    @Volatile private var http: HttpURLConnection? = null
    private var generation = 0L
    private var segmentGeneration = 0L

    fun start() {
        stop()
        val epoch = generation
        val p = context.getSharedPreferences("lulu_advanced_settings", 0)
        fun listen() = microphone.start(accept, onReady, onLevel, {
            segmentGeneration++; transcription?.cancel(); http?.disconnect(); onSpeech()
        }, onSegment = segment@{ bytes ->
            val segment = segmentGeneration
            onStatus("已收音，${CallVoiceConfiguration.sttLabel(sttEngine)}正在识别…")
            transcription = scope.launch {
                runCatching { transcribe(bytes) { partial ->
                    if (epoch == generation && segment == segmentGeneration) onPartial(partial)
                } }.onSuccess { text ->
                    if (epoch == generation && segment == segmentGeneration) {
                        if (text.isBlank()) onError("没有识别出文字，请重试或检查录音音量") else onText(text)
                    }
                }.onFailure { error -> if (error !is CancellationException && epoch == generation && segment == segmentGeneration) onError("语音识别失败：${error.message}") }
            }
        }, onError = onError, threshold = p.getFloat("voice_vad_threshold", 350f), endSilenceMs = p.getInt("voice_end_silence_ms", 500))
        listen()
    }

    private suspend fun transcribe(pcm: ByteArray, onIncremental: (String) -> Unit): String =
        when (sttEngine) {
            "groq" -> transcribeGroq(pcm)
            "minimax" -> transcribeMiniMax(pcm, onIncremental)
            else -> error("未配置可用云端语音识别")
        }

    private suspend fun transcribeGroq(pcm: ByteArray): String = withContext(Dispatchers.IO) {
        val prefs = context.getSharedPreferences("lulu_advanced_settings", 0)
        val key = prefs.getString("groq_asr_key", "").orEmpty().trim()
        require(key.isNotBlank()) { "请在语音设置填写 Groq API Key" }
        val model = prefs.getString("groq_asr_model", "whisper-large-v3-turbo")
            .orEmpty().takeIf { it in setOf("whisper-large-v3", "whisper-large-v3-turbo") }
            ?: "whisper-large-v3-turbo"
        val boundary = "lulu-${UUID.randomUUID()}"
        val connection = (URL("https://api.groq.com/openai/v1/audio/transcriptions")
            .openConnection() as HttpURLConnection).apply {
            requestMethod = "POST"
            doOutput = true
            connectTimeout = 15_000; readTimeout = 30_000
            setRequestProperty("Authorization", "Bearer $key")
            setRequestProperty("Content-Type", "multipart/form-data; boundary=$boundary")
        }
        http = connection
        try {
            connection.outputStream.use { out ->
                fun field(name: String, value: String) {
                    out.write("--$boundary\r\nContent-Disposition: form-data; name=\"$name\"\r\n\r\n$value\r\n".toByteArray())
                }
                field("model", model)
                field("language", "zh")
                field("response_format", "json")
                out.write("--$boundary\r\nContent-Disposition: form-data; name=\"file\"; filename=\"speech.wav\"\r\nContent-Type: audio/wav\r\n\r\n".toByteArray())
                out.write(pcmWav(pcm))
                out.write("\r\n--$boundary--\r\n".toByteArray())
            }
            val code = connection.responseCode
            check(code in 200..299) {
                when (code) {
                    401, 403 -> "Groq API Key 无效或权限不足（HTTP $code）"
                    429 -> "Groq 免费层调用次数/额度已达上限（HTTP 429）"
                    else -> "Groq 语音识别 HTTP $code"
                }
            }
            // Never surface provider response bodies: they may echo user audio text or credentials.
            JSONObject(connection.inputStream.bufferedReader().use { it.readText() }).optString("text").trim()
        } finally {
            connection.disconnect()
            if (http === connection) http = null
        }
    }

    private suspend fun transcribeMiniMax(pcm: ByteArray, onIncremental: (String) -> Unit): String = withContext(Dispatchers.IO) {
        val p = context.getSharedPreferences("lulu_advanced_settings", 0)
        val configured = p.getString("minimax_asr_endpoint", "").orEmpty().trim()
        val endpoint = configured.ifBlank { CallVoiceConfiguration.miniAsrEndpoint(p.getString("minimax_endpoint", LuluSpeechEngine.DEFAULT_MINIMAX_ENDPOINT).orEmpty()) }
        require(URL(endpoint).protocol == "https") { "语音识别接口必须使用 HTTPS" }
        val boundary = "lulu-${UUID.randomUUID()}"
        val connection = (URL(endpoint).openConnection() as HttpURLConnection).apply {
            requestMethod = "POST"; doOutput = true; connectTimeout = 15000; readTimeout = 30000
            setRequestProperty("Authorization", "Bearer ${p.getString("minimax_api_key", "")}")
            setRequestProperty("Content-Type", "multipart/form-data; boundary=$boundary")
            setRequestProperty("language", "zh")
        }
        http = connection
        try {
            connection.outputStream.use { out ->
                fun field(name: String, value: String) { out.write("--$boundary\r\nContent-Disposition: form-data; name=\"$name\"\r\n\r\n$value\r\n".toByteArray()) }
                field("model", p.getString("minimax_asr_model", "asr-1.0").orEmpty())
                field("response_format", "json")
                field("stream", "true")
                out.write("--$boundary\r\nContent-Disposition: form-data; name=\"file\"; filename=\"speech.wav\"\r\nContent-Type: audio/wav\r\n\r\n".toByteArray())
                out.write(pcmWav(pcm)); out.write("\r\n--$boundary--\r\n".toByteArray())
            }
            val code = connection.responseCode
            if (code !in 200..299) {
                // Never display vendor bodies that may reflect credentials or uploaded private speech.
                throw IllegalStateException("HTTP $code（请检查语音识别权限、余额及接口区域）")
            }
            if (connection.contentType.orEmpty().contains("text/event-stream")) {
                val text = StringBuilder()
                var finished = false
                connection.inputStream.bufferedReader().use { reader ->
                    while (!finished) {
                        val line = reader.readLine() ?: break
                        if (!line.startsWith("data:")) continue
                        val raw = line.removePrefix("data:").trim()
                        if (raw == "[DONE]") break
                        val event = JSONObject(raw)
                        text.append(event.optString("delta"))
                        withContext(Dispatchers.Main) { onIncremental(text.toString()) }
                        finished = event.optBoolean("finish")
                    }
                }
                check(finished) { "识别流中断，请重新说一次" }
                text.toString().trim()
            } else JSONObject(connection.inputStream.bufferedReader().use { it.readText() }).optString("text").trim()
        } finally { connection.disconnect(); if (http === connection) http = null }
    }

    fun stop() { generation++; segmentGeneration++; microphone.stop(); transcription?.cancel(); http?.disconnect() }
}
