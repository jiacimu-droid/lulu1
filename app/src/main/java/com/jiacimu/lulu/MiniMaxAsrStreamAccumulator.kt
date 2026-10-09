package com.jiacimu.lulu

import org.json.JSONObject

/** Supports actual additive deltas and corrected full-text snapshots without swallowing repeated syllables. */
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
            // A delta means NEW characters. Repeated "哈" is real speech;
            // deduplicating identical deltas would erase laughter/stuttering.
            buffer.append(delta)
        }
        return value
    }
}
