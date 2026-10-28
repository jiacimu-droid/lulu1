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
    private val order = listOf(
        "身份、人设与表达", "行动与工具协议", "自主心理与生活规则",
        "世界书与世界状态", "核心记忆与长期目标", "近期证据与回忆",
        "真实聊天与历史", "现实设备与感知", "本轮新消息与回执",
        "其他背景材料", "旧版未拆分材料",
    )

    private fun starts(label: String, vararg choices: String) =
        choices.any(label::startsWith)

    private fun has(label: String, vararg choices: String) =
        choices.any(label::contains)

    /** Labels are fixed, privacy-safe source titles, never user-authored content. */
    private fun category(label: String): String = when {
        starts(label, "历史对话 #", "真实聊天/", "近期聊天") -> "真实聊天与历史"
        starts(label, "本轮完整输入", "本次感知/聊天素材",
            "本轮完整用户输入", "段落分隔") -> "本轮新消息与回执"
        starts(label, "本轮任务指令", "任务规则/工具协议") -> when {
            has(label, "【真实数字世界】") -> "世界书与世界状态"
            has(label, "【生活事件不是工作报告】", "【统一持续意愿】",
                "【意愿不等于行动】", "【好奇心不是随机行动】",
                "【可选心理与持续性】", "【行动出自本人选择】",
                "【旧机制各留其长】", "【本轮自主决策】",
                "【像普通人一样表达】", "【观察如何成为你自己的生活】",
                "【亲身参与的互动", "【已沟通完成的事",
                "【主动行动承接过去", "【成长是本人想改变",
                "【不是等待指令", "【真实生活产生的双重动力】") ->
                "自主心理与生活规则"
            else -> "行动与工具协议"
        }
        starts(label, "基础身份与通用规则", "角色身份", "角色人设",
            "用户资料", "角色视频能力和通话实况", "系统段落间隔") ->
            "身份、人设与表达"
        starts(label, "全局世界书", "角色世界书", "数字世界权威状态") ->
            "世界书与世界状态"
        starts(label, "核心记忆", "未完成责任", "当前状态",
            "成长与持续动机", "跨场景连续性锚点",
            "已读材料与可修订的理解", "生活记录", "辞海",
            "往事与当前理解") -> "核心记忆与长期目标"
        starts(label, "近期原始时间线", "近期时间线",
            "召回的原始依据", "召回原始证据", "召回记忆摘要") ->
            "近期证据与回忆"
        starts(label, "本轮背景依据") -> when {
            has(label, "【紧接上一轮的双方实际言语", "【最近聊天与生活事件】",
                "【本轮仍未回应的真实新消息】",
                "【本次上线尚未处理的新动态", "【用户跨场景最新动态") ->
                "真实聊天与历史"
            has(label, "【本轮数字世界程序事件") -> "世界书与世界状态"
            has(label, "【用户现实设备", "【你自己的数字手机",
                "【此刻的真实状态】", "【手机音乐自主能力】",
                "【时间已推进】") -> "现实设备与感知"
            has(label, "【当前已承担的目标", "【本人的未来日程",
                "【自己先前真正安排", "【近期已到时刻的约定") ->
                "核心记忆与长期目标"
            has(label, "【本轮真实互动", "【角色最近自己的生活记录",
                "【上次自己决定做的生活小事",
                "【本人刚刚真实执行的联网工具结果") ->
                "本轮新消息与回执"
            else -> "其他背景材料"
        }
        starts(label, "Relay精简后系统上下文") -> "旧版未拆分材料"
        else -> "其他背景材料"
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
