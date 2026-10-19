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

/** A receipt and the native event it points to are one experience, even when both were recalled.
 * Diary/moment statements stay subjective and never increase this count. */
internal fun independentDevelopmentExposures(events: List<SharedTimelineEvent>): List<SharedTimelineEvent> {
    fun key(event: SharedTimelineEvent, visited: Set<String> = emptySet()): String? {
        if (event.id in visited) return null
        val payload = runCatching { JSONObject(event.content) }.getOrNull()
        val result = payload?.optJSONObject("result") ?: runCatching {
            JSONObject(payload?.optString("result").orEmpty())
        }.getOrNull()
        val parent = when {
            event.source == "tool-router" -> result?.optString("evidenceId").orEmpty()
            event.source.startsWith("learning-material:") -> payload?.optString("originId").orEmpty()
            else -> ""
        }
        if (parent.isNotBlank() && parent != event.id) {
            val original = SharedExperienceTimeline.eventsByIds(event.characterId, listOf(parent)).firstOrNull() ?: return null
            return key(original, visited + event.id)
        }
        return if (event.evidenceKind == EventEvidenceKind.UserStatement) event.id else event.sessionId.ifBlank { event.id }
    }
    return events.filter { it.isDevelopmentExposure() }.mapNotNull { event -> key(event)?.let { it to event } }
        .groupBy({ it.first }, { it.second }).values.map { sameExperience ->
            sameExperience.firstOrNull { it.evidenceKind == EventEvidenceKind.ToolResult } ?: sameExperience.first()
        }
}
