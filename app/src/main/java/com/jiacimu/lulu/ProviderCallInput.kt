package com.jiacimu.lulu

import android.content.Context
import kotlinx.coroutines.*
import kotlinx.coroutines.channels.Channel
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
    @Volatile private var generation = 0L
    @Volatile private var capturingVoice = false
    private data class AudioChunk(val pcm: ByteArray, val isFinal: Boolean)
    private var segments: Channel<AudioChunk>? = null
    @Volatile private var capturePaused = false
    private var pauseReleaseJob: Job? = null

    @OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
    fun start() {
        stop()
        val epoch = generation
        val prefs = context.getSharedPreferences("lulu_advanced_settings", 0)
        // A running user monologue can outlast cloud upload latency; never
        // silently drop earlier chunks because an arbitrary six-item queue filled.
        val queue = Channel<AudioChunk>(Channel.UNLIMITED)
        segments = queue
        // A second utterance must not cancel or discard the first utterance
        // while cloud ASR is still working. Serialize responses in mic order.
        transcription = scope.launch {
            val phrase = StringBuilder()
            var failed = false
            for (chunk in queue) {
                if (epoch != generation) break
                val text = try {
                    transcribe(chunk.pcm) { partial ->
                        if (epoch == generation && !failed) onPartial(
                            PhoneTranscriptAssembler.combine(phrase.toString(), partial)
                        )
                    }
                } catch (error: CancellationException) {
                    throw error
                } catch (error: Exception) {
                    failed = true
                    if (epoch == generation) onError(
                        "这一段语音转写失败，不能当作完整句子发送：${error.message}"
                    )
                    ""
                }
                if (epoch != generation) break
                if (text.isNotBlank() && !failed) {
                    val combined = PhoneTranscriptAssembler.combine(phrase.toString(), text)
                    phrase.clear()
                    phrase.append(combined)
                }
                // Only the actual silence endpoint marks the conversational
                // turn done; 12-second upload chunks NEVER start the reply.
                if (chunk.isFinal) {
                    // The user may already have continued speaking while the
                    // previous cloud request was in flight. Never answer midway.
                    delay(550)
                    // Resume processing the next queued audio immediately if
                    // the person took a breath and continued talking.
                    if (epoch != generation || capturingVoice || !queue.isEmpty) continue
                    if (!failed && phrase.isNotBlank()) onText(phrase.toString().trim())
                    else if (!failed) onError("没有识别出文字，请提高麦克风灵敏度或再说一次")
                    phrase.clear()
                    failed = false
                }
            }
        }
        capturePaused = false
        startMicrophone(epoch, queue, prefs)
    }

    private fun startMicrophone(
        epoch: Long,
        queue: Channel<AudioChunk>,
        prefs: android.content.SharedPreferences,
    ) {
        microphone.start(
            accept = { epoch == generation && !capturePaused && accept() },
            onReady = { if (epoch == generation) onReady() },
            onLevel = { value -> if (epoch == generation) onLevel(value) },
            onSpeech = {
                if (epoch == generation) {
                    capturingVoice = true
                    onSpeech()
                }
            },
            onSegment = { bytes, completed ->
                capturingVoice = !completed
                if (epoch == generation) {
                    onStatus(if (completed) "识别完整语句中…" else "持续收音并分段识别中…")
                    if (!queue.trySend(AudioChunk(bytes, completed)).isSuccess)
                        onError("录音队列已关闭，本段语音未提交识别")
                }
            },
            onError = { message ->
                if (epoch == generation) {
                    capturingVoice = false
                    onError(message)
                }
            },
            threshold = prefs.getFloat("voice_vad_threshold", 350f),
            endSilenceMs = prefs.getInt("voice_end_silence_ms", 650),
        )
    }

    /**
     * End any already spoken fragment before releasing hardware. The capture
     * loop sees accept=false on its next ~100ms frame and emits the final PCM
     * buffer; cancellation a fraction of a second later frees AudioRecord.
     */
    fun pauseCapture() {
        if (capturePaused) return
        capturePaused = true
        pauseReleaseJob?.cancel()
        pauseReleaseJob = scope.launch {
            delay(250)
            if (capturePaused) {
                microphone.stop()
                capturingVoice = false
            }
        }
        onLevel(0f)
    }

    fun resumeCapture() {
        if (!capturePaused) return
        val queue = segments ?: return
        pauseReleaseJob?.cancel()
        pauseReleaseJob = null
        capturePaused = false
        // The old recorder may still be alive during the short drain window;
        // restart cleanly to avoid using a stopped AudioRecord instance.
        microphone.stop()
        startMicrophone(generation, queue, context.getSharedPreferences("lulu_advanced_settings", 0))
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
            // No fixed language header: MiniMax can then recognize mixed
            // Mandarin/English/Japanese utterances within one conversation.
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
            if (connection.contentType.orEmpty().lowercase().contains("text/event-stream")) {
                val transcript = MiniMaxAsrStreamAccumulator()
                var finished = false
                connection.inputStream.bufferedReader().use { reader ->
                    while (!finished) {
                        val line = reader.readLine() ?: break
                        if (!line.startsWith("data:")) continue
                        val raw = line.removePrefix("data:").trim()
                        if (raw == "[DONE]") { finished = true; break }
                        if (raw.isBlank()) continue
                        val event = JSONObject(raw)
                        val status = event.optJSONObject("base_resp")?.optInt("status_code", 0) ?: 0
                        check(status == 0) { "MiniMax 识别服务返回错误码 $status" }
                        val latestText = transcript.accept(event)
                        if (latestText.isNotBlank()) withContext(Dispatchers.Main) {
                            onIncremental(latestText)
                        }
                        finished = event.optBoolean("finish") || event.optBoolean("is_final")
                    }
                }
                check(finished) { "识别流中断，请重新说一次" }
                transcript.value.trim()
            } else {
                val result = JSONObject(connection.inputStream.bufferedReader().use { it.readText() })
                val status = result.optJSONObject("base_resp")?.optInt("status_code", 0) ?: 0
                check(status == 0) { "MiniMax 识别服务返回错误码 $status" }
                result.optString("text").ifBlank {
                    result.optJSONObject("data")?.optString("text").orEmpty()
                }.trim()
            }
        } finally { connection.disconnect(); if (http === connection) http = null }
    }

    fun stop() {
        pauseReleaseJob?.cancel()
        pauseReleaseJob = null
        generation++
        capturingVoice = false
        capturePaused = false
        microphone.stop()
        segments?.close()
        segments = null
        transcription?.cancel()
        transcription = null
        http?.disconnect()
        http = null
    }
}
