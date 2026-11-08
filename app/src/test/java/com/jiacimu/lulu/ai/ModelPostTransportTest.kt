package com.jiacimu.lulu.ai

import java.io.EOFException
import java.io.IOException
import java.net.InetSocketAddress
import java.net.ProtocolException
import java.net.Proxy
import java.net.ProxySelector
import java.net.ServerSocket
import java.net.SocketAddress
import java.net.SocketException
import java.net.URI
import java.net.UnknownHostException
import java.util.concurrent.atomic.AtomicInteger
import javax.net.ssl.SSLHandshakeException
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.SocketPolicy
import org.junit.Assert.*
import org.junit.Test

class ModelPostTransportTest {
    @Test fun bodyUploadHasItsOwnBoundedWriteTimeoutNotOkHttpTenSecondDefault() {
        // A long model readTimeout must not leave a large JSON upload exposed
        // to OkHttp's unrelated 10-second default write timeout.
        assertEquals(60L, ModelPostTransport.REQUEST_WRITE_TIMEOUT_SECONDS)
    }

    @Test fun utf8BodyUsesExactLengthAndOnePost() {
        val server = MockWebServer()
        server.enqueue(MockResponse().setBody("ok"))
        server.start()
        try {
            val body = "{\"内容\":\"记忆\"}"
            assertEquals("ok", ModelPostTransport.execute(server.url("/").toString(),
                emptyMap(), body, 30_000) { it.body!!.string() })
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
            ModelPostTransport.execute(server.url("/").toString(), emptyMap(), "{}", 30_000) {
                assertEquals(401, it.code)
                assertTrue(it.body!!.string().contains("repair-auth-denied"))
            }
            assertEquals(1, server.requestCount)
        } finally { server.shutdown() }
    }

    @Test fun ambiguous503DoesNotRetryOrCountAsSuccessfulGeneration() {
        val server = MockWebServer()
        server.enqueue(MockResponse().setResponseCode(503)
            .setBody("{\"error\":\"relay temporarily unavailable\"}"))
        server.enqueue(MockResponse().setBody("must not replay"))
        server.start()
        try {
            val body = "{\"model\":\"test\",\"messages\":[]}"
            val statusAndBody = ModelPostTransport.execute(
                server.url("/chat/completions").toString(),
                emptyMap(), body, 30_000,
            ) { response -> response.code to response.body!!.string() }
            assertEquals(503, statusAndBody.first)
            assertTrue(statusAndBody.second.contains("relay temporarily unavailable"))
            assertEquals(1, server.requestCount)
            assertEquals(body, server.takeRequest().body.readUtf8())
        } finally { server.shutdown() }
    }

    @Test fun redirectDoesNotReplayThePaidBodyAtAnotherEndpoint() {
        val server = MockWebServer()
        server.start()
        server.enqueue(MockResponse().setResponseCode(307).setHeader("Location", server.url("/other")))
        server.enqueue(MockResponse().setBody("must not redirect"))
        try {
            assertEquals(307, ModelPostTransport.execute(server.url("/").toString(),
                emptyMap(), "{}", 30_000) { it.code })
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
                    emptyMap(), "{\"model\":\"test\"}", 30_000) { it.body!!.string() }
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
            ModelPostTransport.execute("http://127.0.0.1:$port/", emptyMap(), "{}", 30_000) { it.body!!.string() }
            fail("Expected connection failure")
        } catch (error: ModelPostTransportException) {
            assertEquals(ModelPostTransport.Phase.CONNECT, error.phase)
            assertTrue(error.diagnostic.contains("正文尚未发送"))
        }
    }

    @Test fun transientConnectFailureRetriesOnlyBeforeBodyAndPreservesTheOriginalPost() {
        val server = MockWebServer()
        server.enqueue(MockResponse().setBody("recovered"))
        server.start()
        try {
            // First route is a closed HTTP proxy; the retry uses a valid direct
            // route. The provider receives exactly ONE body, not two paid POSTs.
            val unreachablePort = ServerSocket(0).use { it.localPort }
            val routes = AtomicInteger()
            val selector = object : ProxySelector() {
                override fun select(uri: URI): List<Proxy> =
                    if (routes.incrementAndGet() == 1) listOf(
                        Proxy(Proxy.Type.HTTP, InetSocketAddress("127.0.0.1", unreachablePort)),
                    ) else listOf(Proxy.NO_PROXY)

                override fun connectFailed(uri: URI, sa: SocketAddress, ioe: IOException) = Unit
            }
            val testClient = OkHttpClient.Builder()
                .proxySelector(selector)
                .retryOnConnectionFailure(false)
                .build()
            val body = "{\"message\":\"连接恢复后还要收到这句\"}"
            val result = ModelPostTransport.execute(server.url("/chat").toString(),
                emptyMap(), body, 30_000, transportClient = testClient) { it.body!!.string() }
            assertEquals("recovered", result)
            assertEquals(2, routes.get())
            assertEquals(1, server.requestCount)
            assertEquals(body, server.takeRequest().body.readUtf8())
        } finally { server.shutdown() }
    }

    @Test fun onlyTransientPreBodyFailuresAreEligibleForRetry() {
        assertEquals(1, ModelPostTransport.PRE_BODY_CONNECT_RETRIES)
        assertTrue(ModelPostTransport.isRetryablePreBodyFailure(IOException("connection closed")))
        assertTrue(ModelPostTransport.isRetryablePreBodyFailure(SocketException("Connection reset")))
        assertTrue(ModelPostTransport.isRetryablePreBodyFailure(EOFException("EOF")))
        assertFalse(ModelPostTransport.isRetryablePreBodyFailure(UnknownHostException("bad host")))
        assertFalse(ModelPostTransport.isRetryablePreBodyFailure(
            SSLHandshakeException("untrusted certificate")))
        assertFalse(ModelPostTransport.isRetryablePreBodyFailure(ProtocolException("bad protocol")))
        assertFalse(ModelPostTransport.isRetryablePreBodyFailure(IOException("Canceled")))
        assertTrue(ModelPostTransportException(ModelPostTransport.Phase.CONNECT,
            IOException("connection closed"), 1).diagnostic.contains("已安全重连1次"))
    }

    @Test fun providerRequestIsNotBlockedByAnIndependentLocalPreflight() {
        val server = MockWebServer()
        server.enqueue(MockResponse().setBody("accepted"))
        server.start()
        try {
            // A loopback URL is a valid configured model gateway, not a VPN
            // policy violation. Only the real OkHttp request should connect.
            val result = ModelPostTransport.execute(server.url("/v1/chat").toString(),
                emptyMap(), "{\"text\":\"hello\"}", 30_000) { it.body!!.string() }
            assertEquals("accepted", result)
            assertEquals(1, server.requestCount)
        } finally { server.shutdown() }
    }
}
