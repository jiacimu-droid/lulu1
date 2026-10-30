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

/** Passive proxy diagnostics must never become a paid-chat prerequisite. */
class ModelLocalRouteHealthTest {
    private fun withProxy(host: String, port: Int) = object : ProxySelector() {
        override fun select(uri: URI?): List<Proxy> = listOf(
            Proxy(Proxy.Type.HTTP, InetSocketAddress(host, port)))
        override fun connectFailed(uri: URI?, sa: SocketAddress?, ioe: IOException?) = Unit
    }

    @Test fun systemLocalProxyIsOnlyAConnectionFailureHint() {
        val selector = withProxy("127.0.0.1", 12000)
        val url = "https://model.example.test/v1/chat/completions"
        val route = ModelLocalRouteHealth.selectedLocalRoute(URL(url), selector)
        assertNotNull(route)
        assertTrue(route!!.isProxy)
        assertEquals(12000, route.address.port)
        assertTrue(ModelLocalRouteHealth.transportHint(url, selector).contains("127.0.0.1:12000"))
        assertTrue(ModelLocalRouteHealth.transportHint(url, selector).contains("仅在连接失败"))
    }

    @Test fun publicProxyAndDirectConnectionProduceNoLocalWarning() {
        val direct = object : ProxySelector() {
            override fun select(uri: URI?): List<Proxy> = listOf(Proxy.NO_PROXY)
            override fun connectFailed(uri: URI?, sa: SocketAddress?, ioe: IOException?) = Unit
        }
        val url = "https://model.example.test/v1/chat/completions"
        assertEquals("", ModelLocalRouteHealth.transportHint(url, direct))
        assertEquals("", ModelLocalRouteHealth.transportHint(url,
            withProxy("proxy.example.test", 8080)))
    }

    @Test fun deliberatelyConfiguredLocalApiStillHasAnActionableHint() {
        val direct = object : ProxySelector() {
            override fun select(uri: URI?): List<Proxy> = listOf(Proxy.NO_PROXY)
            override fun connectFailed(uri: URI?, sa: SocketAddress?, ioe: IOException?) = Unit
        }
        val url = "http://127.0.0.1:12000/v1"
        val route = ModelLocalRouteHealth.selectedLocalRoute(URL(url), direct)
        assertNotNull(route)
        assertFalse(route!!.isProxy)
        assertEquals(12000, route.address.port)
        assertTrue(ModelLocalRouteHealth.transportHint(url, direct).contains("本机接口"))
    }
}
