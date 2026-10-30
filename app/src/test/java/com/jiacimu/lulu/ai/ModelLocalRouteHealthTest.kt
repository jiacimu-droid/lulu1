package com.jiacimu.lulu.ai

import java.io.IOException
import java.net.InetSocketAddress
import java.net.Proxy
import java.net.ProxySelector
import java.net.SocketAddress
import java.net.URI
import java.net.URL
import org.junit.Assert.*
import org.junit.Test

class ModelLocalRouteHealthTest {
    private fun withProxy(host: String, port: Int) = object : ProxySelector() {
        override fun select(uri: URI?): List<Proxy> = listOf(
            Proxy(Proxy.Type.HTTP, InetSocketAddress(host, port)))
        override fun connectFailed(uri: URI?, sa: SocketAddress?, ioe: IOException?) = Unit
    }

    @Test fun deadLocalSystemProxyFailsBeforeAnyModelBodyIsSent() {
        var attemptedLocalSocket = false
        val thrown = try {
            ModelLocalRouteHealth.probeBeforePost(
                "https://model.example.test/v1/chat/completions",
                selector = withProxy("127.0.0.1", 12000),
                connect = { address, timeout ->
                    assertEquals(12000, address.port)
                    assertEquals(1200, timeout)
                    attemptedLocalSocket = true
                    throw IOException("proxy socket refused")
                },
            )
            null
        } catch (error: ModelLocalRouteUnavailableException) {
            error
        }
        assertTrue(attemptedLocalSocket)
        assertNotNull(thrown)
        assertTrue(thrown!!.message!!.contains("127.0.0.1:12000"))
        assertTrue(thrown.message!!.contains("尚未发送"))
    }

    @Test fun connectedLocalProxyDoesNotTriggerAnyExtraPaidModelRequest() {
        var calls = 0
        ModelLocalRouteHealth.probeBeforePost(
            "https://model.example.test/v1/chat/completions",
            selector = withProxy("127.0.0.1", 12000),
            connect = { _, _ -> calls++ },
        )
        assertEquals(1, calls)
    }

    @Test fun publicProxyAndDirectConnectionHaveNoExtraLocalSocket() {
        val direct = object : ProxySelector() {
            override fun select(uri: URI?): List<Proxy> = listOf(Proxy.NO_PROXY)
            override fun connectFailed(uri: URI?, sa: SocketAddress?, ioe: IOException?) = Unit
        }
        var sockets = 0
        for (selector in listOf(direct, withProxy("proxy.example.test", 8080))) {
            ModelLocalRouteHealth.probeBeforePost(
                "https://model.example.test/v1/chat/completions",
                selector = selector, connect = { _, _ -> sockets++ },
            )
        }
        assertEquals(0, sockets)
    }

    @Test fun explicitlyConfiguredLocalApiAlsoUsesPreflight() {
        val direct = object : ProxySelector() {
            override fun select(uri: URI?): List<Proxy> = listOf(Proxy.NO_PROXY)
            override fun connectFailed(uri: URI?, sa: SocketAddress?, ioe: IOException?) = Unit
        }
        val route = ModelLocalRouteHealth.selectedLocalRoute(
            URL("http://127.0.0.1:12000/v1"), direct)
        assertNotNull(route)
        assertFalse(route!!.isProxy)
        assertEquals(12000, route.address.port)
    }
}
