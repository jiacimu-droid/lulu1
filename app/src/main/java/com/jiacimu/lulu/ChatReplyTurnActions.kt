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

/** A fresh alternative has only the chat preceding the old reply as history. */
internal fun replyHistoryBeforeTurn(
    messages: List<LuluChatMessage>,
    generatedMessageIds: Collection<String>,
): List<LuluChatMessage> {
    val ids = generatedMessageIds.toSet()
    val first = messages.indexOfFirst { it.id in ids }
    require(first >= 0 && ids.isNotEmpty()) { "原回复已失效，不得在较新聊天后重新生成" }
    return messages.take(first)
}

/** The old role message may have both private and group timeline identities. */
internal fun excludedReplyTimelineIds(
    generatedMessageIds: Collection<String>, characterId: String,
): Set<String> = generatedMessageIds.flatMapTo(mutableSetOf()) {
    listOf(it, "$it:group:$characterId")
}

/**
 * A fresh reply consumes the original inbound burst exactly ONCE: the last
 * user's messages are input, not both "history" and "new text".
 */
internal data class FreshReplyWindow(
    val earlierHistory: List<LuluChatMessage>,
    val incomingMessages: List<LuluChatMessage>,
)

internal fun freshReplyWindow(
    messages: List<LuluChatMessage>, generatedMessageIds: Collection<String>,
): FreshReplyWindow {
    val before = replyHistoryBeforeTurn(messages, generatedMessageIds)
    val incoming = before.takeLastWhile { it.sender != LuluChatMessage.Sender.Character }
    return FreshReplyWindow(
        earlierHistory = before.dropLast(incoming.size),
        incomingMessages = incoming,
    )
}
