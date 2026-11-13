package com.jiacimu.lulu.ai

import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE, sdk = [28])
class GeminiUsageRequestPolicyTest {
    @Test fun officialGeminiStreamRequestsTerminalUsageWithoutRewritingPrompt() {
        val body = JSONObject().put("model", "gemini-3.8-flash")
            .put("stream", true).put("messages", "already assembled")
        GeminiUsageRequestPolicy.addStreamingUsageWhenSupported(
            "https://generativelanguage.googleapis.com/v1beta/openai", body)
        assertTrue(body.getJSONObject("stream_options").getBoolean("include_usage"))
        assertEquals("already assembled", body.getString("messages"))
    }

    @Test fun relayOrNonStreamingCallsAreNotGivenUnsupportedOptions() {
        val relay = JSONObject().put("stream", true)
        GeminiUsageRequestPolicy.addStreamingUsageWhenSupported(
            "https://relay.example.invalid/v1", relay)
        assertFalse(relay.has("stream_options"))
        val nonStream = JSONObject().put("stream", false)
        GeminiUsageRequestPolicy.addStreamingUsageWhenSupported(
            "https://generativelanguage.googleapis.com/v1beta/openai", nonStream)
        assertFalse(nonStream.has("stream_options"))
        val spoofed = JSONObject().put("stream", true)
        GeminiUsageRequestPolicy.addStreamingUsageWhenSupported(
            "https://generativelanguage.googleapis.com.evil.example/openai", spoofed)
        assertFalse(spoofed.has("stream_options"))
    }
}
