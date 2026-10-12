package com.jiacimu.lulu

import com.jiacimu.lulu.data.LuluChatMessage

/** A concrete send batch, including autonomous messages with no preceding user request. */
internal data class RegeneratableReplyTurn(
    val anchorMessageId: String?,
    val generatedMessageIds: List<String>,
)

internal fun regeneratableLatestTurn(messages: List<LuluChatMessage>, selectedMessageId: String): RegeneratableReplyTurn? {
    val selectedIndex = messages.indexOfFirst { it.id == selectedMessageId }
    if (selectedIndex < 0) return null
    val selected = messages[selectedIndex]
    if (selected.sender != LuluChatMessage.Sender.Character) return null
    val batch = selected.replyBatchId?.takeIf(String::isNotBlank)
    val generated = if (batch != null) messages.filter {
        it.sender == LuluChatMessage.Sender.Character && it.replyBatchId == batch &&
            it.authorCharacterId == selected.authorCharacterId
    } else listOf(selected) // Legacy unbatched messages cannot safely absorb their neighbours.
    val lastIndex = messages.indexOfLast { it.id in generated.map(LuluChatMessage::id) }
    // New user speech or a later role turn makes this an older historical turn.
    if (messages.drop(lastIndex + 1).any { it.sender != LuluChatMessage.Sender.System }) return null
    val firstIndex = messages.indexOfFirst { it.id == generated.first().id }
    val anchor = messages.take(firstIndex).lastOrNull { it.sender == LuluChatMessage.Sender.User }
    return RegeneratableReplyTurn(anchor?.id, generated.map(LuluChatMessage::id))
}
