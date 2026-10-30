package com.jiacimu.lulu.ai

import java.io.IOException
import java.net.ServerSocket
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.SocketPolicy
import org.junit.Assert.*
import org.junit.Test

class ModelPostTransportTest {
    @Test fun utf8BodyUsesExactLengthAndOnePost() {
        val server = MockWebServer()
        server.enqueue(MockResponse().setBody("ok"))
        server.start()
        try {
            val body = "{\"内容\":\"记忆\"}"
            assertEquals("ok", ModelPostTransport.execute(server.url("/").toString(),
                emptyMap(), body, 30_000, preflight = {}) { it.body!!.string() })
            val request = server.takeRequest()
            assertEquals(body, request.body.readUtf8())
            assertEquals(body.toByteArray(Charsets.UTF_8).size.toString(), request.getHeader("Content-Length"))
            assertEquals(1, server.requestCount)
        } finally { server.shutdown() }
    }

    @Test fun authenticationRejectionRetainsProviderBodyWithoutReplaying() {
        val server = MockWebServer()
        server.enqueue(MockResponse().setResponseCode(401)
            .setBody("{\"error\":{\"message\":\"repair-auth-denied\"}}"))
        server.enqueue(MockResponse().setBody("must not retry"))
        server.start()
        try {
            ModelPostTransport.execute(server.url("/").toString(), emptyMap(), "{}", 30_000,
                preflight = {}) {
                assertEquals(401, it.code)
                assertTrue(it.body!!.string().contains("repair-auth-denied"))
            }
            assertEquals(1, server.requestCount)
        } finally { server.shutdown() }
    }

    @Test fun redirectDoesNotReplayThePaidBodyAtAnotherEndpoint() {
        val server = MockWebServer()
        server.start()
        server.enqueue(MockResponse().setResponseCode(307).setHeader("Location", server.url("/other")))
        server.enqueue(MockResponse().setBody("must not redirect"))
        try {
            assertEquals(307, ModelPostTransport.execute(server.url("/").toString(),
                emptyMap(), "{}", 30_000, preflight = {}) { it.code })
            assertEquals(1, server.requestCount)
        } finally { server.shutdown() }
    }

    @Test fun disconnectAfterAcceptedBodyDoesNotReplayChargeablePost() {
        val server = MockWebServer()
        server.enqueue(MockResponse().setSocketPolicy(SocketPolicy.DISCONNECT_AFTER_REQUEST))
        server.enqueue(MockResponse().setBody("must not replay"))
        server.start()
        try {
            try {
                ModelPostTransport.execute(server.url("/chat/completions").toString(),
                    emptyMap(), "{\"model\":\"test\"}", 30_000, preflight = {}) { it.body!!.string() }
                fail("Expected broken response")
            } catch (error: ModelPostTransportException) {
                assertEquals(ModelPostTransport.Phase.READ_RESPONSE, error.phase)
            }
            assertEquals(1, server.requestCount)
            assertEquals("{\"model\":\"test\"}", server.takeRequest().body.readUtf8())
        } finally { server.shutdown() }
    }

    @Test fun connectFailureIsNotReportedAsResponseReadTimeout() {
        val port = ServerSocket(0).use { it.localPort }
        try {
            ModelPostTransport.execute("http://127.0.0.1:$port/", emptyMap(), "{}", 30_000,
                preflight = {}) { it.body!!.string() }
            fail("Expected connection failure")
        } catch (error: ModelPostTransportException) {
            assertEquals(ModelPostTransport.Phase.CONNECT, error.phase)
            assertTrue(error.diagnostic.contains("正文尚未发送"))
        }
    }

    @Test fun failedLocalPreflightNeverStartsPost() {
        val server = MockWebServer()
        server.start()
        try {
            try {
                ModelPostTransport.execute(server.url("/").toString(), emptyMap(), "{}", 30_000,
                    preflight = { throw ModelLocalRouteUnavailableException("offline", IOException()) }) { "unused" }
                fail("Expected local failure")
            } catch (_: ModelLocalRouteUnavailableException) { }
            assertEquals(0, server.requestCount)
        } finally { server.shutdown() }
    }
}
