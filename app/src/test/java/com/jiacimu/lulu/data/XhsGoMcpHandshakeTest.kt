package com.jiacimu.lulu.data

import android.content.Context
import kotlinx.coroutines.runBlocking
import okhttp3.mockwebserver.MockWebServer
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

/** Obsolete XHS transport must not be revived by a saved URL or old login tests. */
@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE, sdk = [28])
class XhsGoMcpHandshakeTest {
    @Test fun retiredMcpCredentialsNeverReactivateXhsOrHealth() = runBlocking {
        val server = MockWebServer()
        server.start()
        try {
            val app: Context = RuntimeEnvironment.getApplication()
            val url = server.url("/mcp").toString()
            assertFalse(LuluExternalMcp.configured(app, "xiaohongshu"))
            assertTrue(runCatching { LuluExternalMcp.save(app, "xiaohongshu", url, "secret") }.isFailure)
            assertTrue(runCatching { LuluExternalMcp.check(app, "xiaohongshu") }.isFailure)
            assertEquals(0, server.requestCount)
            app.getSharedPreferences("lulu_external_mcp_v1", Context.MODE_PRIVATE).edit()
                .putString("health:url", url).commit()
            assertFalse(LuluExternalMcp.configured(app, "health"))
            assertTrue(runCatching { LuluExternalMcp.check(app, "health") }.isFailure)
            assertEquals(url, LuluExternalMcp.endpoint(app, "health"))
            assertEquals(0, server.requestCount)
        } finally {
            server.shutdown()
        }
    }
}
