package com.jiacimu.lulu.ai

/**
 * Bounded transport recovery for a relay that refused to hold a large request body.
 * This never interprets what the user means or changes a character decision.
 * Only the prompt projection is smaller; persisted facts and original chats stay intact.
 */
internal object RelayRequestPressurePolicy {
    private const val BACKGROUND = "后台主动感知"

    fun isMemoryGuard(status: Int, message: String): Boolean =
        status == 503 && (
            message.contains("请求体内存保护") ||
            (message.contains("Relay", ignoreCase = true) &&
                (message.contains("memory", ignoreCase = true) ||
                    message.contains("request body", ignoreCase = true))) ||
            (message.contains("request body", ignoreCase = true) &&
                message.contains("memory", ignoreCase = true))
        )

    fun canRecover(source: String): Boolean = source == BACKGROUND

    /** Count UTF-8 bytes rather than characters; Chinese and base64 are not interchangeable. */
    fun payloadBytes(system: String, currentUser: String,
                     history: List<ModelConversationMessage>,
                     imageDataUrls: List<String>): Int {
        val parts = sequenceOf(system, currentUser) +
            history.asSequence().map { it.content } + imageDataUrls.asSequence()
        return parts.sumOf { it.toByteArray(Charsets.UTF_8).size.toLong() }
            .coerceAtMost(Int.MAX_VALUE.toLong()).toInt()
    }

    fun shouldCompactBeforeSend(source: String, payloadBytes: Int): Boolean =
        canRecover(source) && payloadBytes > 440_000

    private fun limit(label: String): Int = when (label) {
        "任务规则/工具协议" -> 13_000
        "角色身份" -> 4_000
        "角色人设" -> 8_000
        "用户资料" -> 900
        "全局世界书" -> 2_500
        "角色世界书/设计书" -> 1_800
        "数字世界权威状态" -> 3_500
        "核心记忆" -> 5_000
        "未完成责任" -> 3_500
        "本轮背景依据" -> 11_000
        else -> 900
    }

    private fun excerpt(text: String, allowed: Int, keepTail: Boolean): String {
        if (text.length <= allowed) return text
        if (allowed <= 40) return ""
        // The last portion of background facts often carries CURRENT user evidence.
        // Both sides are clearly marked as partial, never silently merged as facts.
        val marker = "\n【Relay负载保护：部分较早背景省略，原始记录仍保存在手机】\n"
        val n = (allowed - marker.length).coerceAtLeast(0)
        val head = if (keepTail) n / 3 else n * 4 / 5
        return text.take(head) + marker + text.takeLast(n - head)
    }

    /**
     * Full, latest user input is NEVER shortened. Old dialogue keeps complete
     * turns and original roles, newest first; old turns alone are expendable.
     */
    fun compactHistory(history: List<ModelConversationMessage>,
                       maxCharacters: Int = 5_500): List<ModelConversationMessage> {
        var used = 0
        val retained = mutableListOf<ModelConversationMessage>()
        for (turn in history.asReversed()) {
            if (used + turn.content.length > maxCharacters) break
            retained += turn
            used += turn.content.length
        }
        return retained.asReversed()
    }

    fun compactSections(sections: List<Pair<String, String>>): String =
        sections.mapNotNull { (label, content) ->
            if (content.isBlank()) return@mapNotNull null
            val cap = limit(label)
            val selected = excerpt(content, cap, keepTail = label == "本轮背景依据")
            selected.takeIf(String::isNotBlank)
        }.joinToString("\n\n")

    fun savesEnough(originalBytes: Int, compactBytes: Int): Boolean =
        compactBytes < originalBytes - 4_096
}
