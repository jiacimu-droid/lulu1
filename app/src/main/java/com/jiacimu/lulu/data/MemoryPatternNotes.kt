package com.jiacimu.lulu.data

import com.jiacimu.lulu.core.MemoryEntry
import java.time.DayOfWeek
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.temporal.ChronoUnit
import java.time.temporal.WeekFields
import kotlin.math.abs
import kotlin.math.min

/**
 * Read-only evidence counts, not a second habit classifier.
 * One extracted memory is not necessarily one real-world occurrence.
 * The model decides whether the verified counts mean anything for this person.
 */
object MemoryPatternNotes {
    private data class GroupKey(
        val subject: String,
        val slot: String,
        val kind: String,
        val unit: String,
    )

    fun build(
        recalled: List<MemoryEntry>,
        snapshot: List<MemoryEntry>,
        characterId: String,
        now: Instant = Instant.now(),
    ): String {
        val wanted = recalled.filter { it.measureKind.isNotBlank() }
            .map(::keyOf).toSet()
        if (wanted.isEmpty()) return ""

        val candidates = snapshot.asSequence()
            .filter { it.characterId == characterId && it.measureKind.isNotBlank() }
            .filter { keyOf(it) in wanted }
            .filter { (it.occurredAt ?: it.createdAt) <= now }
            .filter { it.measureValue?.isFinite() == true }
            .filter { MemoryValidityStore.isActive(it.id) }
            .toList()
        // Resolve all source evidence in one indexed query. Calling
        // memoryHasLiveSources() for every historical memory would cause
        // one SQLite read per record when a role accumulates years of data.
        val live = SharedExperienceTimeline.eventsByIds(
            characterId, candidates.flatMap(MemoryEntry::sourceIds).distinct(),
        ).associateBy(SharedTimelineEvent::id)
        val grouped = candidates.asSequence()
            .filter { entry ->
                entry.sourceIds().all { id ->
                    live[id]?.let { event ->
                        entry.sourceRevisions[id]?.let { revision -> revision == event.revision } ?: true
                    } == true
                }
            }
            .groupBy(::keyOf)
            .mapValues { (_, group) -> dedupeSameEvidence(group) }
            .filterValues { it.isNotEmpty() }
        if (grouped.isEmpty()) return ""

        val zone = ZoneId.systemDefault()
        val (key, entries) = grouped.entries.maxWithOrNull(
            compareBy<Map.Entry<GroupKey, List<MemoryEntry>>> { it.value.size }
                .thenBy { it.value.maxOfOrNull(::eventTime) ?: Instant.EPOCH },
        ) ?: return ""
        val sorted = entries.sortedByDescending(::eventTime)
        val label = key.slot.ifBlank { key.subject }.ifBlank { key.kind }
        val latestText = relativeDay(sorted.first(), now, zone)
        val in14Days = sorted.filter { daysBetween(it, now, zone) in 0..13 }
        val distinctDays = in14Days.map { LocalDate.ofInstant(eventTime(it), zone) }.distinct().size
        val (weeklyCount, weeklyDay) = weeklyBest(sorted, now, zone)

        val result = when {
            in14Days.size >= 3 && distinctDays >= 3 ->
                "$label 最近14天在${distinctDays}天记录${in14Days.size}次：" +
                    "${valuesOf(in14Days)}，最近一次是$latestText。" +
                    deviationClause(sorted, key.kind, key.unit)
            in14Days.size >= 3 ->
                "$label 最近14天记录${in14Days.size}次（共${distinctDays}天）：" +
                    "${valuesOf(in14Days)}，最近一次是$latestText。"
            weeklyCount >= 3 && weeklyDay != null -> {
                val weekly = weeklyEntries(sorted, weeklyDay, now, zone)
                "$label 最近6周在${weeklyCount}个不同周的${weekdayName(weeklyDay)}有记录" +
                    "（共${weekly.size}次）：${valuesOf(weekly)}，最近一次是$latestText。" +
                    deviationClause(sorted, key.kind, key.unit)
            }
            sorted.size >= 2 ->
                "与本次话题相同的记录共有${sorted.size}次：${valuesOf(sorted)}，最近一次是$latestText。"
            else ->
                "与本次话题相同的记录共有1次：${valuesOf(sorted)}，那次是$latestText。"
        }
        return result.take(300)
    }

    private fun keyOf(entry: MemoryEntry): GroupKey = GroupKey(
        entry.subject, entry.slot, entry.measureKind,
        // Even the same 'kind' cannot compare hours with minutes or km with m.
        // Keep original units rather than guessing conversions of user statements.
        if (entry.measureKind == "bedtime") "clock24" else entry.measureUnit.trim(),
    )

