package com.jiacimu.lulu.data

import com.jiacimu.lulu.qqChatImageContextText

/**
 * A read-only projection of the SAME private chat for a phone tool's later
 * feedback turn. This reads the real sent bubbles, not a model's intended
 * speech, and never creates a second memory or changes role decisions.
 */
internal object PhoneTaskNarrativeContext {
    fun render(
        messages: List<LuluChatMessage>,
        characterId: String,
        maxMessages: Int = 8,
    ): String {
        val recent = messages.asSequence()
            .filter { it.status == LuluChatMessage.Status.Sent }
            .filter {
                it.sender != LuluChatMessage.Sender.Character ||
                    it.authorCharacterId == null ||
                    it.authorCharacterId == characterId
            }
            .toList().takeLast(maxMessages.coerceIn(1, 12))
        if (recent.isEmpty()) return "没有可核验的近期已送达聊天消息"
        return recent.mapNotNull { message ->
            val imageContext = qqChatImageContextText(message.content)
            val visible = (imageContext ?: message.content).trim()
                .replace(Regex("\\s+"), " ")
                .take(if (imageContext != null) 800 else 360)
            if (visible.isBlank()) return@mapNotNull null
            val speaker = when {
                message.onBehalfByOwner -> "主人代发（使用角色账号）"
                message.sender == LuluChatMessage.Sender.User -> "主人"
                message.sender == LuluChatMessage.Sender.Character -> "本人已发送"
                else -> "系统记录（不是本人发言）"
            }
            "$speaker：$visible"
        }.joinToString("\n").ifBlank { "没有可核验的近期已送达聊天消息" }
    }
}
