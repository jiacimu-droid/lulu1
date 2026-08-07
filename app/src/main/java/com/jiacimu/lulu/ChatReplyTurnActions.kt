package com.jiacimu.lulu

import com.jiacimu.lulu.data.LuluChatMessage

/**
 * Describes the latest generated reply turn. A turn starts after the last user message (or user poke)
 * and contains the character bubbles plus the character-side system receipts created from that reply.
 * Regeneration is intentionally limited to this latest turn so older conversation history is never
 * silently rewritten underneath newer user messages. Keeping this boundary explicit also lets the
 * chat screen delete the old turn, its voice cache, and then run the normal reply pipeline again.
 */
internal data class RegeneratableReplyTurn(
    val anchorMessageId: String,
    val generatedMessageIds: List<String>,
)

internal fun regeneratableLatestTurn(
    messages: List<LuluChatMessage>,
    selectedMessageId: String,
): RegeneratableReplyTurn? {
    val selected = messages.firstOrNull { it.id == selectedMessageId } ?: return null
    if (selected.sender != LuluChatMessage.Sender.Character) return null

    val anchorIndex = messages.indexOfLast(::isUserTurnAnchor)
    if (anchorIndex < 0) return null
    val anchor = messages[anchorIndex]
    val generated = messages.drop(anchorIndex + 1)
        .filter(::belongsToGeneratedReply)
        .map(LuluChatMessage::id)
    if (selectedMessageId !in generated) return null
    return RegeneratableReplyTurn(anchor.id, generated)
}

private fun isUserTurnAnchor(message: LuluChatMessage): Boolean =
    message.sender == LuluChatMessage.Sender.User ||
        (message.sender == LuluChatMessage.Sender.System && message.content.startsWith("[戳一戳] 你戳了戳"))

private fun belongsToGeneratedReply(message: LuluChatMessage): Boolean = when (message.sender) {
    LuluChatMessage.Sender.Character -> true
    LuluChatMessage.Sender.User -> false
    LuluChatMessage.Sender.System ->
        message.content.startsWith("[撤回|") ||
            (message.content.startsWith("[戳一戳]") && !message.content.startsWith("[戳一戳] 你戳了戳"))
}
