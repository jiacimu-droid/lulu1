package com.jiacimu.lulu

import android.util.Base64
import android.os.SystemClock
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeout
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import org.json.JSONObject
import java.io.IOException
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong

/**
 * Phone-specific Scribe v2 Realtime transport. It is NOT an ElevenLabs Agent,
 * a TTS engine, or a delayed /speech-to-text upload disguised as realtime.
 *
 * Continuous 16 kHz PCM reaches Scribe, including quiet syllables and silence.
 * Server VAD owns segmentation; the phone's amplitude gate never drops speech.
 * Only committed text enters the character model, after a continuation window.
 */
internal class ElevenLabsRealtimeStt(
    private val scope: CoroutineScope,
    private val apiKey: String,
    private val active: () -> Boolean,
    private val onSpeech: () -> Unit,
    private val onCaptureFinished: () -> Unit,
    private val endSilenceMs: Int = 2_500,
    private val onPartial: (String) -> Unit,
    private val onText: (String) -> Unit,
    private val onError: (String) -> Unit,
    private val endpoint: String = "wss://api.elevenlabs.io/v1/speech-to-text/realtime",
) {
    private data class Packet(val pcm: ByteArray)
    private val client = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .pingInterval(20, TimeUnit.SECONDS)
        .build()
    private val outgoing = Channel<Packet>(256)
    private val ready = CompletableDeferred<WebSocket>()
    private val closed = AtomicBoolean(false)
    private val reported = AtomicBoolean(false)
    private val lock = Any()
    private val transcript = ScribeRealtimeTranscriptState()
    private var worker: Job? = null
    private var turnDelay: Job? = null
    private var socket: WebSocket? = null
    private val lastWireSendAt = AtomicLong(SystemClock.elapsedRealtime())

    fun start() {
        check(apiKey.isNotBlank()) { "请先配置 ElevenLabs API Key" }
        val request = Request.Builder()
            .url(endpoint +
                "?model_id=scribe_v2_realtime&audio_format=pcm_16000" +
                "&language_code=zh&commit_strategy=vad&keepalive_interval_ms=3000" +
                "&vad_threshold=0.3&vad_silence_threshold_secs=${endSilenceMs.coerceIn(1450, 3000) / 1000.0}" +
                "&min_speech_duration_ms=100&min_silence_duration_ms=100")
            .header("xi-api-key", apiKey)
            .build()
        socket = client.newWebSocket(request, object : WebSocketListener() {
            override fun onOpen(webSocket: WebSocket, response: Response) {
                if (closed.get()) webSocket.close(1000, "ended")
                else ready.complete(webSocket)
            }

            override fun onMessage(webSocket: WebSocket, text: String) {
                if (closed.get() || !active()) return
                val event = runCatching { JSONObject(text) }.getOrNull() ?: return
                when (event.optString("message_type")) {
                    "partial_transcript" -> {
                        val partial = event.optString("text").trim()
                        // Empty/repeated keepalives are not new speech. A real
                        // changed partial cancels a pending conversational commit.
                        dispatch {
                            if (!synchronized(lock) { transcript.acceptPartial(partial) }) return@dispatch
                            turnDelay?.cancel()
                            onSpeech()
                            onPartial(synchronized(lock) { transcript.preview(partial) })
                        }
                    }
                    "committed_transcript" -> {
                        val committed = event.optString("text").trim()
                        if (committed.isNotBlank()) dispatch {
                            val rendered = synchronized(lock) { transcript.commit(committed) }
                            // Short speech can be committed without a preceding partial.
                            onSpeech()
                            onPartial(rendered)
                            onCaptureFinished()
                            completeAfterBreath()
                        }
                    }
                    "auth_error", "scribe_auth_error", "quota_exceeded", "scribe_quota_exceeded_error",
                    "rate_limited", "throttled", "error", "scribe_error", "queue_overflow",
                    "invalid_request", "transcriber_error", "input_error", "commit_throttled",
                    "unaccepted_terms", "resource_exhausted", "session_time_limit_exceeded",
                    "chunk_size_exceeded", "insufficient_audio_activity" -> {
                        fail(ScribeRealtimeFailurePolicy.serverEvent(event.optString("message_type")))
                    }
                }
            }

            override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                if (!ready.isCompleted) ready.completeExceptionally(IOException("实时识别连接失败"))
                fail(ScribeRealtimeFailurePolicy.connectionFailure(response?.code, t.javaClass.simpleName))
            }

            override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
                if (!closed.get()) fail(ScribeRealtimeFailurePolicy.remoteClose(code))
            }
        })
        worker = scope.launch(Dispatchers.IO) {
            try {
                val webSocket = withTimeout(18_000) { ready.await() }
                // ElevenLabs closes a Scribe socket after 15 seconds WITHOUT
                // any client messages. Voice-only VAD used to stop sending
                // all PCM when the companion spoke or both sides were silent.
                // Keep the same session alive with a small real silent PCM
                // chunk, without fabricating user speech or a commit.
                val keepalive = launch {
                    while (!closed.get() && active()) {
                        delay(4_000L)
                        if (SystemClock.elapsedRealtime() - lastWireSendAt.get() >= 3_500L) {
                            outgoing.trySend(Packet(ByteArray(3_200)))
                        }
                    }
                }
                try {
                    for (packet in outgoing) {
                        if (closed.get() || !active()) break
                        val body = JSONObject()
                            .put("message_type", "input_audio_chunk")
                            .put("audio_base_64", Base64.encodeToString(packet.pcm, Base64.NO_WRAP))
                            .put("sample_rate", 16_000)
                        check(webSocket.send(body.toString())) { "识别连接无法发送音频" }
                        lastWireSendAt.set(SystemClock.elapsedRealtime())
                    }
                } finally {
                    keepalive.cancel()
                }
            } catch (cancel: CancellationException) {
                throw cancel
            } catch (error: Exception) {
                fail(ScribeRealtimeFailurePolicy.sendFailure(error.javaClass.simpleName))
            }
        }
    }

    /** Every real capture frame, including low-volume speech and silence. */
    fun audioFrame(pcm: ByteArray) {
        if (closed.get() || !active()) return
        enqueue(Packet(pcm))
    }

    private fun enqueue(packet: Packet) {
        if (closed.get()) return
        if (outgoing.trySend(packet).isFailure) fail("实时识别发送队列已满，请检查网络；本段语音不会被假装成功")
    }

    private fun completeAfterBreath() {
        turnDelay?.cancel()
        turnDelay = scope.launch(Dispatchers.Main) {
            delay(500)
            if (closed.get() || !active()) return@launch
            val finalText = synchronized(lock) { transcript.takeFinal() }
            if (finalText.isNotBlank()) onText(finalText)
            else onError("Scribe Realtime 未识别到完整文字，请再说一次")
        }
    }

    private fun dispatch(block: () -> Unit) {
        scope.launch(Dispatchers.Main) {
            if (!closed.get() && active()) block()
        }
    }

    private fun fail(message: String) {
        if (closed.get() || !active() || !reported.compareAndSet(false, true)) return
        // An error must remain visible even though stop() closes the socket.
        scope.launch(Dispatchers.Main) {
            if (active()) onError(message)
        }
        stop()
    }

    fun stop() {
        if (!closed.compareAndSet(false, true)) return
        turnDelay?.cancel()
        outgoing.close()
        worker?.cancel()
        socket?.close(1000, "call finished")
        client.dispatcher.executorService.shutdown()
        client.connectionPool.evictAll()
    }
}

/** Only committed server events are stable; never send partial words to the LLM. */
internal class ScribeRealtimeTranscriptState {
    private var stable = ""
    private var partial = ""

    fun acceptPartial(text: String): Boolean {
        if (text.isBlank() || text == partial) return false
        partial = text
        return true
    }

    fun preview(partial: String): String = PhoneTranscriptAssembler.combine(stable, partial)

    fun commit(committed: String): String {
        partial = ""
        stable = PhoneTranscriptAssembler.combine(stable, committed)
        return stable
    }

    fun takeFinal(): String = stable.trim().also { stable = ""; partial = "" }
}
