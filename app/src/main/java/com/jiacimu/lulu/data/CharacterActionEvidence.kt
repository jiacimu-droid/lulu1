package com.jiacimu.lulu.data

import org.json.JSONObject

/** A model-provided ID is not an action receipt. Resolve role ownership and executed origin. */
internal object CharacterActionEvidence {
    fun resolve(role: String, id: String): SharedTimelineEvent? =
        if (role.isBlank() || id.isBlank()) null
        else SharedExperienceTimeline.eventsByIds(role, listOf(id, "$id:group:$role")).firstOrNull()

    private fun successfulTool(e: SharedTimelineEvent): String? {
        if (e.evidenceKind != EventEvidenceKind.ToolResult || e.source != "tool-router") return null
        val wrapper = runCatching { JSONObject(e.content) }.getOrNull() ?: return null
        val result = runCatching { JSONObject(wrapper.optString("result")) }.getOrNull() ?: return null
        if (!result.optBoolean("success") || wrapper.optString("status") in
            setOf("running", "failed", "waiting_user")) return null
        return wrapper.optString("tool")
    }

    fun supports(e: SharedTimelineEvent, action: String): Boolean {
        val tool = successfulTool(e)
        return when (action.trim().lowercase()) {
            "message", "group_message", "phone_reply", "sticker", "group_sticker",
            "kaomoji", "group_kaomoji" ->
                e.evidenceKind == EventEvidenceKind.CharacterStatement &&
                    e.source == "message" && (e.channel.contains("聊") || e.channel.contains("电话"))
            "moment" -> e.evidenceKind == EventEvidenceKind.CharacterStatement && e.source == "moment:self"
            "journal", "write_journal" -> e.evidenceKind == EventEvidenceKind.CharacterStatement && e.source == "journal:own"
            "reading", "read_book" -> tool == "read_book" ||
                (e.source.startsWith("reading:") &&
                    e.evidenceKind in setOf(EventEvidenceKind.Observation, EventEvidenceKind.ToolResult))
            "solo_game", "play_solo_game" -> tool == "play_solo_game" ||
                (e.channel.startsWith("独自游戏") && e.evidenceKind == EventEvidenceKind.Observation)
            "digital_world", "digital_world_action" -> tool == "digital_world_action" ||
                (e.source == "digital-world" && e.evidenceKind == EventEvidenceKind.Observation)
            "silent", "" -> false
            else -> tool != null && (tool == action.trim().lowercase() || action == "tool")
        }
    }
}
