package com.jiacimu.lulu.study

import com.jiacimu.lulu.data.InMemoryLuluChatStore
import com.jiacimu.lulu.data.LuluChatMessage
import com.jiacimu.lulu.data.LuluConversation
import com.jiacimu.lulu.data.MigratedDomainStores
import kotlinx.coroutines.flow.MutableStateFlow
import java.time.Instant

/**
 * Compatibility guard for focus sessions created before the chat store required an explicit
 * conversation record. StudyFocusCompleteScreen historically addresses a stable
 * "<characterId>-study-focus" conversation, so restored sessions must recreate that record
 * before collecting or appending messages.
 */
internal fun ensureStudyFocusConversation(characterId: String, displayName: String) {
    val cleanCharacterId = characterId.trim().ifBlank { "lulu" }
    val conversationId = "$cleanCharacterId-study-focus"
    val chat = MigratedDomainStores.chat
    if (chat.conversations.value.any { conversation -> conversation.id == conversationId }) return

    // InMemoryLuluChatStore does not yet expose an API for a caller-supplied conversation ID.
    // Keep this migration in one isolated guard so the normal chat API remains unchanged.
    val store = chat as? InMemoryLuluChatStore ?: return
    runCatching {
        val type = InMemoryLuluChatStore::class.java
        val lockField = type.getDeclaredField("lock").apply { isAccessible = true }
        val conversationField = type.getDeclaredField("conversationState").apply { isAccessible = true }
        val messagesField = type.getDeclaredField("messageStates").apply { isAccessible = true }
        val persistMethod = type.getDeclaredMethod("persistLocked").apply { isAccessible = true }
        val lock = lockField.get(store)

        synchronized(lock) {
            @Suppress("UNCHECKED_CAST")
            val conversations = conversationField.get(store) as MutableStateFlow<List<LuluConversation>>
            if (conversations.value.any { conversation -> conversation.id == conversationId }) return@synchronized

            @Suppress("UNCHECKED_CAST")
            val messageStates = messagesField.get(store) as MutableMap<String, MutableStateFlow<List<LuluChatMessage>>>
            val restored = LuluConversation(
                id = conversationId,
                characterId = cleanCharacterId,
                title = "${displayName.trim().ifBlank { "露露" }} · 专注陪学",
                lastMessage = "",
                updatedAt = Instant.now(),
            )
            messageStates.putIfAbsent(conversationId, MutableStateFlow(emptyList()))
            conversations.value = (listOf(restored) + conversations.value)
                .sortedByDescending(LuluConversation::updatedAt)
            persistMethod.invoke(store)
        }
    }
}
