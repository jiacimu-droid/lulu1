package com.jiacimu.lulu.data

import com.jiacimu.lulu.ai.ModelConversationMessage
import com.jiacimu.lulu.qqForwardContextText
import com.jiacimu.lulu.recalledMessageIds

/** Build dialogue only from the current private chat's persisted messages, never parse a transcript. */
internal object PrivateChatExpressionContext {
    /** Mixed scenes stay in the autonomous multi-scene path; never attribute a group turn to the user. */
    fun unreadConversation(
        characterId: String,
        conversationId: String,
        messages: List<LuluChatMessage>,
        unread: CompanionUnreadSnapshot,
    ): List<ModelConversationMessage> {
        if (unread.conversationIds != setOf(conversationId) || unread.messageIds.isEmpty()) return emptyList()
        val pending = messages.filter { it.id in unread.messageIds && it.sender == LuluChatMessage.Sender.User }
        if (pending.mapTo(mutableSetOf()) { it.id } != unread.messageIds) return emptyList()
        return conversation(characterId, messages, unread.messageIds,
            pending.joinToString("\n") { qqForwardContextText(it.content) })
    }

    fun conversation(
        characterId: String,
        messages: List<LuluChatMessage>,
        currentUserMessageIds: Set<String>,
        currentInput: String,
        maxMessages: Int = 30,
        maxCharacters: Int = 12_000,
    ): List<ModelConversationMessage> {
        val firstCurrent = messages.indexOfFirst {
            it.sender == LuluChatMessage.Sender.User && it.id in currentUserMessageIds
        }
        if (firstCurrent < 0 || currentInput.isBlank()) return emptyList()
        val recalled = recalledMessageIds(messages)
        val eligible = messages.take(firstCurrent).filter { message ->
            message.id !in recalled && message.status != LuluChatMessage.Status.Failed &&
                (message.sender == LuluChatMessage.Sender.User ||
                    (message.sender == LuluChatMessage.Sender.Character &&
                        (message.authorCharacterId == null || message.authorCharacterId == characterId)))
        }
        fun rendered(message: LuluChatMessage): String {
            val quote = ConversationHistoryWindow.quoteNote(message, messages, recalled)
            val text = qqForwardContextText(message.content).trim()
            return if (quote.isBlank()) text else "$quote\n$text"
        }
        val chosen = ConversationHistoryWindow.select(
            eligible, maxMessages, maxCharacters,
        ) { rendered(it).length }
        // These are real role turns, not one assistant API message per visual
        // bubble. The provider's transport compaction must not cut a reply
        // into a misleading final sentence.
        val history = ConversationHistoryWindow.turns(chosen).map { turn ->
            ModelConversationMessage(
                if (turn.first().sender == LuluChatMessage.Sender.User)
                    ModelConversationMessage.Role.USER else ModelConversationMessage.Role.ASSISTANT,
                turn.joinToString("\n") { rendered(it) },
            )
        }
        // The active input remains authoritative and unabridged. A user may be
        // replying to an older voice transcript or image; restore the real
        // quoted reference from this same conversation before the words, without
        // pretending a missing image description has been visually understood.
        val quoted = messages.filter {
            it.sender == LuluChatMessage.Sender.User && it.id in currentUserMessageIds
        }.mapNotNull { message ->
            ConversationHistoryWindow.quoteNote(message, messages, recalled)
                .takeIf(String::isNotBlank)
        }
        val userContent = if (quoted.isEmpty()) currentInput
            else quoted.joinToString("\n") + "\n" + currentInput
        return history + ModelConversationMessage(ModelConversationMessage.Role.USER, userContent)
    }
}
