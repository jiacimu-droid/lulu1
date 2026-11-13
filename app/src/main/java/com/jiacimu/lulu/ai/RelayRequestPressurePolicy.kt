package com.jiacimu.lulu.ai

import com.jiacimu.lulu.data.MemoryWorkingSet

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

    /**
     * The background wake can carry a 90-second decision, long structured
     * action protocol and an image in the same request. Two measured requests
     * around 360 KB reached the read timeout while the old 440 KB gate was
     * never entered. A further real interrupted body write contained about
     * 263 KB, just below the former 280 KB preflight gate. A 2026-11-14
     * proactive request also failed upstream with HTTP 500 at 196,618
     * preflight bytes (199,237 JSON bytes) despite an available 142,688-byte
     * projection. The relay did not explicitly attribute that 500 to size;
     * proactively send the already available, lower-pressure projection for
     * this background source instead of assuming a generic 500 is retry-safe.
     * Preserve the same
     * protected persona/relationships/tool rules and current user input while
     * projecting older, optional context BEFORE a large background POST.
     *
     * SavesEnough is checked separately by the caller. Ordinary short turns
     * are left unchanged, as are other model entry points.
     */
    fun shouldCompactBeforeSend(source: String, payloadBytes: Int): Boolean =
        canRecover(source) && payloadBytes > 100_000

    /**
     * These aren't local-memory quotas. A relay failure may justify omitting
     * older secondary context from ONE outgoing request, but not corrupting
     * role identity, established relationships, commitments, or the tool rules.
     */
    private val protected = setOf(
        "权威现实时钟", "任务规则/工具协议", "本轮任务指令", "本轮头像观察说明",
        "本轮表达证据", "角色身份", "角色人设", "当前人物关系",
        "用户资料",
        "核心记忆", "未完成责任", "全局世界书",
        "角色世界书/设计书", "数字世界权威状态",
        "全局世界书/TOP", "角色世界书/TOP",
        "全局世界书/NORMAL", "角色世界书/NORMAL",
        "全局世界书/BOTTOM", "角色世界书/BOTTOM",
    )

    private fun limit(label: String): Int = when (label) {
        "本轮背景依据" -> 14_000
        "跨场景连续性锚点", "成长与持续动机", "当前状态" -> 4_000
        "近期原始时间线（新原话优先；旧理解不得覆盖本轮纠正）：" -> 6_000
        else -> 3_000
    }

    private const val OMITTED =
        "【本轮 Relay 请求压力保护：其他较早材料暂未提供，原始记录仍在手机】"

    /** Select complete source lines, never cut an utterance or a negation mid-line. */
    private fun excerptWholeLines(text: String, allowed: Int, mostRecentFirst: Boolean): String {
        if (text.length <= allowed) return text
        val lines = text.lines()
        // The first line often carries the authority/source label. Retain it
        // when selecting from the tail, but leave the original line intact.
        // A short first line is not necessarily a section heading: evidence
        // projections often start immediately with "- eventId=..." records.
        // Pinning that oldest record while keeping a newest-first suffix
        // silently changes the evidence window and breaks its chronology.
        val header = lines.firstOrNull().orEmpty()
            .takeIf { mostRecentFirst && it.length <= 160 &&
                !it.trimStart().startsWith("- ") }.orEmpty()
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
     * The proactive facts blob contains many explicitly titled independent
     * sources. Taking only the last N lines drops live device and unfinished
     * responsibilities before it reaches the recent context. Instead reserve
     * space for known authoritative sources, then use the remaining budget
     * for the latest independent sections. This is a transport projection:
     * the original records are never altered.
     */
    private val priorityBackgroundTitles = setOf(
        "【用户现实设备与用户状态感知层】",
        "【你自己的数字手机 · 不是用户的Android设备】",
        "【在线窗口到期｜现场状态】",
        "【休眠前尚无后续安排的本人责任｜权威任务ID】",
        "【当前已承担的目标与统一验收入口】",
        "【本轮真实互动的同次理解与抉择】",
        "【自己先前真正安排、现在待处理的日程】",
        "【本轮数字世界程序事件｜不可改写】",
        "【本次上线尚未处理的新动态｜旧→新】",
        "【本轮仍未回应的真实新消息】",
        "【当前实际同场】",
    )

    private fun excerptBackgroundBySource(text: String, allowed: Int): String {
        if (text.length <= allowed) return text
        // Only the application's titled sections form boundaries. A plain
        // user utterance is never split at a keyword or summarized by regex.
        val starts = Regex("(?m)(?=^【[^\\n】]{2,120}】)")
            .findAll(text).map { it.range.first }.filter { it > 0 }.toList()
        if (starts.isEmpty()) return excerptWholeLines(text, allowed, mostRecentFirst = true)
        val boundaries = (listOf(0) + starts + listOf(text.length)).distinct()
        val blocks = boundaries.zipWithNext().map { (a, b) -> text.substring(a, b).trimEnd() }
        val selected = mutableMapOf<Int, String>()
        var remaining = (allowed - OMITTED.length * 2 - 6).coerceAtLeast(0)
        // Preserve the opening scope, provenance and any brief non-titled
        // device summary. Current user text has its own intact wire message.
        if (blocks.firstOrNull()?.startsWith("【") == false && remaining > 300) {
            val first = excerptWholeLines(blocks.first(), minOf(1_000, remaining), false)
            selected[0] = first
            remaining -= first.length + 1
        }
        val priorities = blocks.indices.filter { index ->
            priorityBackgroundTitles.any(blocks[index]::startsWith)
        }
        val other = blocks.indices.filterNot { it in priorities || it in selected }
        // Do not silently lose upcoming responsibilities merely because later
        // social/history sections are numerous. Keep order in the final wire.
        for (index in priorities.asReversed() + other.asReversed()) {
            if (remaining < 240) break
            val maxForBlock = minOf(remaining, if (index in priorities) 3_500 else 2_000)
            val candidate = excerptWholeLines(blocks[index], maxForBlock, mostRecentFirst = true)
            if (candidate.isNotBlank()) {
                selected[index] = candidate
                remaining -= candidate.length + 1
            }
        }
        if (selected.isEmpty()) return excerptWholeLines(text, allowed, true)
        return buildString {
            var previous = -1
            selected.keys.sorted().forEach { index ->
                if (index > previous + 1) appendLine(OMITTED)
                append(selected.getValue(index).trimEnd())
                append('\n')
                previous = index
            }
            if (previous < blocks.lastIndex) appendLine(OMITTED)
        }.trimEnd()
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

    /** Keep section provenance alongside the exact reduced text. Diagnostics
     * must never collapse a trimmed request into an anonymous system blob.
     * Static contracts, core memories and ongoing responsibilities stay intact.
     */
    fun compactSectionPairs(sections: List<Pair<String, String>>): List<Pair<String, String>> =
        sections.mapNotNull { (label, content) ->
            if (content.isBlank()) return@mapNotNull null
            val selected = if (label in protected) content
                else if (label == "本轮背景依据")
                    excerptBackgroundBySource(content, limit(label))
                else excerptWholeLines(content, limit(label),
                    // Direction follows the *actual* section identity. The
                    // summary title says "以原始依据为准" as a caution, but is not
                    // raw evidence; substring matching silently evicted its
                    // highest-priority entries from the final relay request.
                    mostRecentFirst = label == "本轮背景依据" ||
                        label == MemoryWorkingSet.RECENT ||
                        label == MemoryWorkingSet.EVIDENCE)
            selected.takeIf(String::isNotBlank)?.let { label to it }
        }

    fun compactSections(sections: List<Pair<String, String>>): String =
        compactSectionPairs(sections).joinToString("\n\n") { it.second }

    fun savesEnough(originalBytes: Int, compactBytes: Int): Boolean =
        compactBytes < originalBytes - 4_096
}
