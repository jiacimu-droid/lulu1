package com.jiacimu.lulu.ai

import java.io.ByteArrayOutputStream
import java.io.IOException
import java.io.OutputStream
import java.net.HttpURLConnection
import java.net.SocketTimeoutException
import java.net.URL
import org.junit.Assert.*
import org.junit.Test

class ModelPostTransportTest {
    private class Connection : HttpURLConnection(URL("http://example.test")) {
        var connects = 0
        var disconnected = false
        var failure: String = ""
        val sent = ByteArrayOutputStream()
        val bodyLength get() = fixedContentLength
        override fun connect() { connects++; if (failure == "connect") throw SocketTimeoutException("timeout") }
        override fun disconnect() { disconnected = true }
        override fun usingProxy() = false
        override fun getOutputStream(): OutputStream {
            if (failure == "write") throw IOException("connection closed")
            return sent
        }
    }

    @Test fun utf8BodyUsesExactLengthAndOnePost() {
        val connection = Connection()
        val text = "{\"内容\":\"记忆\"}"
        val reply = ModelPostTransport.execute("http://example.test", emptyMap(), text, 180_000,
            open = { connection }, preflight = {}) { "ok" }
        assertEquals("ok", reply)
        assertEquals(text.toByteArray(Charsets.UTF_8).size, connection.bodyLength)
        assertEquals(text, connection.sent.toString("UTF-8"))
        assertEquals(1, connection.connects)
        assertTrue(connection.disconnected)
    }

    @Test fun identicalTimeoutMessageIsClassifiedByActualPhase() {
        for (stage in ModelPostTransport.Phase.values()) {
            val connection = Connection().apply {
                failure = when (stage) {
                    ModelPostTransport.Phase.CONNECT -> "connect"
                    ModelPostTransport.Phase.WRITE_BODY -> "write"
                    else -> ""
                }
            }
            val failure = try {
                ModelPostTransport.execute("http://example.test", emptyMap(), "{}", 180_000,
                    open = { connection }, preflight = {}) { throw SocketTimeoutException("timeout") }
                error("Expected failure")
            } catch (error: ModelPostTransportException) { error }
            assertEquals(stage, failure.phase)
            assertEquals(1, connection.connects)
            assertTrue(connection.disconnected)
            if (stage == ModelPostTransport.Phase.CONNECT) {
                assertEquals(0, connection.sent.size())
                assertTrue(failure.diagnostic.contains("正文尚未发送"))
            } else assertTrue(failure.diagnostic.contains("结果未知"))
        }
    }

    @Test fun disconnectAfterAcceptedBodyDoesNotReplayChargeablePost() {
        val server = okhttp3.mockwebserver.MockWebServer()
        server.enqueue(okhttp3.mockwebserver.MockResponse().setSocketPolicy(
            okhttp3.mockwebserver.SocketPolicy.DISCONNECT_AFTER_REQUEST))
        server.enqueue(okhttp3.mockwebserver.MockResponse().setBody("must not replay"))
        server.start()
        try {
            try {
                ModelPostTransport.execute(server.url("/chat/completions").toString(),
                    emptyMap(), "{\"model\":\"test\"}", 30_000, preflight = {}) {
                    it.inputStream.use { stream -> stream.readBytes() }
                }
                fail("Expected broken response")
            } catch (error: ModelPostTransportException) {
                assertEquals(ModelPostTransport.Phase.READ_RESPONSE, error.phase)
            }
            assertEquals(1, server.requestCount)
            assertEquals("{\"model\":\"test\"}", server.takeRequest().body.readUtf8())
        } finally { server.shutdown() }
    }

    @Test fun failedLocalPreflightNeverOpensPost() {
        var opened = false
        try {
            ModelPostTransport.execute("http://localhost:12000", emptyMap(), "{}", 180_000,
                open = { opened = true; Connection() },
                preflight = { throw ModelLocalRouteUnavailableException("offline", IOException()) }) { "unused" }
            fail("Expected local failure")
        } catch (_: ModelLocalRouteUnavailableException) { }
        assertFalse(opened)
    }
}
