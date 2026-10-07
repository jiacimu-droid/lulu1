package com.jiacimu.lulu

import android.content.Context
import android.util.Base64
import kotlinx.coroutines.*
import okhttp3.*
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.util.UUID
import java.util.concurrent.TimeUnit

/** Personal account direct call input; no app-owned cloud server is required. */
internal class ProviderCallInput(private val context: Context, private val scope: CoroutineScope,
    private val accept: () -> Boolean, private val onReady: () -> Unit,
    private val onLevel: (Float) -> Unit, private val onSpeech: () -> Unit,
    private val onPartial: (String) -> Unit, private val onText: (String) -> Unit,
    private val onStatus: (String) -> Unit, private val onError: (String) -> Unit) {
    private val microphone = CallAudioInput(scope)
    private val client = OkHttpClient.Builder().readTimeout(0, TimeUnit.MILLISECONDS).build()
    private var socket: WebSocket? = null
    private var transcription: Job? = null
    @Volatile private var http: HttpURLConnection? = null
    private var generation = 0L
    private var segmentGeneration = 0L

    fun start(provider: String) {
        stop()
        val epoch = generation
        val p = context.getSharedPreferences("lulu_advanced_settings", 0)
        fun listen() = microphone.start(accept, onReady, onLevel, {
            segmentGeneration++; transcription?.cancel(); http?.disconnect(); onSpeech()
        }, onFrame = { bytes ->
            if (provider == "elevenlabs") socket?.send(JSONObject().put("message_type", "input_audio_chunk")
                .put("audio_base_64", Base64.encodeToString(bytes, Base64.NO_WRAP)).toString())
        }, onSegment = segment@{ bytes ->
            if (provider != "minimax") return@segment
            val segment = segmentGeneration
            onStatus("已收音，MiniMax 正在识别…")
            transcription = scope.launch {
                runCatching { transcribeMiniMax(bytes) { partial ->
                    if (epoch == generation && segment == segmentGeneration) onPartial(partial)
                } }.onSuccess { text ->
                    if (epoch == generation && segment == segmentGeneration) {
                        if (text.isBlank()) onError("MiniMax 没有识别出文字，请重试或检查录音音量") else onText(text)
                    }
                }.onFailure { error -> if (error !is CancellationException && epoch == generation && segment == segmentGeneration) onError("MiniMax 识别失败：${error.message}") }
            }
        }, onError = onError, threshold = p.getFloat("voice_vad_threshold", 350f), endSilenceMs = p.getInt("voice_end_silence_ms", 500))
        if (provider == "minimax") { listen(); return }
        onStatus("正在连接 ElevenLabs 语音识别…")
        val key = p.getString("eleven_api_key", "").orEmpty()
        val request = Request.Builder().url("wss://api.elevenlabs.io/v1/speech-to-text/realtime?model_id=scribe_v2_realtime&audio_format=pcm_16000&language_code=zh&commit_strategy=vad&vad_silence_threshold_secs=0.8&keepalive_interval_ms=2000")
            .header("xi-api-key", key).build()
        socket = client.newWebSocket(request, object : WebSocketListener() {
            override fun onMessage(webSocket: WebSocket, text: String) {
                scope.launch {
                    if (epoch != generation) return@launch
                    runCatching {
                        val event = JSONObject(text)
                        when (event.optString("message_type")) {
                            "session_started" -> listen()
                            "partial_transcript" -> if (accept()) onPartial(event.optString("text"))
                            "committed_transcript" -> if (accept() && event.optString("text").isNotBlank()) onText(event.optString("text"))
                            "warning" -> Unit
                            else -> if (event.has("error")) onError("ElevenLabs 识别失败：${event.optString("error").take(200)}")
                        }
                    }.onFailure { onError("ElevenLabs 返回无法解析的识别事件") }
                }
            }
            override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                scope.launch { if (epoch == generation) { microphone.stop(); onError("ElevenLabs 识别连接失败${response?.code?.let { " HTTP $it" }.orEmpty()}：${t.message}") } }
            }
            override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
                scope.launch { if (epoch == generation) { microphone.stop(); onError("ElevenLabs 识别连接已断开，请重新收音") } }
            }
        })
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

    fun stop() { generation++; segmentGeneration++; microphone.stop(); transcription?.cancel(); http?.disconnect(); socket?.cancel(); socket = null }
}
