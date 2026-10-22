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


/**
 * Cloud and on-device ASR occasionally hallucinate sound-effect captions from
 * room noise (e.g. （笑）（不幸）). These are not confirmed user words and must
 * not become user chat bubbles, emotional evidence or model turns.
 * Real spoken words OUTSIDE caption brackets are preserved verbatim.
 */
internal object PhoneTranscriptEvidence {
    private val captionOnly = Regex(
        """^(?:[\\s，,。.!！?？]*[（(【\\[][^(（)）】\\]]{1,24}[)）】\\]][\\s，,。.!！?？]*)+$"""
    )
    fun isConfirmedSpeech(raw: String): Boolean {
        val value = raw.trim()
        return value.isNotEmpty() && !captionOnly.matches(value)
    }
}
