package com.jiacimu.lulu

import android.util.Base64
import okhttp3.*
import org.json.JSONArray
import org.json.JSONObject
import java.io.IOException
import java.io.InputStream
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

/** Adapts TTD WebSocket audio to the same streamed PCM/MP3 reader as HTTP. */
internal class ElevenLabsDialogueStream(key: String, model: String, voice: String, text: String, format: String) : InputStream() {
    private data class Frame(val bytes: ByteArray? = null, val error: String? = null)
    private val frames = LinkedBlockingQueue<Frame>(128)
    private val ended = AtomicBoolean(false)
    @Volatile private var closed = false
    private var chunk = ByteArray(0)
    private var offset = 0
    private var eof = false
    private var received = 0L
    private val socket: WebSocket

    init {
        val request = Request.Builder()
            .url("wss://api.elevenlabs.io/v1/text-to-dialogue/stream-input?model_id=$model&output_format=$format")
            .header("xi-api-key", key).build()
        socket = client.newWebSocket(request, object : WebSocketListener() {
            override fun onOpen(webSocket: WebSocket, response: Response) {
                if (closed) { webSocket.cancel(); return }
                val registered = webSocket.send(JSONObject().put("voices", JSONArray().put(voice)).toString())
                val sent = webSocket.send(JSONObject().put("inputs", JSONArray().put(JSONObject()
                    .put("text", text).put("voice_id", voice).put("new_turn", false))).toString())
                val flushed = webSocket.send(JSONObject().put("close_socket", true).toString())
                if (!registered || !sent || !flushed) finish("ElevenLabs 实时发声请求未发送", webSocket)
            }
            override fun onMessage(webSocket: WebSocket, text: String) {
                if (ended.get() || closed) return
                runCatching {
                    val event = JSONObject(text)
                    if (event.has("error")) {
                        finish("ElevenLabs 实时发声失败，请检查模型权限、声线和余额", webSocket)
                        return
                    }
                    val bytes = decodeAudio(event)
                    if (bytes != null) {
                        received += bytes.size
                        if (received > 20 * 1024 * 1024 || !frames.offer(Frame(bytes))) {
                            finish("ElevenLabs 音频超过缓冲限制", webSocket)
                            return
                        }
                    }
                    if (event.optBoolean("is_final")) finish(null, webSocket)
                }.onFailure { finish("ElevenLabs 返回无法解析的音频事件", webSocket) }
            }
            override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                finish("ElevenLabs 实时发声连接失败${response?.code?.let { " HTTP $it" }.orEmpty()}", webSocket)
            }
            override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
                if (!ended.get()) finish("ElevenLabs 音频流提前断开", webSocket)
            }
        })
    }

    private fun finish(error: String?, webSocket: WebSocket) {
        if (!ended.compareAndSet(false, true)) return
        if (error != null) frames.clear()
        if (!frames.offer(Frame(error = error))) {
            frames.clear()
            frames.offer(Frame(error = "ElevenLabs 音频超过缓冲限制"))
        }
        if (error == null) webSocket.close(1000, null) else webSocket.cancel()
    }

    override fun read(): Int {
        val byte = ByteArray(1)
        return if (read(byte, 0, 1) < 0) -1 else byte[0].toInt() and 255
    }
    override fun read(bytes: ByteArray, start: Int, length: Int): Int {
        require(start >= 0 && length >= 0 && start <= bytes.size - length)
        if (length == 0) return 0
        if (closed) throw IOException("语音播放已取消")
        if (eof) return -1
        while (offset >= chunk.size) {
            val frame = frames.poll(60, TimeUnit.SECONDS) ?: throw IOException("ElevenLabs 音频等待超时")
            if (closed) throw IOException("语音播放已取消")
            frame.error?.let { throw IOException(it) }
            chunk = frame.bytes ?: run { eof = true; return -1 }
            offset = 0
        }
        val count = minOf(length, chunk.size - offset)
        chunk.copyInto(bytes, start, offset, offset + count)
        offset += count
        return count
    }
    override fun close() {
        closed = true
        ended.set(true)
        frames.clear()
        frames.offer(Frame(error = "语音播放已取消"))
        socket.cancel()
    }
    companion object {
        private val client = OkHttpClient.Builder().readTimeout(0, TimeUnit.MILLISECONDS).build()
        internal fun decodeAudio(event: JSONObject): ByteArray? {
            if (event.isNull("audio")) return null
            return event.optString("audio").takeIf { it.isNotBlank() }?.let { Base64.decode(it, Base64.DEFAULT) }
        }
    }
}
