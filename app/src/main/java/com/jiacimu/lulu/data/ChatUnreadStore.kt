package com.jiacimu.lulu.data

import android.content.Context
import android.content.SharedPreferences
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Persistent read receipts for chat bubbles.
 *
 * A role reply may be split into several semantic bubbles. Each unread character bubble therefore
 * counts as one unread message. We store the last actually-viewed message instead of incrementing a
 * fragile counter at append time, so deletions/retractions and foreground viewing stay consistent.
 */
object ChatUnreadStore {
    private const val PREFS_NAME = "lulu_chat_read_receipts_v1"
    private const val KEY_LAST_ID = "last_read_id_"
    private const val KEY_LAST_AT = "last_read_at_"

    private var prefs: SharedPreferences? = null
    private val mutableRevision = MutableStateFlow(0)
    val revision: StateFlow<Int> = mutableRevision.asStateFlow()

    @Synchronized
    fun initialize(context: Context) {
        if (prefs != null) return
        prefs = context.applicationContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
    }

    /**
     * Upgrade baseline only. Existing conversations should not suddenly show their entire historical
     * backlog as unread when this feature first ships. Once a receipt exists it is never overwritten
     * by this method, so genuinely new background messages remain unread across process restarts.
     */
    @Synchronized
    fun ensureBaseline(conversationId: String, messages: List<LuluChatMessage>) {
        val cleanId = conversationId.trim()
        if (cleanId.isBlank()) return
        val storage = prefs ?: return
        val idKey = KEY_LAST_ID + cleanId
        if (storage.contains(idKey)) return
        val last = messages.lastOrNull()
        storage.edit()
            .putString(idKey, last?.id.orEmpty())
            .putLong(KEY_LAST_AT + cleanId, last?.createdAt?.toEpochMilli() ?: System.currentTimeMillis())
            .apply()
        bumpRevisionLocked()
    }

    /** Mark everything currently present in this conversation as actually viewed. */
    @Synchronized
    fun markRead(conversationId: String, messages: List<LuluChatMessage>) {
        val cleanId = conversationId.trim()
        if (cleanId.isBlank()) return
        val storage = prefs ?: return
        val last = messages.lastOrNull()
        val nextId = last?.id.orEmpty()
        val nextAt = last?.createdAt?.toEpochMilli() ?: System.currentTimeMillis()
        val idKey = KEY_LAST_ID + cleanId
        val atKey = KEY_LAST_AT + cleanId
        if (
            storage.contains(idKey) &&
            storage.getString(idKey, "").orEmpty() == nextId &&
            storage.getLong(atKey, Long.MIN_VALUE) == nextAt
        ) {
            return
        }
        storage.edit()
            .putString(idKey, nextId)
            .putLong(atKey, nextAt)
            .apply()
        bumpRevisionLocked()
    }

    /** Number of sent character bubbles that arrived after the last message the user actually viewed. */
    @Synchronized
    fun unreadCount(conversationId: String, messages: List<LuluChatMessage>): Int {
        val cleanId = conversationId.trim()
        if (cleanId.isBlank()) return 0
        val storage = prefs ?: return 0
        val idKey = KEY_LAST_ID + cleanId
        if (!storage.contains(idKey)) {
            return messages.count(::isUnreadCharacterBubble)
        }

        val lastReadId = storage.getString(idKey, "").orEmpty()
        if (lastReadId.isNotBlank()) {
            val markerIndex = messages.indexOfLast { message -> message.id == lastReadId }
            if (markerIndex >= 0) {
                return messages.asSequence()
                    .drop(markerIndex + 1)
                    .count(::isUnreadCharacterBubble)
            }
        }

        // The exact marker may have been deleted/retracted. The timestamp keeps later bubbles unread
        // without resurrecting already-read older history.
        val lastReadAt = storage.getLong(KEY_LAST_AT + cleanId, Long.MIN_VALUE)
        return messages.count { message ->
            isUnreadCharacterBubble(message) && message.createdAt.toEpochMilli() > lastReadAt
        }
    }

    @Synchronized
    fun clearConversation(conversationId: String) {
        val cleanId = conversationId.trim()
        if (cleanId.isBlank()) return
        val storage = prefs ?: return
        storage.edit()
            .remove(KEY_LAST_ID + cleanId)
            .remove(KEY_LAST_AT + cleanId)
            .apply()
        bumpRevisionLocked()
    }

    private fun isUnreadCharacterBubble(message: LuluChatMessage): Boolean =
        message.sender == LuluChatMessage.Sender.Character && message.status == LuluChatMessage.Status.Sent

    private fun bumpRevisionLocked() {
        mutableRevision.value = mutableRevision.value + 1
    }
}
