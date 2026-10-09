package com.jiacimu.lulu

import org.json.JSONObject

/** Handles both additive delta and accumulated text SSE conventions without repeating words. */
internal class MiniMaxAsrStreamAccumulator {
    private val buffer = StringBuilder()
    val value: String get() = buffer.toString()

    fun accept(event: JSONObject): String {
        val delta = event.optString("delta")
            .takeIf { it.isNotBlank() && !it.startsWith("{") }.orEmpty()
        val snapshot = event.optString("text").ifBlank {
            event.optJSONObject("data")?.optString("text").orEmpty()
        }
        if (snapshot.isNotBlank()) {
            // Some providers stream the entire hypothesis again with edits.
            buffer.setLength(0)
            buffer.append(snapshot)
        } else if (delta.isNotBlank()) {
            if (buffer.isEmpty()) {
                buffer.append(delta)
            } else if (delta != buffer.toString()) {
                if (delta.startsWith(buffer.toString())) {
                    buffer.setLength(0); buffer.append(delta)
                } else {
                    val prior = buffer.toString()
                    val overlap = (minOf(prior.length, delta.length) downTo 1)
                        .firstOrNull { len -> prior.endsWith(delta.substring(0, len)) } ?: 0
                    if (overlap != delta.length) buffer.append(delta.substring(overlap))
                }
            }
        }
        return value
    }
}
