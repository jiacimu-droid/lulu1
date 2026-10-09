package com.jiacimu.lulu

import com.jiacimu.lulu.data.LuluChatMessage
import java.time.Instant

/**
 * A call is NOT the existing QQ history. Old chat bubbles must never become
 * subtitles just because the phone dialog is opened or restored.
 */
internal fun actualPhoneCaptions(
    messages: List<LuluChatMessage>,
    connectedAt: Instant?,
): List<LuluChatMessage> {
    val start = connectedAt ?: return emptyList()
    return messages.filter { item ->
        item.createdAt >= start && item.sender != LuluChatMessage.Sender.System
    }
}
