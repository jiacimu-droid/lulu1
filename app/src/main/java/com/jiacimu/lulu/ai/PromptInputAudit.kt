package com.jiacimu.lulu.ai

import com.jiacimu.lulu.data.TokenBreakdownItem

/**
 * Audits the exact text routed into the provider request, without storing prompt contents.
 * Provider token usage is request-wide. Per-section tokens below are estimates only.
 */
internal object PromptInputAudit {
    data class Snapshot(
        val breakdown: List<TokenBreakdownItem>,
        val textCharacters: Int,
        val textUtf8Bytes: Long,
        val imageCount: Int,
        val imageDataUrlBytes: Long,
        val messageCount: Int,
        val reduced: Boolean,
    ) {
        val coveredCharacters: Long get() = breakdown.sumOf { it.chars.toLong() }
        val estimatedTextTokens: Int get() = breakdown.sumOf { it.estimatedTokens }
        val coverageMatches: Boolean get() = textCharacters.toLong() == coveredCharacters
    }

    private val heading = Regex("(?m)(?=^[ \\t]*【[^\\n】]{2,48}】)")

    private fun splitHeadings(label: String, content: String): List<Pair<String, String>> {
        if (content.isEmpty()) return emptyList()
        val starts = heading.findAll(content).map { it.range.first }.filter { it > 0 }.toList()
        val boundaries = listOf(0) + starts + content.length
        return boundaries.zipWithNext().mapIndexed { index, (start, end) ->
            val piece = content.substring(start, end)
            val title = heading.find(piece)?.value?.trim()?.take(48)
                ?: if (index == 0) "开头/说明" else "续段"
            (label + " / " + title) to piece
        }.filter { it.second.isNotEmpty() }
    }

    private fun detailed(label: String, content: String, instruction: String?): List<Pair<String, String>> {
        if (content.isBlank()) return emptyList()
        if (label == "任务规则/工具协议" && instruction != null) {
            val suffix = "本次任务：" + instruction
            if (content.endsWith(suffix)) {
                val common = content.dropLast(suffix.length)
                return listOf("基础身份与通用规则" to common).filter { it.second.isNotEmpty() } +
                    splitHeadings("本轮任务指令", suffix)
            }
        }
        return listOf(label to content)
    }

    fun inspect(
        systemSections: List<Pair<String, String>>,
        system: String,
        user: String,
        history: List<ModelConversationMessage>,
        images: List<String>,
        instruction: String? = null,
        reduced: Boolean = false,
    ): Snapshot {
        val parts = mutableListOf<Pair<String, String>>()
        systemSections.forEach { (label, content) -> parts += detailed(label, content, instruction) }
        val systemPartsCharacters = systemSections.filter { it.second.isNotBlank() }.sumOf { it.second.length }
        val separators = system.length - systemPartsCharacters
        if (separators > 0) parts += "系统段落间隔" to "\n".repeat(separators)
        history.forEachIndexed { index, turn ->
            parts += ("历史对话 #" + (index + 1) + " / " + turn.role.wire) to turn.content
        }
        parts += "本轮完整输入 / user" to user
        val rendered = parts.filter { it.second.isNotEmpty() }.map { (label, value) ->
            TokenBreakdownItem(label, value.length, estimateTokens(value.length))
        }
        val allText = system.length.toLong() + user.length + history.sumOf { it.content.length.toLong() }
        val utf8Bytes = system.toByteArray(Charsets.UTF_8).size.toLong() +
            user.toByteArray(Charsets.UTF_8).size +
            history.sumOf { it.content.toByteArray(Charsets.UTF_8).size.toLong() }
        return Snapshot(
            breakdown = rendered,
            textCharacters = allText.coerceAtMost(Int.MAX_VALUE.toLong()).toInt(),
            textUtf8Bytes = utf8Bytes,
            imageCount = images.size,
            imageDataUrlBytes = images.sumOf { it.toByteArray(Charsets.UTF_8).size.toLong() },
            messageCount = history.size + 2, // system + earlier turns + current user
            reduced = reduced,
        )
    }

    private fun estimateTokens(chars: Int): Int = ((chars / 1.8f) + 0.5f).toInt().coerceAtLeast(0)
}
