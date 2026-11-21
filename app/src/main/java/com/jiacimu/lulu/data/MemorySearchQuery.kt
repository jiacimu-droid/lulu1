package com.jiacimu.lulu.data

import com.jiacimu.lulu.LuluRepositories
import com.jiacimu.lulu.core.MemoryEntry
import com.jiacimu.lulu.core.MemoryKind
import com.jiacimu.lulu.core.MemoryPerspective
import java.time.Duration
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

private val calendarDate = Regex("""(?:(\d{4})年)?(\d{1,2})月(\d{1,2})[日号]""")
private val isoDate = Regex("""\d{4}-\d{2}-\d{2}""")
private val daysAgo = Regex("""(\d{1,3})天前""")
private val clockTime = Regex("""(?<!\d)(\d{1,2})(?:[:：](\d{2})|(?:点|时)(?:(\d{1,2})分?|半)?)(?!\d)""")
private val morning = Regex("早上|上午|清晨|今早|昨早")
private val evening = Regex("晚上|夜里|昨晚|昨夜|今晚|夜间|夜晚")

internal fun memoryTimeRange(
    query: String, now: Instant = Instant.now(), zone: ZoneId = ZoneId.systemDefault(),
): Pair<Instant, Instant>? {
    val focused = memoryTemporalQuery(query)
    val today = now.atZone(zone).toLocalDate()
    val yesterday = today.minusDays(1)
    val weekStart = today.minusDays((today.dayOfWeek.value - 1).toLong())
    val month = YearMonth.from(today)
    val exact = isoDate.find(focused)?.value?.let { runCatching { LocalDate.parse(it) }.getOrNull() }
        ?: calendarDate.find(focused)?.destructured?.let { (year, monthNumber, day) ->
            val date = runCatching {
                LocalDate.of(year.toIntOrNull() ?: today.year, monthNumber.toInt(), day.toInt())
            }.getOrNull()
            if (date != null && year.isBlank() && date > today) date.minusYears(1) else date
        }
    val day = exact ?: when {
        "前天" in focused -> yesterday.minusDays(1)
        listOf("昨天", "昨晚", "昨夜", "昨早").any(focused::contains) -> yesterday
        listOf("今天", "今早", "今晚").any(focused::contains) -> today
        else -> daysAgo.find(focused)?.groupValues?.get(1)?.toLongOrNull()?.let(today::minusDays)
    }
    if (day != null) {
        val clock = clockTime.find(focused)
        val hour = clock?.groupValues?.get(1)?.toIntOrNull()
        val minute = clock?.let {
            it.groupValues[2].toIntOrNull() ?: it.groupValues[3].toIntOrNull()
                ?: if (it.value.endsWith("半")) 30 else 0
        } ?: 0
        val afternoon = "凌晨" !in focused && ("下午" in focused || evening.containsMatchIn(focused))
        val normalizedHour = hour?.let { if (afternoon && it in 1..11) it + 12 else it }
        if (normalizedHour != null && normalizedHour in 0..23 && minute in 0..59) {
            val clockDay = if ("凌晨" in focused && evening.containsMatchIn(focused)) day.plusDays(1) else day
            val start = clockDay.atTime(normalizedHour, minute).atZone(zone)
            val end = if (clock!!.groupValues.drop(2).any(String::isNotBlank) || clock.value.endsWith("半"))
                start.plusMinutes(1) else start.plusHours(1)
            return start.toInstant() to end.toInstant()
        }
        val (start, end) = when {
            evening.containsMatchIn(focused) -> day.atTime(18, 0) to day.plusDays(1).atTime(6, 0)
            "凌晨" in focused -> day.atStartOfDay() to day.atTime(6, 0)
            morning.containsMatchIn(focused) -> day.atTime(6, 0) to day.atTime(12, 0)
            "下午" in focused -> day.atTime(12, 0) to day.atTime(18, 0)
            else -> day.atStartOfDay() to day.plusDays(1).atStartOfDay()
        }
        return start.atZone(zone).toInstant() to end.atZone(zone).toInstant()
    }
    val (start, end) = when {
        "上个月" in focused -> month.minusMonths(1).atDay(1) to month.atDay(1)
        "这个月" in focused || "本月" in focused -> month.atDay(1) to month.plusMonths(1).atDay(1)
        "上周" in focused || "上星期" in focused -> weekStart.minusDays(7) to weekStart
        "这周" in focused || "本周" in focused -> weekStart to weekStart.plusDays(7)
        else -> return null
    }
    return start.atStartOfDay(zone).toInstant() to end.atStartOfDay(zone).toInstant()
}

