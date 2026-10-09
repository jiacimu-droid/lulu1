package com.jiacimu.lulu

import com.jiacimu.lulu.data.LuluChatMessage
import java.time.Duration

/** Chat avatar identity follows true message batches, never an arbitrary 2-minute cutoff. */
internal fun sameQqMessageGroup(previous: LuluChatMessage, current: LuluChatMessage): Boolean {
    if (previous.sender != current.sender || previous.authorCharacterId != current.authorCharacterId) return false
    if (previous.sender == LuluChatMessage.Sender.Character) {
        if (previous.replyBatchId != null || current.replyBatchId != null) {
            return previous.replyBatchId != null && previous.replyBatchId == current.replyBatchId
        }
    }
    val gap = Duration.between(previous.createdAt, current.createdAt)
    return !gap.isNegative && gap < Duration.ofMinutes(2)
}
