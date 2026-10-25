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
        "planId", "dueAt", "intent", "activityAction", "activityArgs",
        "commitmentTaskId", "repeat",
    )

    // These are existing digital-world executor verbs, not independent ToolRouter
    // tools. A model may use the public direct-action spelling in a nested step.
    private val worldVerbs = setOf(
        "approach_user", "wait_at_entrance", "offer_hand", "offer_hug",
        "offer_follow", "go_home", "visit_cloud_meadow", "visit_public_place",
        "build_home_item", "move_home_item", "remove_home_item",
        "use_home_item", "use_location", "handle_incident",
        "visit_character_home", "visit_resident_home",
        "leave_resident_episode", "interact_resident",
    )

    /**
     * One boundary for explicit secondary decisions from steps, sideActions and
     * nextAction. Their friendly action names use the SAME vocabulary as the
     * primary decision, but OrderedCharacterActions requires registered tools.
     * Never generate an action from prose or a private thought.
     */
    fun normalizeStep(original: JSONObject): JSONObject {
        val step = JSONObject(original.toString())
        val namedTool = step.optString("tool").trim().lowercase()
        val action = step.optString("action").trim().lowercase()
        val args = step.optJSONObject("args")?.let { JSONObject(it.toString()) } ?: JSONObject()
        val nestedTool = if (action == "tool" && namedTool.isBlank())
            args.optString("tool").ifBlank { args.optString("name") }.trim().lowercase()
        else ""
        val worldVerb = when {
            namedTool in worldVerbs -> namedTool
            namedTool.isBlank() && action in worldVerbs -> action
            namedTool.isBlank() && action.isBlank() &&
                step.optString("worldAction").trim().lowercase() in worldVerbs ->
                step.optString("worldAction").trim().lowercase()
            else -> ""
        }
        val candidate = when {
            worldVerb.isNotBlank() -> "digital_world_action"
            namedTool.isNotBlank() -> CapabilityRegistry.scheduledActionName(namedTool)
            nestedTool.isNotBlank() -> CapabilityRegistry.scheduledActionName(nestedTool)
            action in setOf("message", "reply", "private_message") -> "send_private_message"
            action == "journal" -> "write_journal"
            action == "sticker" -> "send_private_sticker"
            action == "group_sticker" -> "send_group_sticker"
            action in setOf("kaomoji", "private_kaomoji") -> "send_private_message"
            action == "group_kaomoji" -> "send_group_message"
            action.isNotBlank() && action != "tool" -> CapabilityRegistry.scheduledActionName(action)
            else -> ""
        }
        // Unknown explicit tool names must stay unknown for the error receipt;
        // there is no fallback to some arbitrary executable capability.
        if (candidate.isNotBlank()) step.put("tool", candidate)
        if (CapabilityRegistry.find(candidate) != null) {
            argumentKeys.forEach { key ->
                if (!args.has(key) && step.has(key)) args.put(key, step.opt(key))
            }
            if (worldVerb.isNotBlank() && args.optString("worldAction").isBlank())
                args.put("worldAction", worldVerb)
            if (candidate == "digital_world_action" &&
                args.optString("location").isBlank() && args.optString("locationCode").isNotBlank())
                args.put("location", args.optString("locationCode"))
            if (candidate == "write_journal") {
                if (args.optString("title").isBlank() && step.optString("journalTitle").isNotBlank())
                    args.put("title", step.optString("journalTitle"))
                if (args.optString("content").isBlank() && step.optString("journalContent").isNotBlank())
                    args.put("content", step.optString("journalContent"))
            }
            step.put("args", args)
        }
        return step
    }

    fun normalize(original: JSONObject): JSONObject {
        val result = JSONObject(original.toString())
        val specified = result.optString("action").trim().lowercase()
        val toolName = if (specified == "tool" || specified.isBlank())
            result.optString("tool").trim().lowercase() else specified
        val friendly = friendlyActionForTool[toolName]
        when {
            friendly != null -> result.put("action", friendly)
            // Registered tools can be named directly as action. The executor
            // still checks authorization and writes the actual outcome.
            toolName.isNotBlank() && CapabilityRegistry.find(toolName) != null -> {
                result.put("action", "tool")
                result.put("tool", toolName)
            }
            // An explicit top-level utterance is a message even if a provider
            // omitted the label. Never infer a tool from prose or nested thoughts.
            specified.isBlank() && toolName.isBlank() &&
                (result.optString("text").isNotBlank() ||
                    result.optJSONArray("bubbles")?.let { bubbles ->
                        (0 until bubbles.length()).any { index ->
                            when (val bubble = bubbles.opt(index)) {
                                is String -> bubble.isNotBlank()
                                is JSONObject -> bubble.optString("text").isNotBlank()
                                else -> false
                            }
                        }
                    } == true) -> result.put("action", "message")
        }
        if (result.optString("action") == "tool" &&
            result.optString("tool").isNotBlank()) {
            result.put("tool", result.optString("tool").trim().lowercase())
        }
        // Some models return {"action":"tool","args":{"tool":"read_xiaohongshu",...}}
        // instead of the top-level name. Normalize the explicit name; NEVER
        // guess a tool merely from an unrelated piece of reply text.
        if (specified == "tool" && result.optString("tool").isBlank()) {
            val nestedName = result.optJSONObject("args")?.optString("tool").orEmpty()
                .ifBlank { result.optJSONObject("args")?.optString("name").orEmpty() }
            if (nestedName.isNotBlank()) {
                val registeredName = nestedName.trim().lowercase()
                result.put("tool", registeredName)
                friendlyActionForTool[registeredName]?.let { result.put("action", it) }
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
