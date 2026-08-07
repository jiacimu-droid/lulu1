package com.jiacimu.lulu

import com.jiacimu.lulu.data.LuluChatMessage
import com.jiacimu.lulu.data.LuluChatStore

private val RecallReceiptRegex = Regex("^\\[撤回\\|([^]]+)]\\s*", RegexOption.IGNORE_CASE)

internal fun recalledMessageIdFromReceipt(content: String): String? =
    RecallReceiptRegex.find(content)?.groupValues?.getOrNull(1)?.trim()?.takeIf(String::isNotBlank)

internal fun stripRecallReceiptDirective(content: String): String =
    content.replace(RecallReceiptRegex, "").trim()

internal fun recalledMessageIds(messages: List<LuluChatMessage>): Set<String> =
    messages.asSequence()
        .filter { it.sender == LuluChatMessage.Sender.System }
        .mapNotNull { recalledMessageIdFromReceipt(it.content) }
        .toSet()

/**
 * QQ-style recall is not deletion. Keep the original message and its raw timeline event intact,
 * then append a structured recall receipt. The UI hides the recalled bubble by the referenced ID.
 */
internal fun LuluChatStore.retractCharacterMessage(messageId: String, actorDisplayName: String): Boolean {
    val conversation = conversations.value.firstOrNull { item ->
        messages(item.id).value.any { it.id == messageId }
    } ?: return false
    val original = messages(conversation.id).value.firstOrNull { it.id == messageId }
        ?.takeIf { it.sender == LuluChatMessage.Sender.Character }
        ?: return false
    val alreadyRecalled = messages(conversation.id).value.any { message ->
        recalledMessageIdFromReceipt(message.content) == original.id
    }
    if (alreadyRecalled) return true

    appendSystemMessage(
        conversation.id,
        "[撤回|${original.id}] ${actorDisplayName.trim().ifBlank { "角色" }}撤回了一条消息。",
    )
    return true
}
