package com.jiacimu.lulu.data

import com.jiacimu.lulu.core.MemoryEntry

/** Bounded *view* of ranked recalled memories. Originals and provenance remain untouched.
 * A long highest-ranked record must not silently evict every smaller relevant memory.
 */
internal object PromptMemoryRenderer {
    fun render(memories: List<MemoryEntry>, characterBudget: Int): String {
        if (memories.isEmpty() || characterBudget < 140) return ""
        val header = "相关经历摘要（仅供联想；时间、原话与结果以权威记录为准）："
        val lines = mutableListOf(header)
        var remaining = characterBudget - header.length - 1
        val perEntry = if (characterBudget >= 6_000) 1_500 else 850
        for (memory in memories) {
            val at = memory.occurredAt ?: memory.createdAt
            val prefix = "- [$at] "
            val space = (remaining - prefix.length - 1).coerceAtLeast(0)
            if (space < 65) break
            val clean = memory.content.trim().replace(Regex("\\s+"), " ")
            val allowance = minOf(space, perEntry)
            val shown = if (clean.length <= allowance) clean else clean.take(allowance - 1) + "…"
            lines += prefix + shown
            remaining -= prefix.length + shown.length + 1
        }
        return if (lines.size <= 1) "" else lines.joinToString("\n")
    }
}
