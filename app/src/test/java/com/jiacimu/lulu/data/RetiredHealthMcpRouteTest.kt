package com.jiacimu.lulu.data

import android.content.Context
import kotlinx.coroutines.runBlocking
import org.json.JSONObject
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE, sdk = [28])
class RetiredHealthMcpRouteTest {
    @Test fun retiredHealthMcpIsNotOfferedEvenWithOldCredentialsAndCachedReadings() = runBlocking {
        val context = RuntimeEnvironment.getApplication() as Context
        context.getSharedPreferences("lulu_external_mcp_v1", Context.MODE_PRIVATE)
            .edit().putString("health:url", "http://127.0.0.1:49999/mcp")
            .putString("health:token", "old-unused-token")
            .putString("health:last", "old-step-count")
            .putLong("health:lastAt", System.currentTimeMillis())
            .commit()
        assertNull(CapabilityRegistry.find("read_health_mcp"))
        assertFalse(CapabilityRegistry.canChoose("read_health_mcp"))
        assertTrue(CapabilityRegistry.find("background_phone") != null)
        assertFalse(LuluExternalMcp.configured(context, "health"))
        assertTrue(LuluExternalMcp.healthContext(context).isEmpty())
        assertThrows(IllegalStateException::class.java) {
            runBlocking { LuluExternalMcp.healthRead(context, JSONObject().put("data_type", "sleep")) }
        }
        assertThrows(IllegalStateException::class.java) {
            LuluExternalMcp.save(context, "health", "https://example.com/mcp", "token")
        }
        // Retirement must not delete a person's historical imported observations.
        assertTrue(context.getSharedPreferences("lulu_external_mcp_v1", Context.MODE_PRIVATE)
            .getString("health:last", "").orEmpty().isNotBlank())
    }
}
