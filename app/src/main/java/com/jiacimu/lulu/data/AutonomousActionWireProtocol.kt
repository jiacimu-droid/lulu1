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
        // Existing social tools accept these as authored executor arguments.
        // Missing keys previously vanished when the model used the allowed
        // flat step form, turning an explicit accept/decline into empty args.
        "postId", "requestId", "decision", "greeting",
        "replyToCommentId", "groupName", "memberIds",
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

    /**
     * A syntactically selected build is not executable without its actual
     * required fields. This only validates the model's authored JSON; it never
     * selects a world action, names an item, or invents its appearance.
     */
    internal fun missingWorldBuildFields(normalized: JSONObject): List<String> {
        val action = normalized.optString("action").trim().lowercase()
        val asTool = action == "tool" && normalized.optString("tool") == "digital_world_action"
        if (action != "digital_world" && !asTool) return emptyList()
        val parameters = if (asTool) normalized.optJSONObject("args") ?: JSONObject() else normalized
        if (parameters.optString("worldAction") != "build_home_item") return emptyList()
        return buildList {
            if (parameters.optString("name").isBlank() &&
                parameters.optString("itemName").isBlank()) add("name")
            if (parameters.optString("appearance").isBlank()) add("appearance")
        }
    }

    /** Validate each *actually chosen* secondary world step, including
     * those originating in nextAction, without guessing an intended verb. */
    internal fun missingWorldBuildStepFields(steps: org.json.JSONArray?): List<String> = buildList {
        for (index in 0 until (steps?.length() ?: 0)) {
            val step = steps?.optJSONObject(index)?.let(::normalizeStep) ?: continue
            if (step.optString("tool") != "digital_world_action") continue
            val args = step.optJSONObject("args") ?: continue
            if (args.optString("worldAction") != "build_home_item") continue
            if (args.optString("name").isBlank()) add("steps[$index].name")
            if (args.optString("appearance").isBlank()) add("steps[$index].appearance")
        }
    }

    /**
     * Apply only the same model's explicit field-only corrections to existing
     * build steps; retain exact other steps, order, tools and original args.
     * Missing model values stay missing, not filled by executor heuristics.
     */
    internal fun repairWorldBuildSteps(
        original: org.json.JSONArray?, correction: JSONObject,
    ): org.json.JSONArray? {
        if (original == null) return null
        if (missingWorldBuildStepFields(original).isEmpty()) return original
        val patches = correction.optJSONArray("repairs") ?: return null
        val copied = org.json.JSONArray(original.toString())
        for (patchIndex in 0 until patches.length()) {
            val patch = patches.optJSONObject(patchIndex) ?: continue
            val index = patch.optInt("index", -1)
            if (index !in 0 until copied.length()) continue
            val step = copied.optJSONObject(index)?.let(::normalizeStep) ?: continue
            if (step.optString("tool") != "digital_world_action") continue
            val args = step.optJSONObject("args") ?: continue
            if (args.optString("worldAction") != "build_home_item") continue
            if (args.optString("name").isBlank()) {
                patch.optString("name").trim().take(40).takeIf(String::isNotBlank)
                    ?.let { args.put("name", it) }
            }
            if (args.optString("appearance").isBlank()) {
                patch.optString("appearance").trim().take(500).takeIf(String::isNotBlank)
                    ?.let { args.put("appearance", it) }
            }
            step.put("args", args)
            copied.put(index, step)
        }
        return copied.takeIf { missingWorldBuildStepFields(it).isEmpty() }
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
        // The same authored-field projection must work for ALL registered
        // primary tool choices, not just world actions or side steps.
        // normalizeStep preserves nested args precedence and never fabricates
        // an absent requestId, decision or postId.
        if (result.optString("action") == "tool" &&
            CapabilityRegistry.find(result.optString("tool")) != null) {
            result.put("args", normalizeStep(result).optJSONObject("args") ?: JSONObject())
        }
        // A registered tool sometimes arrives with explicit parameters at
        // the outer level and only worldAction (or an empty object) in args.
        // The direct-action path consumes top-level keys, but Action.TOOL sends
        // args verbatim: mirror *authored* parameters rather than dropping them.
        // Nested args retain precedence; never synthesize item names/looks.
        if ((specified == "tool" && toolName == "digital_world_action") ||
            specified == "digital_world_action") {
            val toolArgs = result.optJSONObject("args")?.let { JSONObject(it.toString()) } ?: JSONObject()
            argumentKeys.forEach { key ->
                if (!toolArgs.has(key) && result.has(key)) toolArgs.put(key, result.opt(key))
            }
            if (toolArgs.optString("name").isBlank() && result.optString("itemName").isNotBlank())
                toolArgs.put("name", result.optString("itemName"))
            result.put("args", toolArgs)
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
