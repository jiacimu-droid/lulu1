package com.jiacimu.lulu.data

import org.json.JSONObject

/** An expressed feeling can support a preference, but cannot prove the underlying action happened. */
internal fun SharedTimelineEvent.isDevelopmentExposure(): Boolean = when (evidenceKind) {
    EventEvidenceKind.UserStatement, EventEvidenceKind.Observation -> true
    EventEvidenceKind.ToolResult -> runCatching {
        val result = JSONObject(content)
        result.optString("status") == "succeeded" || result.optBoolean("success") ||
            result.optString("result").takeIf { it.startsWith("{") }?.let { JSONObject(it).optBoolean("success") } == true
    }.getOrDefault(false)
    EventEvidenceKind.Legacy -> source.startsWith("reading:") || channel.startsWith("独自阅读《") ||
        (channel.startsWith("数字世界见面·") && id.startsWith("meeting-")) ||
        id.startsWith("world-fact-") || channel.startsWith("独自游戏")
    else -> false
}
internal fun SharedTimelineEvent.supportsDevelopmentReflection(): Boolean = isDevelopmentExposure() ||
    evidenceKind == EventEvidenceKind.CharacterStatement
