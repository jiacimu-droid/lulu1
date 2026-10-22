package com.jiacimu.lulu.data

import kotlin.math.roundToLong

/** Presentation-only categories: preserve the complete individual source audit. */
internal data class TokenSourceGroup(
    val title: String,
    val items: List<TokenBreakdownItem>,
) {
    val chars: Long get() = items.sumOf { it.chars.toLong() }
    val estimatedTokens: Long get() = items.sumOf { it.estimatedTokens.toLong() }
}

internal data class TokenCalibratedEstimate(
    val tokens: Long,
    val sampleCount: Int,
    val sameSource: Boolean,
)

/** Explicit grouping, never modifies persisted/token-accounted components. */
internal object TokenConsolePresentation {
    private val order = listOf("人物与固定规则", "长期记忆与状态", "近期历史与召回", "本轮感知与消息", "其他／合并上下文")

    private fun category(label: String): String = when {
        listOf("角色身份", "角色人设", "用户资料", "基础身份与通用规则",
            "任务规则/工具协议", "本轮任务指令", "角色视频能力和通话实况",
            "全局世界书", "角色世界书/设计书", "系统段落间隔").any { label.startsWith(it) } ->
            "人物与固定规则"

        listOf("核心记忆", "未完成责任", "当前状态", "成长与持续动机",
            "跨场景连续性锚点", "数字世界权威状态", "生活记录",
            "已读材料与可修订的理解", "辞海", "往事与当前理解").any { label.startsWith(it) } ->
            "长期记忆与状态"

        listOf("本轮背景依据", "近期原始时间线", "近期时间线",
            "召回的原始依据", "召回原始证据", "召回记忆摘要", "真实聊天/",
            "历史对话 #", "近期聊天", "召回的原始依据").any { label.startsWith(it) } ->
            "近期历史与召回"

        listOf("本轮完整输入", "本次感知/聊天素材", "本轮完整用户输入",
            "段落分隔").any { label.startsWith(it) } -> "本轮感知与消息"

        else -> "其他／合并上下文"
    }

    fun groups(breakdown: List<TokenBreakdownItem>): List<TokenSourceGroup> {
        val buckets = breakdown.groupBy { category(it.label) }
        return order.mapNotNull { title ->
            buckets[title]?.takeIf { it.isNotEmpty() }
                ?.let { TokenSourceGroup(title, it.sortedByDescending { part -> part.estimatedTokens }) }
        }
    }

    /**
     * Reconcile a provider's one real total with the CLIENT-SIDE text inventory.
     * This is an explicitly proportional ALLOCATION, not provider-reported
     * per-section tokens. We cannot recover true section counts from one total.
     * Return null whenever media, old records or incomplete coverage prevent
     * a defensible all-text distribution.
     */
    fun allocateReportedInput(record: TokenConsoleRecord, groups: List<TokenSourceGroup>):
        List<Long>? {
        if (record.auditVersion < 1 || record.reportedInputTokens <= 0L ||
            record.imageCount != 0 || record.textCharacters <= 0 ||
            record.coveredTextCharacters != record.textCharacters.toLong() ||
            groups.isEmpty() || groups.sumOf { it.chars } != record.coveredTextCharacters
        ) return null
        val total = record.reportedInputTokens
        var allotted = 0L
        return groups.mapIndexed { index, group ->
            if (index == groups.lastIndex) total - allotted
            else (total.toDouble() * group.chars / record.coveredTextCharacters)
                .toLong().coerceIn(0L, total - allotted).also { allotted += it }
        }
    }

    /** Learns a robust provider/model character-to-token ratio from prior complete
     * text-only requests. This is a non-billable estimate, not a tokenizer.
     * Cross-model or media-containing records must never contaminate training.
     */
    fun calibratedEstimate(target: TokenConsoleRecord, all: List<TokenConsoleRecord>): TokenCalibratedEstimate? {
        if (target.auditVersion < 1 || target.imageCount != 0 ||
            target.textCharacters <= 0 || target.coveredTextCharacters != target.textCharacters.toLong() ||
            target.model.isBlank() || target.providerHost.isBlank()) return null
        val eligible = all.asSequence().filter { it.id != target.id && it.createdAtMillis < target.createdAtMillis &&
            it.auditVersion > 0 && it.imageCount == 0 && it.reportedInputTokens > 0L &&
            it.textCharacters > 0 && it.coveredTextCharacters == it.textCharacters.toLong() &&
            it.model == target.model && it.providerHost == target.providerHost
        }.sortedByDescending { it.createdAtMillis }.take(16).toList()
        val sameSource = eligible.filter { it.requestSource == target.requestSource }
        val pool = (if (sameSource.size >= 3) sameSource else eligible).take(9)
        if (pool.size < 3) return null
        val rates = pool.map { it.reportedInputTokens.toDouble() / it.textCharacters }.sorted()
        val median = rates[rates.size / 2]
        return TokenCalibratedEstimate(
            tokens = (target.textCharacters * median).roundToLong(),
            sampleCount = pool.size, sameSource = sameSource.size >= 3,
        )
    }
}
