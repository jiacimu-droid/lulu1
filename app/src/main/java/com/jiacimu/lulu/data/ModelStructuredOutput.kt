package com.jiacimu.lulu.data

import org.json.JSONObject

/**
 * Tolerates reasoning prefixes, markdown fences and trailing prose.
 * NEVER reconstructs incomplete JSON tool commands as executable actions.
 */
internal object ModelStructuredOutput {
    fun objectOrNull(raw: String): JSONObject? {
        val clean = raw.trim().removePrefix("\uFEFF")
        if (clean.isBlank()) return null
        runCatching { JSONObject(clean) }.getOrNull()?.let { return it }
        var start = -1
        var depth = 0
        var inString = false
        var escaped = false
        for (i in clean.indices) {
            val c = clean[i]
            if (inString) {
                if (escaped) { escaped = false; continue }
                when (c) {
                    '\\' -> escaped = true
                    '"' -> inString = false
                }
                continue
            }
            when (c) {
                '"' -> if (depth > 0) inString = true
                '{' -> { if (depth == 0) start = i; depth++ }
                '}' -> if (depth > 0) {
                    depth--
                    if (depth == 0 && start >= 0) {
                        val json = runCatching { JSONObject(clean.substring(start, i + 1)) }.getOrNull()
                        if (json != null) return json
                        start = -1
                    }
                }
            }
        }
        return null
    }

    /**
     * Recover only a *completed* speech text field from a truncated chat envelope.
     * Invalid command parameters are never executed or marked successful.
     */
    fun completedReplyText(raw: String): String? {
        val found = Regex(""""text"\s*:\s*"((?:[^"\\]|\\.)*)"""").find(raw) ?: return null
        return runCatching { JSONObject("{\"text\":\"" + found.groupValues[1] + "\"}").optString("text").takeIf(String::isNotBlank) }.getOrNull()
    }
}