internal fun Pair<Instant, Instant>.includes(moment: Instant): Boolean =
    moment >= first && moment < second

/** Literal and synonymous search hints. The role interprets the returned words in context. */
internal class MemoryAffectCue private constructor(
    private val synonyms: Set<String>,
    private val speaker: Speaker,
) {
    private enum class Speaker { Owner, Character, Any }

    private fun matches(text: String): Boolean = synonyms.any(text::contains)

    fun matches(memory: MemoryEntry): Boolean =
        memory.kind == MemoryKind.Emotion && matches(memory.content) && when (speaker) {
            Speaker.Any -> true
            Speaker.Character -> memory.perspective == MemoryPerspective.Subjective ||
                SharedExperienceTimeline.eventsByIds(memory.characterId, memory.sourceIds())
                    .any { matches(it) }
            Speaker.Owner -> memory.perspective == MemoryPerspective.Record &&
                (memory.sourceIds().isEmpty() || SharedExperienceTimeline.eventsByIds(memory.characterId, memory.sourceIds())
                    .any { matches(it) })
        }

    fun matches(event: SharedTimelineEvent): Boolean {
        if (!matches(event.content)) return false
        if (speaker != Speaker.Character && event.isUserMemoryStatement() &&
            (speaker == Speaker.Owner || "我" in event.content)) return true
        if (speaker == Speaker.Owner || !event.isOwnSubjectiveMemorySource()) return false
        if (event.source == "presence") {
            return matches(event.content.substringAfter("心情：", event.content).substringBefore('；')) ||
                matches(event.content.substringAfter("心声：", ""))
        }
        return selfExpression.findAll(event.content).any { matches(it.value) }
    }

    companion object {
        private val vocabulary = listOf(
            setOf("生气", "愤怒", "恼火", "气恼", "火大", "发火", "窝火", "气坏", "气得", "来气", "不爽"),
            setOf("难过", "伤心", "悲伤", "失落", "沮丧", "委屈", "心酸"),
            setOf("开心", "高兴", "快乐", "喜悦", "欢喜", "兴奋", "雀跃"),
            setOf("担心", "焦虑", "紧张", "忐忑", "不安", "害怕", "恐惧"),
            setOf("吃醋", "嫉妒"),
            setOf("惊讶", "震惊", "吃惊", "诧异"),
        )

        private val selfExpression = Regex("我[^，。！？；]{0,20}")
        private val historicalCue = Regex("之前|以前|过去|上次|有一次|那一次|那次|那天|当时|曾经|刚才|刚刚|记得")

        fun from(query: String, dated: Boolean = false): MemoryAffectCue? {
            val literal = memoryTemporalQuery(query)
            if (!dated && !historicalCue.containsMatchIn(literal)) return null
            val match = vocabulary.firstOrNull { words -> words.any(literal::contains) } ?: return null
            val cueAt = match.minOf { literal.indexOf(it).takeIf { index -> index >= 0 } ?: Int.MAX_VALUE }
            val prefix = literal.substring(0, cueAt).takeLast(18)
            val you = prefix.lastIndexOf('你')
            val me = prefix.lastIndexOf('我')
            val speaker = when {
                you > me -> Speaker.Character
                me > you -> Speaker.Owner
                else -> Speaker.Any
            }
            return MemoryAffectCue(match, speaker)
        }
    }
}

/** Searches evidence directly so a failed extractor cannot hide an older relevant event. */
internal object RawTimelineMemoryRecall {
    private val timeQuestion = Regex("""^([\p{L}\p{N}]{2,16}?)(?:是)?(?:什么时候|什么时间|几点)[吗呢呀？?]*$""")
    private val overview = Regex("做了?什么|聊了?什么|聊过什么|发生了?什么|有什么事|干了?什么|记得吗|还记得")

