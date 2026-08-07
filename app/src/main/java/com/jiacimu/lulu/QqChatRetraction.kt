package com.jiacimu.lulu

import com.jiacimu.lulu.data.LuluChatMessage
import com.jiacimu.lulu.data.LuluChatStore
import com.jiacimu.lulu.data.SharedExperienceTimeline

/**
 * QQ-style recall is not the same thing as deleting history. The visible message disappears, but
 * the raw timeline keeps the fact that the role said it and then recalled it.
 */
internal fun LuluChatStore.retractCharacterMessage(messageId: String, actorDisplayName: String): Boolean {
    val conversation = conversations.value.firstOrNull { item ->
        messages(item.id).value.any { it.id == messageId }
    } ?: return false
    val original = messages(conversation.id).value.firstOrNull { it.id == messageId }
        ?.takeIf { it.sender == LuluChatMessage.Sender.Character }
        ?: return false
    if (!deleteMessage(messageId)) return false

    // deleteMessage correctly removes ordinary deleted content from the raw timeline. A recall is
    // different, so put the original event back before recording the visible recall receipt.
    SharedExperienceTimeline.recordConversationMessage(conversation, original)
    appendSystemMessage(conversation.id, "[撤回] $actorDisplayName 撤回了一条消息。")
    return true
}