    private fun eventTime(entry: MemoryEntry): Instant = entry.occurredAt ?: entry.createdAt

    /** Multiple extractions of the same single source event count only once.
     * Multi-source summaries do not assert a single occurrence: preserve each
     * memory ID instead of collapsing unrelated real events into one. */
    internal fun dedupeSameEvidence(entries: List<MemoryEntry>): List<MemoryEntry> =
        entries.sortedByDescending(::eventTime).distinctBy { entry ->
            val sourceId = entry.sourceIds().singleOrNull()
            if (sourceId != null) "event:$sourceId" else "memory:${entry.id}"
        }

    private fun valuesOf(entries: List<MemoryEntry>): String =
        entries.take(5).joinToString("、", transform = ::formatValue)

    private fun formatValue(entry: MemoryEntry): String {
        val value = entry.measureValue ?: return entry.measureUnit
        return formatNumberValue(value, entry.measureKind, entry.measureUnit)
    }

    private fun formatNumberValue(value: Double, measureKind: String, unit: String): String {
        if (measureKind == "bedtime") {
            val minutes = (value * 60).toInt().mod(24 * 60)
            return "%02d:%02d".format(minutes / 60, minutes % 60)
        }
        val text = if (value == value.toLong().toDouble()) value.toLong().toString()
            else "%.1f".format(value)
        return text + unit
    }

    private fun deviationClause(sorted: List<MemoryEntry>, kind: String, unit: String): String {
        if (sorted.size < 3) return ""
        val latest = sorted.first()
        val latestValue = latest.measureValue ?: return ""
        val rest = sorted.drop(1).mapNotNull { it.measureValue }.sorted()
        if (rest.isEmpty()) return ""
        val median = rest[rest.size / 2]
        // A universal 1.0-unit threshold mixes unrelated quantities (e.g.
        // kilometres, temperature, kilograms). Only known units have a valid
        // meaningful scale; leave all other interpretation to the character.
        val deviates = when {
            kind == "bedtime" -> circularHours(latestValue, median) > 1.5
            kind == "sleep_hours" && unit in setOf("小时", "h", "hour", "hours") ->
                abs(latestValue - median) > 1.0
            else -> false
        }
        if (!deviates) return ""
        return "最新这条与之前不同（${formatValue(latest)}，之前约" +
            "${formatNumberValue(median, kind, latest.measureUnit)}）。"
    }

    private fun circularHours(a: Double, b: Double): Double {
        val diff = abs(a - b).mod(24.0)
        return min(diff, 24.0 - diff)
    }

    private fun daysBetween(entry: MemoryEntry, now: Instant, zone: ZoneId): Long =
        ChronoUnit.DAYS.between(LocalDate.ofInstant(eventTime(entry), zone), LocalDate.ofInstant(now, zone))

    private fun relativeDay(entry: MemoryEntry, now: Instant, zone: ZoneId): String {
        val day = LocalDate.ofInstant(eventTime(entry), zone)
        return when (ChronoUnit.DAYS.between(day, LocalDate.ofInstant(now, zone))) {
            0L -> "今天"
            1L -> "昨天"
            2L -> "前天"
            else -> "${day.monthValue}月${day.dayOfMonth}日"
        }
    }

    private fun weeklyBest(
        sorted: List<MemoryEntry>,
        now: Instant,
        zone: ZoneId,
    ): Pair<Int, DayOfWeek?> {
        val inSixWeeks = sorted.filter { daysBetween(it, now, zone) in 0..41 }
        val best = inSixWeeks.groupBy { LocalDate.ofInstant(eventTime(it), zone).dayOfWeek }
            .mapValues { (_, entries) -> entries.map { weekKey(it, zone) }.toSet().size }
            .maxByOrNull { it.value }
        return (best?.value ?: 0) to best?.key
    }

    private fun weekKey(entry: MemoryEntry, zone: ZoneId): Pair<Int, Int> {
        val date = LocalDate.ofInstant(eventTime(entry), zone)
        val iso = WeekFields.ISO
        return date.get(iso.weekBasedYear()) to date.get(iso.weekOfWeekBasedYear())
    }

    private fun weeklyEntries(
        sorted: List<MemoryEntry>,
        day: DayOfWeek,
        now: Instant,
        zone: ZoneId,
    ): List<MemoryEntry> = sorted.filter {
        daysBetween(it, now, zone) in 0..41 &&
            LocalDate.ofInstant(eventTime(it), zone).dayOfWeek == day
    }

    private fun weekdayName(day: DayOfWeek): String =
        listOf("周一", "周二", "周三", "周四", "周五", "周六", "周日")[day.value - 1]
}
