package com.jiacimu.lulu.study

import com.jiacimu.lulu.data.InMemoryLuluChatStore
import com.jiacimu.lulu.data.LuluChatMessage
import com.jiacimu.lulu.data.LuluConversation
import com.jiacimu.lulu.data.MigratedDomainStores
import kotlinx.coroutines.flow.MutableStateFlow
import java.time.Instant
import java.util.UUID

/**
 * Focus-session dialogue belongs to the character's ordinary private chat.
 *
 * Older builds stored it in a synthetic "<characterId>-study-focus" conversation. This helper
 * resolves the real private conversation, merges any legacy focus messages into it once, removes
 * the synthetic record, and returns the ordinary conversation id used by every new focus message.
 */
internal fun ensureStudyFocusConversation(characterId: String, displayName: String): String {
    val cleanCharacterId = characterId.trim().ifBlank { "lulu" }
    val cleanDisplayName = displayName.trim().ifBlank { "露露" }
    val legacyFocusId = "$cleanCharacterId-study-focus"
    val chat = MigratedDomainStores.chat
    val store = chat as? InMemoryLuluChatStore
        ?: return chat.ensureConversation(cleanCharacterId, cleanDisplayName).id

    var resolvedConversationId = ""
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

            val current = conversations.value
            var primary = current
                .asSequence()
                .filter { conversation ->
                    conversation.characterId == cleanCharacterId &&
                        conversation.id != legacyFocusId &&
                        conversation.parentConversationId == null &&
                        conversation.groupChat == null
                }
                .maxByOrNull(LuluConversation::updatedAt)

            if (primary == null) {
                val preferredId = if (
                    cleanCharacterId == "lulu" && current.none { it.id == "lulu-main" }
                ) "lulu-main" else UUID.randomUUID().toString()
                primary = LuluConversation(
                    id = preferredId,
                    characterId = cleanCharacterId,
                    title = cleanDisplayName,
                    lastMessage = "",
                    updatedAt = Instant.now(),
                )
                messageStates.putIfAbsent(primary.id, MutableStateFlow(emptyList()))
            }
            val primaryConversation = primary ?: return@synchronized
            val primaryState = messageStates.getOrPut(primaryConversation.id) { MutableStateFlow(emptyList()) }
            val legacyMessages = messageStates[legacyFocusId]?.value.orEmpty()
                .map { message -> message.copy(conversationId = primaryConversation.id) }

            val mergedMessages = (primaryState.value + legacyMessages)
                .distinctBy(LuluChatMessage::id)
                .sortedBy(LuluChatMessage::createdAt)
            primaryState.value = mergedMessages
            messageStates.remove(legacyFocusId)

            val updatedPrimary = primaryConversation.copy(
                title = primaryConversation.title.ifBlank { cleanDisplayName },
                lastMessage = mergedMessages.lastOrNull()?.content.orEmpty(),
                updatedAt = mergedMessages.lastOrNull()?.createdAt ?: primaryConversation.updatedAt,
            )
            conversations.value = (
                listOf(updatedPrimary) + current.filterNot { conversation ->
                    conversation.id == primaryConversation.id || conversation.id == legacyFocusId
                }
            ).sortedByDescending(LuluConversation::updatedAt)
            persistMethod.invoke(store)
            resolvedConversationId = updatedPrimary.id
        }
    }

    return resolvedConversationId.ifBlank {
        chat.conversations.value
            .filter { conversation ->
                conversation.characterId == cleanCharacterId &&
                    conversation.id != legacyFocusId &&
                    conversation.parentConversationId == null &&
                    conversation.groupChat == null
            }
            .maxByOrNull(LuluConversation::updatedAt)
            ?.id
            ?: chat.ensureConversation(cleanCharacterId, cleanDisplayName).id
    }
}
