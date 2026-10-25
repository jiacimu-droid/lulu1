package com.jiacimu.lulu.data

import com.jiacimu.lulu.qqForwardContextText

/**
 * A model-facing view of existing persisted chat messages, not another history store.
 * Consecutive character bubbles with the same real replyBatchId are ONE turn.
 * Consecutive user bubbles before any character answer are also ONE incoming turn.
 * The transport's message and character budgets are soft at a turn boundary:
 * we never delete half of what someone actually said to make a context window.
 */
internal object ConversationHistoryWindow {
    private fun sameTurn(previous: LuluChatMessage, current: LuluChatMessage): Boolean {
        if (previous.conversationId != current.conversationId ||
            previous.sender != current.sender ||
            previous.authorCharacterId != current.authorCharacterId) return false
        return when (current.sender) {
            LuluChatMessage.Sender.User -> true
            LuluChatMessage.Sender.Character ->
                !previous.replyBatchId.isNullOrBlank() &&
                    previous.replyBatchId == current.replyBatchId
            LuluChatMessage.Sender.System -> false
        }
    }

    fun select(
        messages: List<LuluChatMessage>,
        maxMessages: Int,
        maxCharacters: Int,
        visibleLength: (LuluChatMessage) -> Int,
    ): List<LuluChatMessage> {
        if (maxMessages <= 0 || maxCharacters <= 0 || messages.isEmpty()) return emptyList()
        val turns = mutableListOf<MutableList<LuluChatMessage>>()
        messages.forEach { message ->
            val last = turns.lastOrNull()
            if (last != null && sameTurn(last.last(), message)) last.add(message)
            else turns.add(mutableListOf(message))
        }
        val chosen = ArrayDeque<List<LuluChatMessage>>()
        var chars = 0
        var count = 0
        for (turn in turns.asReversed()) {
            val size = turn.sumOf { visibleLength(it).coerceAtLeast(0) }
            // The most recent whole turn is always kept, even if unusually
            // long. Older turns fit only when BOTH budgets have room.
            if (chosen.isNotEmpty() &&
                (count + turn.size > maxMessages || chars + size > maxCharacters)) break
            chosen.addFirst(turn)
            count += turn.size
            chars += size
        }
        return chosen.flatMap { it }
    }

    /**
     * Quote reference stays tied to an earlier, still-visible message in THIS
     * conversation. Decode existing image descriptions/forward cards rather than
     * interpreting a raw media URI or fabricating sight of an image.
     */
    fun quoteNote(
        message: LuluChatMessage,
        conversation: List<LuluChatMessage>,
        excludedIds: Set<String> = emptySet(),
    ): String {
        val ref = message.replyToMessageId?.takeIf(String::isNotBlank) ?: return ""
        val currentIndex = conversation.indexOfFirst { it.id == message.id }
        if (currentIndex <= 0 || ref in excludedIds) return ""
        val original = conversation.take(currentIndex).firstOrNull { it.id == ref &&
            it.conversationId == message.conversationId &&
            it.status != LuluChatMessage.Status.Failed } ?: return ""
        val preview = qqForwardContextText(original.content).trim().take(480)
        if (preview.isBlank()) return ""
        val role = when (original.sender) {
            LuluChatMessage.Sender.User -> "用户"
            LuluChatMessage.Sender.Character -> "角色"
            LuluChatMessage.Sender.System -> "系统"
        }
        return "（引用之前的${role}消息：${preview}）"
    }
}
