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
        val previous = messages.take(firstCurrent).filter { message ->
            message.id !in recalled && message.status != LuluChatMessage.Status.Failed &&
                (message.sender == LuluChatMessage.Sender.User ||
                    (message.sender == LuluChatMessage.Sender.Character &&
                        (message.authorCharacterId == null || message.authorCharacterId == characterId)))
        }.takeLast(maxMessages)
        val history = ArrayDeque<ModelConversationMessage>()
        var characters = 0
        for (message in previous.asReversed()) {
            val content = qqForwardContextText(message.content).trim()
            if (content.isBlank()) continue
            // Keep whole turns. Neither old replies nor the current input are clipped.
            if (characters + content.length > maxCharacters) break
            history.addFirst(ModelConversationMessage(
                if (message.sender == LuluChatMessage.Sender.User) ModelConversationMessage.Role.USER
                else ModelConversationMessage.Role.ASSISTANT,
                content,
            ))
            characters += content.length
        }
        return history.toList() + ModelConversationMessage(ModelConversationMessage.Role.USER, currentInput)
    }
}
