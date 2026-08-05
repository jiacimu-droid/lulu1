package com.jiacimu.lulu.study

import com.jiacimu.lulu.data.InMemoryLuluChatStore
import com.jiacimu.lulu.data.LuluChatMessage
import com.jiacimu.lulu.data.LuluConversation
import com.jiacimu.lulu.data.MigratedDomainStores
import kotlinx.coroutines.flow.MutableStateFlow
import java.time.Instant
import java.util.UUID

/**
 * Keeps the focus-session transcript available to the focus screen without exposing it as a
 * second top-level chat for the same character. Older builds created "<characterId>-study-focus"
 * as an ordinary conversation, which produced two visible chat pages and could also attract
 * proactive messages. The focus transcript is now stored as a hidden child of the character's
 * normal private conversation.
 */
internal fun ensureStudyFocusConversation(characterId: String, displayName: String) {
    val cleanCharacterId = characterId.trim().ifBlank { "lulu" }
    val cleanDisplayName = displayName.trim().ifBlank { "露露" }
    val focusConversationId = "$cleanCharacterId-study-focus"
    val chat = MigratedDomainStores.chat

    // InMemoryLuluChatStore does not expose caller-supplied IDs or parent reassignment. Keep the
    // compatibility migration isolated here so the public chat API stays unchanged.
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
            @Suppress("UNCHECKED_CAST")
            val messageStates = messagesField.get(store) as MutableMap<String, MutableStateFlow<List<LuluChatMessage>>>

            val existingFocus = conversations.value.firstOrNull { it.id == focusConversationId }
            var primary = conversations.value
                .asSequence()
                .filter { conversation ->
                    conversation.characterId == cleanCharacterId &&
                        conversation.id != focusConversationId &&
                        conversation.parentConversationId == null &&
                        conversation.groupChat == null
                }
                .maxByOrNull(LuluConversation::updatedAt)

            if (primary == null) {
                val primaryId = if (cleanCharacterId == "lulu") "lulu-main" else UUID.randomUUID().toString()
                primary = LuluConversation(
                    id = primaryId,
                    characterId = cleanCharacterId,
                    title = cleanDisplayName,
                    lastMessage = "",
                    updatedAt = Instant.now(),
                )
                messageStates.putIfAbsent(primaryId, MutableStateFlow(emptyList()))
            }

            val focusConversation = (existingFocus ?: LuluConversation(
                id = focusConversationId,
                characterId = cleanCharacterId,
                title = "$cleanDisplayName · 专注陪学",
                lastMessage = "",
                updatedAt = Instant.now(),
            )).copy(
                characterId = cleanCharacterId,
                title = "$cleanDisplayName · 专注陪学",
                parentConversationId = primary.id,
                groupChat = null,
            )

            messageStates.putIfAbsent(focusConversationId, MutableStateFlow(emptyList()))
            conversations.value = (
                listOf(primary, focusConversation) +
                    conversations.value.filterNot { it.id == primary.id || it.id == focusConversationId }
                ).sortedByDescending(LuluConversation::updatedAt)
            persistMethod.invoke(store)
        }
    }
}
