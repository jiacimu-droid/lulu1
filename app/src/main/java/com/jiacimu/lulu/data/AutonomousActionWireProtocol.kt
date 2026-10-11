package com.jiacimu.lulu.data

import org.json.JSONObject

/**
 * Bridge one autonomous choice to the names accepted by ToolRouter.
 * The model can use the friendly action enum OR an actual registered tool
 * name. A real tool choice must never degrade into a fake 'silent' action.
 *
 * Parsing never executes a tool. ToolRouter still verifies permissions, IDs
 * and real-world preconditions before recording success.
 */
internal object AutonomousActionWireProtocol {
    private val toolNames = setOf(
        "send_private_message", "send_group_message",
        "send_private_sticker", "send_group_sticker",
        "send_game_invite", "play_solo_game",
        "send_world_invite", "publish_moment", "start_call",
        "write_journal", "read_book", "digital_world_action",
        "set_user_remark", "set_self_nickname",
    )
    private val topLevelArguments = setOf(
        "text", "groupId", "stickerId", "gameId", "location",
        "readingBookId", "worldAction", "itemId", "activityId",
        "incidentId", "approach", "targetCharacterId",
        "title", "content", "nickname", "name", "appearance",
        "position", "itemType",
    )

    fun normalize(original: JSONObject): JSONObject {
        val result = JSONObject(original.toString())
        val action = result.optString("action").trim().lowercase()
        val nested = result.optJSONObject("args")
        if (action in toolNames) {
            val args = JSONObject()
            topLevelArguments.forEach { key ->
                if (nested?.has(key) == true) args.put(key, nested.opt(key))
                else if (result.has(key)) args.put(key, result.opt(key))
            }
            result.put("action", "tool")
                .put("tool", action)
                .put("args", args)
        } else if (nested != null) {
            // 'reading' and 'journal' use the human-friendly action names,
            // but JSON clients sometimes place parameters inside args.
            topLevelArguments.forEach { key ->
                if (!result.has(key) && nested.has(key)) result.put(key, nested.opt(key))
            }
        }
        if (result.optString("action").equals("journal", true)) {
            if (!result.has("journalTitle")) result.put("journalTitle", result.optString("title"))
            if (!result.has("journalContent")) result.put("journalContent", result.optString("content"))
        }
        return result
    }
}
