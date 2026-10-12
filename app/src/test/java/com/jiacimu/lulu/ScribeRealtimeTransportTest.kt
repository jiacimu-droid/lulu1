package com.jiacimu.lulu

import android.os.Looper
import android.util.Base64
import kotlinx.coroutines.*
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
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
        val packets = LinkedBlockingQueue<String>()
        val sockets = LinkedBlockingQueue<WebSocket>()
        val server = MockWebServer()
        server.enqueue(MockResponse().withWebSocketUpgrade(object : WebSocketListener() {
            override fun onOpen(webSocket: WebSocket, response: Response) { sockets.offer(webSocket) }
            override fun onMessage(webSocket: WebSocket, text: String) { packets.offer(text) }
        }))
        server.start()
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
        val partials = mutableListOf<String>()
        val finals = mutableListOf<String>()
        val errors = mutableListOf<String>()
        var starts = 0
        val recognizer = ElevenLabsRealtimeStt(scope, "test-key", { true }, { starts++ }, {},
            onPartial = { partials += it }, onText = { finals += it }, onError = { errors += it },
            endpoint = server.url("/realtime").toString())
        try {
            recognizer.start()
            // RMS=19, far below the old phone-side 195 gate.
            val quiet = ByteArray(3200) { if (it % 2 == 0) 19 else 0 }
            recognizer.audioFrame(quiet)
            val request = server.takeRequest(5, TimeUnit.SECONDS)!!
            assertEquals("vad", request.requestUrl!!.queryParameter("commit_strategy"))
            val packet = JSONObject(packets.poll(5, TimeUnit.SECONDS)!!)
            assertArrayEquals(quiet, Base64.decode(packet.getString("audio_base_64"), Base64.NO_WRAP))
            assertFalse(packet.has("commit"))
            val socket = sockets.poll(5, TimeUnit.SECONDS)!!
            socket.send("""{"message_type":"partial_transcript","text":"我还有句话"}""")
            pumpUntil { partials.isNotEmpty() }
            assertEquals(1, starts)
            assertTrue(finals.isEmpty())
            socket.send("""{"message_type":"partial_transcript","text":""}""")
            socket.send("""{"message_type":"partial_transcript","text":"我还有句话"}""")
            socket.send("""{"message_type":"committed_transcript","text":"我还有句话"}""")
            pumpUntil { finals.isNotEmpty() }
            assertEquals(listOf("我还有句话"), finals)
            assertEquals(2, starts) // No onset from repeated/empty keepalives.
            assertTrue(errors.isEmpty())
        } finally {
            recognizer.stop()
            scope.cancel()
            server.shutdown()
        }
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
