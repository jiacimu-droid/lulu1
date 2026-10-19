package com.jiacimu.lulu.data

import org.json.JSONObject

/**
 * Keep friendly autonomous action names and registered app-tool names on the
 * SAME receipt path. A 'send_private_message' must still count as a MESSAGE,
 * so unread cursors, notifications, delivery links and growth all recognize
 * real execution rather than recording it as an opaque generic tool.
 */
internal object AutonomousActionWireProtocol {
    private val friendlyActionForTool = mapOf(
        "send_private_message" to "message",
        "send_group_message" to "group_message",
        "send_private_sticker" to "sticker",
        "send_group_sticker" to "group_sticker",
        "send_game_invite" to "game_invite",
        "play_solo_game" to "solo_game",
        "send_world_invite" to "world_invite",
        "publish_moment" to "moment",
        "start_call" to "call",
        "write_journal" to "journal",
        "read_book" to "reading",
        "digital_world_action" to "digital_world",
        "set_user_remark" to "user_remark",
        "set_self_nickname" to "self_nickname",
    )
    private val argumentKeys = setOf(
        "text", "groupId", "stickerId", "gameId", "location", "locationCode",
        "readingBookId", "worldAction", "itemId", "activityId",
        "incidentId", "approach", "targetCharacterId",
        "title", "content", "nickname", "messageId", "reason", "name", "appearance",
        "position", "itemType", "journalTitle", "journalContent",
        "planId", "dueAt", "intent", "activityAction",
    )

    fun normalize(original: JSONObject): JSONObject {
        val result = JSONObject(original.toString())
        val specified = result.optString("action").trim().lowercase()
        val toolName = if (specified == "tool")
            result.optString("tool").trim().lowercase() else specified
        val friendly = friendlyActionForTool[toolName]
        if (friendly != null) result.put("action", friendly)
        // Some models return {"action":"tool","args":{"tool":"read_xiaohongshu",...}}
        // instead of the top-level name. Normalize the explicit name; NEVER
        // guess a tool merely from an unrelated piece of reply text.
        if (specified == "tool" && result.optString("tool").isBlank()) {
            val nestedName = result.optJSONObject("args")?.optString("tool").orEmpty()
                .ifBlank { result.optJSONObject("args")?.optString("name").orEmpty() }
            if (nestedName.isNotBlank()) {
                result.put("tool", nestedName)
                friendlyActionForTool[nestedName]?.let { result.put("action", it) }
            }
        }
        // Favorites have no top-level "favorite" enum: they must remain a
        // real registered tool, even when a model calls it as a direct action.
        // Previously this valid request was parsed as an unknown action and
        // silently became an unexecuted/invalid autonomous decision.
        if (toolName == "favorite_user_message") {
            result.put("action", "tool")
            result.put("tool", "favorite_user_message")
            if (result.optJSONObject("args") == null) result.put("args", JSONObject().apply {
                put("messageId", result.optString("messageId"))
                put("reason", result.optString("reason"))
            })
        }
        val nested = result.optJSONObject("args")
        if (nested != null) {
            argumentKeys.forEach { key ->
                if ((!result.has(key) || result.optString(key).isBlank()) && nested.has(key)) {
                    result.put(key, nested.opt(key))
                }
            }
        }
        // Two wire spellings for public-place destination.
        if (result.optString("action").equals("digital_world", true) &&
            result.optString("location").isBlank() && result.optString("locationCode").isNotBlank()) {
            result.put("location", result.optString("locationCode"))
        }
        if (result.optString("action").equals("journal", true)) {
            if (result.optString("journalTitle").isBlank())
                result.put("journalTitle", result.optString("title"))
            if (result.optString("journalContent").isBlank())
                result.put("journalContent", result.optString("content"))
        }
        return result
    }
}