    fun liveEvents(characterId: String, excluded: Set<String> = emptySet()): List<SharedTimelineEvent> {
        val live = SharedExperienceTimeline.withoutRecalledGroupSpeech(SharedExperienceTimeline.all(characterId)).filter {
            it.id !in excluded && DigitalLifeProfileStore.allowsTimestamp(characterId, it.occurredAt) &&
                LuluRepositories.memory.allowsRawRecall(it)
        }
        val liveIds = live.mapTo(mutableSetOf(), SharedTimelineEvent::id)
        return live.filter { event -> event.presenceParentEventId()?.let(liveIds::contains) != false }
    }

    fun find(characterId: String, query: String, limit: Int = 16): List<SharedTimelineEvent> =
        find(query, liveEvents(characterId), limit)

    fun find(
        query: String, live: List<SharedTimelineEvent>, limit: Int = 16,
        now: Instant = Instant.now(), zone: ZoneId = ZoneId.systemDefault(),
    ): List<SharedTimelineEvent> {
        if (limit <= 0 || query.isBlank()) return emptyList()
        val currentInput = memoryTemporalQuery(RelevantMemoryRecall.focusQuery(query)).trim()
        val range = memoryTimeRange(currentInput, now, zone)
        val affect = MemoryAffectCue.from(currentInput, range != null)
        val terms = if (range != null && overview.containsMatchIn(currentInput) && affect == null)
            emptySet() else memorySearchTerms(currentInput)
        val timeTopic = timeQuestion.matchEntire(currentInput)?.groupValues?.get(1)
        if (terms.isEmpty() && range == null) return emptyList()
        val eligible = live.asSequence().filter { event ->
            !event.occurredAt.isAfter(now) &&
                event.source != "online-awareness" && event.source != "background-awareness"
        }
        val dateOverview = range != null && overview.containsMatchIn(currentInput) && affect == null
        fun ranked(window: Pair<Instant, Instant>?, allForOverview: Boolean = false) =
            eligible.filter { window == null || window.includes(it.occurredAt) }
            .mapNotNull { event ->
                val exactTopic = timeTopic != null && event.content.contains(timeTopic, ignoreCase = true)
                val overlap = terms.intersect(memorySearchTerms(event.content)).size
                val emotion = affect?.matches(event) == true
                val relevant = emotion || exactTopic ||
                    (terms.isNotEmpty() && overlap >= minOf(2, terms.size)) ||
                    (window != null && (terms.isEmpty() || overlap > 0))
                if (!relevant) null else event to (overlap + if (emotion) 20 else 0)
            }.sortedWith(compareByDescending<Pair<SharedTimelineEvent, Int>> { it.second }
                .thenByDescending { it.first.occurredAt })
            .take(if (allForOverview) Int.MAX_VALUE else limit.coerceAtMost(64))
            .map { it.first }.toList()
        val hits = ranked(range, allForOverview = dateOverview)
        if (dateOverview && range != null) {
            // "What happened yesterday?" must cover the whole requested
            // period, not only the last 16 evening messages. Choose evidence
            // from separate time slices and keep original chronological order.
            if (hits.size <= limit) return hits.sortedBy(SharedTimelineEvent::occurredAt)
            val buckets = minOf(limit, 6).coerceAtLeast(1)
            val minutes = Duration.between(range.first, range.second).toMinutes().coerceAtLeast(1)
            val bySlice = hits.groupBy { event ->
                val offset = Duration.between(range.first, event.occurredAt)
                    .toMinutes().coerceIn(0, minutes - 1)
                ((offset * buckets) / minutes).toInt().coerceIn(0, buckets - 1)
            }
            val anchors = bySlice.toSortedMap().values.map { slice ->
                slice.firstOrNull { it.evidenceKind == EventEvidenceKind.UserStatement }
                    ?: slice.firstOrNull { it.evidenceKind == EventEvidenceKind.ToolResult }
                    ?: slice.first()
            }
            return (anchors + hits).distinctBy(SharedTimelineEvent::id)
                .take(limit).sortedBy(SharedTimelineEvent::occurredAt)
        }
        // Historical clocks are often approximate. Try the immediate
        // neighborhood only when a minute-specific search finds nothing.
        return if (hits.isEmpty() && range != null && Duration.between(range.first, range.second).toMinutes() == 1L)
            ranked(range.first.minusSeconds(900) to range.second.plusSeconds(900)) else hits
    }
}
