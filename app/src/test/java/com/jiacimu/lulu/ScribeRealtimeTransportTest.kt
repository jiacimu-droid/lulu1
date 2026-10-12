package com.jiacimu.lulu

import android.os.Looper
import android.util.Base64
import kotlinx.coroutines.*
import okhttp3.Protocol
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import okio.ByteString
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit

@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE, sdk = [28])
class ScribeRealtimeTransportTest {
    @Test fun quietPcmReachesScribeAndOnlyCommittedSpeechCreatesATurn() {
        // Isolate the external socket, not the transport, PCM or transcript logic.
        // No native server reader can mask a failing protocol assertion during teardown.
        val wire = FakeWire()
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
        val partials = mutableListOf<String>()
        val finals = mutableListOf<String>()
        val errors = mutableListOf<String>()
        var starts = 0
        val recognizer = ElevenLabsRealtimeStt(scope, "test-key", { true }, { starts++ }, {},
            onPartial = { partials += it }, onText = { finals += it }, onError = { errors += it },
            socketFactory = wire)
        try {
            recognizer.start()
            // RMS=19, far below the old phone-side 195 gate.
            val quiet = ByteArray(3200) { if (it % 2 == 0) 19 else 0 }
            recognizer.audioFrame(quiet)
            assertEquals("vad", wire.request.url.queryParameter("commit_strategy"))
            val packet = JSONObject(wire.packets.poll(5, TimeUnit.SECONDS)!!)
            assertArrayEquals(quiet, Base64.decode(packet.getString("audio_base_64"), Base64.NO_WRAP))
            assertFalse(packet.has("commit"))
            wire.receive("partial_transcript", "我还有句话")
            pumpUntil { partials.isNotEmpty() }
            assertEquals(1, starts)
            assertTrue(finals.isEmpty())
            wire.receive("partial_transcript", "")
            wire.receive("partial_transcript", "我还有句话")
            wire.receive("committed_transcript", "我还有句话")
            pumpUntil { finals.isNotEmpty() }
            assertEquals(listOf("我还有句话"), finals)
            assertEquals(2, starts) // No onset from repeated/empty keepalives.
            assertTrue(errors.isEmpty())
            recognizer.stop()
            assertTrue(wire.closed)
            wire.receive("committed_transcript", "已经结束通话后的旧事件")
            shadowOf(Looper.getMainLooper()).idleFor(1, TimeUnit.SECONDS)
            assertEquals(listOf("我还有句话"), finals)
        } finally {
            recognizer.stop()
            scope.cancel()
        }
    }
    private class FakeWire : WebSocket.Factory, WebSocket {
        lateinit var request: Request
        lateinit var listener: WebSocketListener
        val packets = LinkedBlockingQueue<String>()
        var closed = false
        override fun newWebSocket(request: Request, listener: WebSocketListener): WebSocket {
            this.request = request
            this.listener = listener
            listener.onOpen(this, Response.Builder().request(request).protocol(Protocol.HTTP_1_1)
                .code(101).message("Switching Protocols").build())
            return this
        }
        fun receive(kind: String, text: String) = listener.onMessage(this,
            JSONObject().put("message_type", kind).put("text", text).toString())
        override fun request(): Request = request
        override fun queueSize(): Long = 0L
        override fun send(text: String): Boolean = !closed && packets.offer(text)
        override fun send(bytes: ByteString): Boolean = false
        override fun close(code: Int, reason: String?): Boolean { closed = true; return true }
        override fun cancel() { closed = true }
    }
    private fun pumpUntil(condition: () -> Boolean) {
        val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5)
        while (!condition() && System.nanoTime() < deadline) {
            shadowOf(Looper.getMainLooper()).idleFor(100, TimeUnit.MILLISECONDS)
            Thread.sleep(10)
        }
        assertTrue("Expected realtime callback did not arrive", condition())
    }
}
