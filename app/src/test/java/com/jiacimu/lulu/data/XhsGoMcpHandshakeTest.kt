package com.jiacimu.lulu.data

import kotlinx.coroutines.runBlocking
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.json.JSONArray
import org.json.JSONObject
import java.net.InetSocketAddress
import java.net.Proxy
import java.net.ProxySelector
import java.net.URI
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE, sdk = [28])
class XhsGoMcpHandshakeTest {
    private fun result(id: Int, payload: JSONObject): String =
        JSONObject().put("jsonrpc", "2.0").put("id", id).put("result", payload).toString()

    @Test fun localOfficialGoMcpDoesNotNeedLegacyNodeHealthEndpoint() = runBlocking {
        val server = MockWebServer()
        server.start()
        try {
            val app = RuntimeEnvironment.getApplication()
            assertTrue(LuluExternalMcp.save(app, "xiaohongshu", server.url("/mcp").toString(), "test-only-token"))
            server.enqueue(MockResponse().setResponseCode(404).setBody("Not Found"))
            server.enqueue(MockResponse().setHeader("Content-Type", "application/json")
                .setBody(result(1, JSONObject().put("protocolVersion", "2025-03-26"))))
            assertTrue(LuluExternalMcp.testService(app, "xiaohongshu").contains("MCP 服务已响应"))
            val health = server.takeRequest()
            assertEquals("/health", health.path)
            val handshake = server.takeRequest()
            assertEquals("/mcp", handshake.path)
            assertEquals("POST", handshake.method)
            assertEquals("Bearer test-only-token", handshake.getHeader("Authorization"))
            assertEquals("initialize", JSONObject(handshake.body.readUtf8()).getString("method"))
        } finally {
            server.shutdown()
        }
    }

    @Test fun samePhoneMcpBypassesAndroidHttpProxy() = runBlocking {
        val server = MockWebServer()
        server.start()
        val previousProxy = ProxySelector.getDefault()
        try {
            // Simulate a VPN/system HTTP proxy that cannot reach the phone's
            // localhost. Neither /health nor initialize may use that proxy.
            ProxySelector.setDefault(object : ProxySelector() {
                override fun select(uri: URI?): List<Proxy> =
                    listOf(Proxy(Proxy.Type.HTTP, InetSocketAddress("127.0.0.1", 9)))
                override fun connectFailed(
                    uri: URI?, sa: java.net.SocketAddress?, ioe: java.io.IOException?
                ) = Unit
            })
            val app = RuntimeEnvironment.getApplication()
            LuluExternalMcp.save(app, "xiaohongshu", server.url("/mcp").toString(), "test-token")
            server.enqueue(MockResponse().setResponseCode(404).setBody("Not Found"))
            server.enqueue(MockResponse().setHeader("Content-Type", "application/json")
                .setBody(result(1, JSONObject().put("protocolVersion", "2025-03-26"))))
            val outcome = LuluExternalMcp.testService(app, "xiaohongshu")
            assertTrue(outcome, outcome.contains("MCP 服务已响应"))
            assertEquals("/health", server.takeRequest().path)
            assertEquals("/mcp", server.takeRequest().path)
        } finally {
            ProxySelector.setDefault(previousProxy)
            server.shutdown()
        }
    }

    @Test fun officialGoMcpTextLoginIsRecognizedThroughActualWireProtocol() = runBlocking {
        val server = MockWebServer()
        server.start()
        try {
            val app = RuntimeEnvironment.getApplication()
            LuluExternalMcp.save(app, "xiaohongshu", server.url("/mcp").toString(), "go-token")
            server.enqueue(MockResponse().setHeader("Content-Type", "application/json")
                .setBody(result(1, JSONObject().put("protocolVersion", "2025-03-26"))))
            server.enqueue(MockResponse().setResponseCode(202))
            server.enqueue(MockResponse().setHeader("Content-Type", "application/json")
                .setBody(result(2, JSONObject().put("content", JSONArray().put(
                    JSONObject().put("type", "text")
                        .put("text", "✅ 已登录\n用户名: 已授权账号")
                )))))
            val message = LuluExternalMcp.check(app, "xiaohongshu")
            assertTrue(message, message.contains("已登录"))
            assertTrue(message, message.contains("已授权账号"))
            assertEquals("initialize", JSONObject(server.takeRequest().body.readUtf8()).optString("method"))
            assertEquals("notifications/initialized",
                JSONObject(server.takeRequest().body.readUtf8()).optString("method"))
            val call = JSONObject(server.takeRequest().body.readUtf8())
            assertEquals("check_login_status",
                call.getJSONObject("params").getString("name"))
        } finally {
            server.shutdown()
        }
    }
}
