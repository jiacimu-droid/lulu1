package com.jiacimu.lulu.data

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.time.Instant
import java.util.UUID

/**
 * Converts old saved branch/focus records before the current chat model is decoded.
 * After this runs, persisted chat data contains only ordinary private chats and group chats.
 */
object LegacyConversationMigration {
    private const val PREFS_NAME = "lulu_chat_store"
    private const val STATE_KEY = "state_v1"

    fun migrateSavedState(context: Context) {
        val prefs = context.applicationContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val raw = prefs.getString(STATE_KEY, null) ?: return
        val root = runCatching { JSONObject(raw) }.getOrNull() ?: return
        val sourceConversations = root.optJSONArray("conversations") ?: return
        val messagesRoot = root.optJSONObject("messages") ?: JSONObject()

        val all = buildList {
            for (index in 0 until sourceConversations.length()) {
                sourceConversations.optJSONObject(index)?.let { add(JSONObject(it.toString())) }
            }
        }
        if (all.isEmpty()) return

        val byId = all.associateBy { it.optString("id") }
        val legacyIds = all
            .filter { conversation ->
                conversation.stringOrNull("parentConversationId") != null ||
                    conversation.optString("id").endsWith("-study-focus")
            }
            .mapTo(mutableSetOf()) { it.optString("id") }

        val kept = all.filterNot { it.optString("id") in legacyIds }.toMutableList()

        fun findPrivate(characterId: String): JSONObject? = kept
            .filter { candidate ->
                candidate.optString("characterId") == characterId && candidate.optJSONObject("groupChat") == null
            }
            .maxByOrNull { it.optString("updatedAt") }

        fun createPrivate(characterId: String, title: String): JSONObject {
            val id = if (characterId == "lulu" && kept.none { it.optString("id") == "lulu-main" }) {
                "lulu-main"
            } else {
                UUID.randomUUID().toString()
            }
            return JSONObject()
                .put("id", id)
                .put("characterId", characterId)
                .put("title", title.ifBlank { "未命名角色" })
                .put("lastMessage", "")
                .put("updatedAt", Instant.now().toString())
                .put("unreadCount", 0)
                .put("groupChat", JSONObject.NULL)
                .also(kept::add)
        }

        fun privateTarget(source: JSONObject): JSONObject {
            val characterId = source.optString("characterId").ifBlank { "lulu" }
            return findPrivate(characterId)
                ?: createPrivate(characterId, source.optString("title").substringBefore(" · 分支"))
        }

        fun branchTarget(source: JSONObject): JSONObject? {
            var parentId = source.stringOrNull("parentConversationId")
            val visited = mutableSetOf<String>()
            while (!parentId.isNullOrBlank() && visited.add(parentId)) {
                val parent = byId[parentId] ?: break
                if (parentId !in legacyIds && !parent.optString("id").endsWith("-study-focus")) return parent
                parentId = parent.stringOrNull("parentConversationId")
            }
            return null
        }

        val mergeTargets = linkedMapOf<String, MutableList<JSONObject>>()
        val targetObjects = linkedMapOf<String, JSONObject>()
        legacyIds.mapNotNull(byId::get)
            .sortedBy { it.optString("updatedAt") }
            .forEach { source ->
                val target = if (source.optString("id").endsWith("-study-focus")) {
                    privateTarget(source)
                } else {
                    branchTarget(source) ?: privateTarget(source)
                }
                val targetId = target.optString("id")
                targetObjects[targetId] = target
                val merged = mergeTargets.getOrPut(targetId) {
                    messagesRoot.optJSONArray(targetId).toMessageObjects().toMutableList()
                }
                messagesRoot.optJSONArray(source.optString("id")).toMessageObjects().forEach { message ->
                    message.put("conversationId", targetId)
                    message.remove("branchOriginMessageId")
                    merged += message
                }
            }

        mergeTargets.forEach { (targetId, values) ->
            val deduplicated = linkedMapOf<String, JSONObject>()
            values.sortedBy { it.optString("createdAt") }.forEach { message ->
                message.remove("branchOriginMessageId")
                val key = listOf(
                    message.optString("sender"),
                    message.optString("authorCharacterId"),
                    message.optString("content").trim(),
                    message.optString("createdAt"),
                    message.optString("replyToMessageId"),
                ).joinToString("\u0001")
                deduplicated.putIfAbsent(key, message)
            }
            val merged = deduplicated.values.toList()
            messagesRoot.put(targetId, JSONArray(merged))
            targetObjects[targetId]?.apply {
                merged.lastOrNull()?.let { last ->
                    put("lastMessage", last.optString("content"))
                    put("updatedAt", last.optString("createdAt").ifBlank { optString("updatedAt") })
                }
            }
        }

        legacyIds.forEach(messagesRoot::remove)
        kept.forEach { conversation ->
            conversation.remove("parentConversationId")
            conversation.remove("branchOriginMessageId")
            messagesRoot.optJSONArray(conversation.optString("id"))?.let { messages ->
                for (index in 0 until messages.length()) {
                    messages.optJSONObject(index)?.remove("branchOriginMessageId")
                }
            }
        }

        root.put(
            "conversations",
            JSONArray(
                kept.sortedWith(
                    compareByDescending<JSONObject> { it.optJSONObject("groupChat")?.optBoolean("pinned") == true }
                        .thenByDescending { it.optString("updatedAt") },
                ),
            ),
        )
        root.put("messages", messagesRoot)
        prefs.edit().putString(STATE_KEY, root.toString()).commit()
    }

    private fun JSONArray?.toMessageObjects(): List<JSONObject> {
        if (this == null) return emptyList()
        return buildList {
            for (index in 0 until length()) {
                optJSONObject(index)?.let { add(JSONObject(it.toString())) }
            }
        }
    }

    private fun JSONObject.stringOrNull(key: String): String? =
        takeUnless { isNull(key) }
            ?.optString(key)
            ?.takeIf(String::isNotBlank)
}
