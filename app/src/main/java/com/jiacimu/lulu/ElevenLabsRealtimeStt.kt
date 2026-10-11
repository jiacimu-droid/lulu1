package com.jiacimu.lulu

import android.util.Base64
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

/**
 * Phone-specific Scribe v2 Realtime transport. It is NOT an ElevenLabs Agent,
 * a TTS engine, or a delayed /speech-to-text upload disguised as realtime.
 *
 * Only PCM from the actual user's detected utterance is streamed. Frames are
 * 16 kHz/16-bit mono; the last frame of each manual commit carries commit=true.
 * Intermediate commits preserve continuous monologues without asking the
 * character model to reply before the user has actually finished talking.
 */
internal class ElevenLabsRealtimeStt(
    private val scope: CoroutineScope,
    private val apiKey: String,
    private val active: () -> Boolean,
    private val speaking: () -> Boolean,
    private val onPartial: (String) -> Unit,
    private val onText: (String) -> Unit,
    private val onError: (String) -> Unit,
) {
    private data class Packet(val pcm: ByteArray, val commit: Boolean = false, val endTurn: Boolean = false)
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
    private val expectedCommits = ArrayDeque<Boolean>()
    private var lastFrame: ByteArray? = null
    private var framesSinceCommit = 0
    private var worker: Job? = null
    private var turnDelay: Job? = null
    private var socket: WebSocket? = null

    fun start() {
        check(apiKey.isNotBlank()) { "请先配置 ElevenLabs API Key" }
        val request = Request.Builder()
            .url("wss://api.elevenlabs.io/v1/speech-to-text/realtime" +
                "?model_id=scribe_v2_realtime&audio_format=pcm_16000" +
                "&language_code=zh&commit_strategy=manual")
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
                        val rendered = synchronized(lock) {
                            transcript.preview(event.optString("text"))
                        }
                        if (rendered.isNotBlank()) dispatch { onPartial(rendered) }
                    }
                    "committed_transcript" -> {
                        val finishedTurn: Boolean
                        val rendered: String
                        synchronized(lock) {
                            finishedTurn = if (expectedCommits.isEmpty()) false else expectedCommits.removeFirst()
                            rendered = transcript.commit(event.optString("text"))
                        }
                        if (rendered.isNotBlank()) dispatch { onPartial(rendered) }
                        if (finishedTurn) completeAfterBreath()
                    }
                    "auth_error", "scribe_auth_error" -> fail("ElevenLabs Realtime 验证失败，请检查 API Key 和权限")
                    "quota_exceeded", "scribe_quota_exceeded_error", "rate_limited", "throttled" ->
                        fail("ElevenLabs Realtime 识别额度或频率受限")
                    "error", "scribe_error", "queue_overflow" ->
                        fail("ElevenLabs Realtime 识别服务返回错误，请检查网络和识别权限")
                }
            }

            override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                if (!ready.isCompleted) ready.completeExceptionally(IOException("实时识别连接失败"))
                fail(if (response?.code == 401 || response?.code == 403)
                    "ElevenLabs Realtime 权限不足或 API Key 无效"
                else "ElevenLabs Realtime 识别连接断开，请检查网络")
            }

            override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
                if (!closed.get()) fail("ElevenLabs Realtime 识别连接中断")
            }
        })
        worker = scope.launch(Dispatchers.IO) {
            try {
                val webSocket = withTimeout(18_000) { ready.await() }
                for (packet in outgoing) {
                    if (closed.get() || !active()) break
                    val body = JSONObject()
                        .put("message_type", "input_audio_chunk")
                        .put("audio_base_64", Base64.encodeToString(packet.pcm, Base64.NO_WRAP))
                        .put("sample_rate", 16_000)
                    if (packet.commit) body.put("commit", true)
                    // Put the expected response in the same order as actual socket writes.
                    if (packet.commit) synchronized(lock) {
                        expectedCommits.addLast(packet.endTurn)
                    }
                    check(webSocket.send(body.toString())) { "识别连接无法发送音频" }
                }
            } catch (cancel: CancellationException) {
                throw cancel
            } catch (error: Exception) {
                fail("ElevenLabs Realtime 音频传输失败，请稍后重试")
            }
        }
    }

    /** Called from AudioRecord's IO thread only for speech/pre-roll frames. */
    fun speechFrame(pcm: ByteArray) {
        if (closed.get() || !active()) return
        synchronized(lock) {
            lastFrame?.let { enqueue(Packet(it)) }
            lastFrame = pcm
            framesSinceCommit++
            // Scribe auto-commits after ~36 seconds; proactively commit at ~20s.
            // This is NOT the user's conversational turn boundary.
            if (framesSinceCommit >= 200) {
                enqueue(Packet(checkNotNull(lastFrame), commit = true))
                lastFrame = null
                framesSinceCommit = 0
            }
        }
    }

    /** Actual VAD/mute turn boundary: commit the held last real PCM frame. */
    fun finishUtterance() {
        if (closed.get() || !active()) return
        synchronized(lock) {
            // If a periodic commit consumed the last frame exactly on the
            // boundary, 100ms silence can delimit the new final commit.
            val tail = lastFrame ?: ByteArray(3_200)
            enqueue(Packet(tail, commit = true, endTurn = true))
            lastFrame = null
            framesSinceCommit = 0
        }
    }

    private fun enqueue(packet: Packet) {
        if (closed.get()) return
        if (outgoing.trySend(packet).isFailure) fail("实时识别发送队列已满，请检查网络；本段语音不会被假装成功")
    }

    private fun completeAfterBreath() {
        turnDelay?.cancel()
        turnDelay = scope.launch(Dispatchers.Main) {
            delay(500)
            if (closed.get() || !active() || speaking()) return@launch
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
        dispatch { onError(message) }
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

    fun preview(partial: String): String = PhoneTranscriptAssembler.combine(stable, partial)

    fun commit(committed: String): String {
        stable = PhoneTranscriptAssembler.combine(stable, committed)
        return stable
    }

    fun takeFinal(): String = stable.trim().also { stable = "" }
}
