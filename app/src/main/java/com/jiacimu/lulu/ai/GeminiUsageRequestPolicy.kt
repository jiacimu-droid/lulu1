package com.jiacimu.lulu.ai

import org.json.JSONObject
import java.net.URL

/**
 * Ask for end-of-stream usage only on Google's official OpenAI-compatible host.
 * Generic relays may reject stream_options; don't change their request.
 * Reporting only: no extra model call, no explicit cache allocation.
 */
internal object GeminiUsageRequestPolicy {
    fun addStreamingUsageWhenSupported(baseUrl: String, body: JSONObject) {
        val official = runCatching {
            URL(baseUrl).host.equals("generativelanguage.googleapis.com", ignoreCase = true)
        }.getOrDefault(false)
        if (official && body.optBoolean("stream")) {
            body.put("stream_options", JSONObject().put("include_usage", true))
        }
    }
}
