package com.jiacimu.lulu.data

import java.time.Instant
import java.time.LocalDate
import java.time.YearMonth
import java.time.ZoneId

internal fun memorySearchTerms(text: String): Set<String> {
    val normalized = text.lowercase()
        .replace(Regex("(?m)^(当前输入|当前场景|近期上下文|拟采取行动|任务意图|用户本轮|本轮用户)[：:]"), "")
        .replace(Regex("[^\\p{L}\\p{N}]+"), " ")
    return buildSet {
        normalized.split(Regex("\\s+")).filter { it.length >= 2 }.forEach { token ->
            add(token)
            if (token.any { it.code > 127 }) {
                token.windowed(2).forEach(::add)
                token.windowed(3).forEach(::add)
            }
        }
    } - setOf("当前", "输入", "用户", "角色", "场景", "上下", "下文", "近期", "记忆", "记得", "之前", "上个", "个月", "什么", "这个", "那个", "我们", "事情", "时候", "知道", "可以", "需要", "一下", "今天", "昨天", "以前", "现在", "你说", "的话")
}

internal fun memoryTemporalQuery(query: String): String = query.lineSequence().lastOrNull {
    it.startsWith("当前输入：") || it.startsWith("用户本轮：") || it.startsWith("本轮用户：")
}?.substringAfter('：') ?: query

internal fun memoryTimeRange(query: String, now: Instant = Instant.now(), zone: ZoneId = ZoneId.systemDefault()): Pair<Instant, Instant>? {
    val focused = memoryTemporalQuery(query)
    val today = now.atZone(zone).toLocalDate()
    val weekStart = today.minusDays((today.dayOfWeek.value - 1).toLong())
    val month = YearMonth.from(today)
    val dates: Pair<LocalDate, LocalDate> = when {
        "上个月" in focused -> month.minusMonths(1).let { it.atDay(1) to it.plusMonths(1).atDay(1) }
        "这个月" in focused || "本月" in focused -> month.atDay(1) to month.plusMonths(1).atDay(1)
        "昨天" in focused || "昨晚" in focused -> today.minusDays(1) to today
        "前天" in focused -> today.minusDays(2) to today.minusDays(1)
        "今天" in focused -> today to today.plusDays(1)
        "上周" in focused || "上星期" in focused -> weekStart.minusDays(7) to weekStart
        "这周" in focused || "本周" in focused -> weekStart to weekStart.plusDays(7)
        else -> {
            val daysAgo = Regex("(\\d{1,3})天前").find(focused)?.groupValues?.get(1)?.toLongOrNull()
            val exactDate = Regex("\\d{4}-\\d{2}-\\d{2}").find(focused)?.value
                ?.let { runCatching { LocalDate.parse(it) }.getOrNull() }
            val day = if (daysAgo != null) today.minusDays(daysAgo) else exactDate ?: return null
            day to day.plusDays(1)
        }
    }
    return dates.first.atStartOfDay(zone).toInstant() to dates.second.atStartOfDay(zone).toInstant()
}

/** Searches evidence directly so a failed extractor cannot hide an older relevant event. */
internal object RawTimelineMemoryRecall {
    fun find(characterId: String, query: String, limit: Int = 16): List<SharedTimelineEvent> {
        val focused = RelevantMemoryRecall.focusQuery(query)
        val range = memoryTimeRange(focused)
        val overview = Regex("(?:做了什么|聊了什么|聊过什么|发生了什么|有什么事)").containsMatchIn(memoryTemporalQuery(focused))
        val terms = if (range != null && overview) emptySet() else memorySearchTerms(focused)
        if (terms.isEmpty() && range == null) return emptyList()
        val events = SharedExperienceTimeline.all(characterId).filter {
            com.jiacimu.lulu.LuluRepositories.memory.allowsRawRecall(it) &&
                (range == null || (it.occurredAt >= range.first && it.occurredAt < range.second))
        }
        return events.map { event ->
            val overlap = terms.intersect(memorySearchTerms(event.content)).size
            event to overlap
        }.filter { (_, overlap) -> terms.isNotEmpty() && overlap >= minOf(2, terms.size) ||
                (range != null && (terms.isEmpty() || overlap > 0)) }
            .sortedWith(compareByDescending<Pair<SharedTimelineEvent, Int>> { it.second }.thenByDescending { it.first.occurredAt })
            .take(limit.coerceIn(1, 64)).map { it.first }
    }
}
