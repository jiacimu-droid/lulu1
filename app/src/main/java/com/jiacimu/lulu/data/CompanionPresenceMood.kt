package com.jiacimu.lulu.data

import org.json.JSONObject

/**
 * The public "心情" field is prose, not a JSON transport for affect scores.
 * Decode accidental model JSON before clipping it; old snapshots may already
 * contain a clipped object, so support the leading label in those as well.
 * Never show raw internal scores or malformed transport syntax to the user.
 */
internal object CompanionPresenceMood {
    private val clippedLabel = Regex("""^\s*\{\s*["']label["']\s*:\s*["']([^"']*)""")

    fun text(raw: String): String {
        val input = raw.trim()
        if (input.isEmpty()) return ""
        val readable = if (input.startsWith("{") || input.startsWith("[")) {
            runCatching { JSONObject(input).optString("label") }.getOrDefault("")
                .ifBlank { clippedLabel.find(input)?.groupValues?.get(1).orEmpty() }
        } else input
        return readable.trim().replace(Regex("\\s+"), " ").take(80)
    }
}
