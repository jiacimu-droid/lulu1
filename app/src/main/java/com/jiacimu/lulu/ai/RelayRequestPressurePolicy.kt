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

    /**
     * These aren't local-memory quotas. A relay failure may justify omitting
     * older secondary context from ONE outgoing request, but not corrupting
     * role identity, established relationships, commitments, or the tool rules.
     */
    private val protected = setOf(
        "任务规则/工具协议", "角色身份", "角色人设",
        "核心记忆", "未完成责任", "全局世界书",
        "角色世界书/设计书", "数字世界权威状态",
    )

    private fun limit(label: String): Int = when (label) {
        "本轮背景依据" -> 20_000
        "跨场景连续性锚点", "成长与持续动机", "当前状态" -> 7_000
        "近期原始时间线（新原话优先；旧理解不得覆盖本轮纠正）：" -> 10_000
        else -> 4_000
    }

    private const val OMITTED =
        "【本轮 Relay 请求压力保护：其他较早材料暂未提供，原始记录仍在手机】"

    /** Select complete source lines, never cut an utterance or a negation mid-line. */
    private fun excerptWholeLines(text: String, allowed: Int, mostRecentFirst: Boolean): String {
        if (text.length <= allowed) return text
        val lines = text.lines()
        // The first line often carries the authority/source label. Retain it
        // when selecting from the tail, but leave the original line intact.
        val header = lines.firstOrNull().orEmpty()
            .takeIf { mostRecentFirst && it.length <= 160 }.orEmpty()
        var remaining = (allowed - OMITTED.length - 2 -
            if (header.isNotEmpty()) header.length + 1 else 0).coerceAtLeast(0)
        val selected = mutableListOf<String>()
        val candidates = if (mostRecentFirst)
            (if (header.isNotEmpty()) lines.drop(1) else lines).asReversed() else lines
        // Keep a *contiguous* prefix or suffix; don't splice unrelated lines
        // across an omitted condition into an apparently continuous fact.
        for (line in candidates) {
            val cost = line.length + 1
            if (cost > remaining) break
            selected += line
            remaining -= cost
        }
        val kept = if (mostRecentFirst) selected.asReversed() else selected
        return if (mostRecentFirst)
            listOf(header, OMITTED).filter(String::isNotBlank).plus(kept).joinToString("\n")
        else kept.plus(OMITTED).joinToString("\n")
    }

    /**
     * Full, latest user input is NEVER shortened. Old dialogue keeps complete
     * turns and original roles, newest first; old turns alone are expendable.
     */
    fun compactHistory(history: List<ModelConversationMessage>,
                       maxCharacters: Int = 8_000): List<ModelConversationMessage> {
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
            val selected = if (label in protected) content
                else excerptWholeLines(content, limit(label),
                    mostRecentFirst = label == "本轮背景依据" ||
                        label.contains("时间线") || label.contains("原始依据"))
            selected.takeIf(String::isNotBlank)
        }.joinToString("\n\n")

    fun savesEnough(originalBytes: Int, compactBytes: Int): Boolean =
        compactBytes < originalBytes - 4_096
}
