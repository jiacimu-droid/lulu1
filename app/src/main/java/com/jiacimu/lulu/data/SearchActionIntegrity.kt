package com.jiacimu.lulu.data

import org.json.JSONArray
import org.json.JSONObject

/**
 * A real-user search request plus the character's own accepted search promise
 * is a tool-delivery obligation, not merely an attractive future-tense bubble.
 * This detects a narrow protocol omission for a corrective SAME-role decision;
 * it does not automatically search, assign a query or overrule a refusal.
 */
internal object SearchActionIntegrity {
    private val requests = Regex("搜索|搜|查一下|查查|查找|检索|上网查|网上找|帮我找|结果呢")
    private val commits = Regex("我.{0,24}(?:搜|搜索|查|查找|检索)|这就.{0,12}(?:搜|查)|马上.{0,12}(?:搜|查)|(?:搜|查).{0,8}(?:一下|看看)")
    private val declines = Regex("不搜|不查|没搜|没查|不会搜|不能搜|不打算搜|没有搜|没办法查|不想搜")
    private val toolNames = setOf("web_search", "image_search")

    fun needsFollowThrough(userText: String, speech: String, action: String,
        mainTool: String, sideActions: JSONArray?): Boolean {
        if (action != "message" || !requests.containsMatchIn(userText) ||
            !commits.containsMatchIn(speech) || declines.containsMatchIn(speech)) return false
        if (mainTool in toolNames) return false
        for (index in 0 until (sideActions?.length() ?: 0)) {
            if (sideActions?.optJSONObject(index)?.optString("tool") in toolNames) return false
        }
        return true
    }

    /** Model-authored, tool-verified. Never infer query, url or permission from prose. */
    fun validatedAction(raw: String): JSONObject? {
        val json = ModelStructuredOutput.objectOrNull(raw) ?: return null
        val tool = json.optString("tool").trim()
        if (tool !in toolNames) return null
        val args = json.optJSONObject("args") ?: return null
        val query = args.optString("query").trim()
        if (query.length !in 2..128) return null
        val op = args.optString("op", "search").ifBlank { "search" }
        if (tool == "web_search" && op !in setOf("search", "search_read")) return null
        if (tool == "image_search" && op !in setOf("search", "search_send")) return null
        return JSONObject().put("tool", tool)
            .put("args", JSONObject(args.toString()).put("query", query))
            .put("requiresPreviousSuccess", true)
    }
}
