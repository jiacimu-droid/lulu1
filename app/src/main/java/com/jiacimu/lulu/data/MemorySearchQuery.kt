package com.jiacimu.lulu.data

import java.time.Instant
import java.time.LocalDate
import java.time.YearMonth
import java.time.ZoneId

internal fun memorySearchTerms(text: String): Set<String> {
    val normalized = text.lowercase().replace(Regex("[^\\p{L}\\p{N}]+"), " ")
    return buildSet {
        normalized.split(Regex("\\s+")).filter { it.length >= 2 }.forEach { token ->
            add(token)
            if (token.any { it.code > 127 }) {
                token.windowed(2).forEach(::add)
                token.windowed(3).forEach(::add)
            }
        }
    } - setOf("当前", "输入", "用户", "角色", "场景", "上下", "下文", "近期", "记忆", "记得", "之前", "上个", "个月")
}

internal fun memoryTimeRange(query: String, now: Instant = Instant.now(), zone: ZoneId = ZoneId.systemDefault()): Pair<Instant, Instant>? {
    val today = now.atZone(zone).toLocalDate()
    val month = YearMonth.from(today)
    val dates: Pair<LocalDate, LocalDate> = when {
        "上个月" in query -> month.minusMonths(1).let { it.atDay(1) to it.plusMonths(1).atDay(1) }
        "这个月" in query || "本月" in query -> month.atDay(1) to month.plusMonths(1).atDay(1)
        "昨天" in query || "昨晚" in query -> today.minusDays(1) to today
        "前天" in query -> today.minusDays(2) to today.minusDays(1)
        "今天" in query -> today to today.plusDays(1)
        else -> return null
    }
    return dates.first.atStartOfDay(zone).toInstant() to dates.second.atStartOfDay(zone).toInstant()
}

/** Searches evidence directly so a failed extractor cannot hide an older relevant event. */
internal object RawTimelineMemoryRecall {
    fun find(characterId: String, query: String, limit: Int = 16): List<SharedTimelineEvent> {
        val focused = RelevantMemoryRecall.focusQuery(query)
        val terms = memorySearchTerms(focused)
        val range = memoryTimeRange(focused)
        if (terms.isEmpty() && range == null) return emptyList()
        val events = SharedExperienceTimeline.all(characterId).filter {
            com.jiacimu.lulu.LuluRepositories.memory.allowsRawRecall(it) &&
                (range == null || (it.occurredAt >= range.first && it.occurredAt < range.second))
        }
        return events.map { event ->
            val overlap = terms.intersect(memorySearchTerms(event.content)).size
            event to overlap
        }.filter { (_, overlap) -> overlap >= 2 || (range != null && (terms.isEmpty() || overlap > 0)) }
            .sortedWith(compareByDescending<Pair<SharedTimelineEvent, Int>> { it.second }.thenByDescending { it.first.occurredAt })
            .take(limit.coerceIn(1, 64)).map { it.first }.sortedBy(SharedTimelineEvent::occurredAt)
    }
}
