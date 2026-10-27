package com.jiacimu.lulu.data

import com.jiacimu.lulu.core.MemoryEntry
import java.time.DayOfWeek
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.temporal.ChronoUnit
import kotlin.math.abs
import kotlin.math.min

/**
* 同类记录统计：只数事实，不下判断。
*
* 输入是本轮已召回的记忆与全量快照；输出至多一行事实提示，交给模型自己决定怎么说。
* 分组只按原样字段（subject + slot + measureKind）做，不做语义判断、不猜关联。
* - 同类记录有 1 次：提示“还有 1 次、那次是什么时候、数值是多少”。
* - 同类记录有 2 次及以上：提示次数、数值与最近一次。
* - 最近 14 天内出现 3 次及以上：提示为习惯（只报次数与数值，不说“总是”）。
* - 最近 6 周内同一星期几出现 3 次及以上：提示为周习惯。
* - bedtime 用圆周距离算时段相近（23:50 与 00:10 只差 20 分钟，不是 12 小时）。
* - 最新一条与同组其余条目的中位数偏离过大时，追加一句“最新这条与之前不同”。
* 没有可量同类记录时返回空字符串；每轮至多一行，不做推送，只出现在对话内。
* 旧记忆没有场景与可量字段时自然没有统计，等新对话重新提取后才有。
*/
object MemoryPatternNotes {

fun build(
recalled: List<MemoryEntry>,
snapshot: List<MemoryEntry>,
characterId: String,
now: Instant = Instant.now(),
): String {
val wanted = recalled
.filter { it.measureKind.isNotBlank()}
.map { Triple(it.subject, it.slot, it.measureKind)}
.toSet()
if (wanted.isEmpty()) return ""
val grouped = snapshot
.filter { it.characterId == characterId && it.measureKind.isNotBlank()}
.groupBy { Triple(it.subject, it.slot, it.measureKind)}
.filterKeys { it in wanted}
if (grouped.isEmpty()) return ""
val zone = ZoneId.systemDefault()
// 同类记录最多的组优先，其次取最近一次最新鲜的组。
val (key, entries) = grouped.entries.maxWithOrNull(
compareBy(
{ it.value.size},
{ it.value.maxOfOrNull { entry -> entry.occurredAt?: entry.createdAt}?: Instant.EPOCH},
),
)?: return ""
val sorted = entries.sortedByDescending { it.occurredAt?: it.createdAt}
val label = key.second.ifBlank { key.first}.ifBlank { key.third}
val latestText = relativeDay(sorted.first(), now, zone)
val in14Days = sorted.filter { daysBetween(it, now, zone) <= 14}
val (weeklyCount, weeklyDay) = weeklyBest(sorted, now, zone)
val line = when {
in14Days.size >= 3 -> "$label 在最近14天内出现${in14Days.size}次：" +
"${valuesOf(in14Days)}，最近一次是$latestText。" + deviationClause(sorted, key.third)
weeklyCount >= 3 && weeklyDay!= null -> "$label 在最近6周的每周${weekdayName(weeklyDay)}出现$weeklyCount 次：" +
"${valuesOf(weeklyEntries(sorted, weeklyDay, now, zone))}，最近一次是$latestText。" + deviationClause(sorted, key.third)
sorted.size >= 2 -> "与本次话题相同的记录还有${sorted.size}次：${valuesOf(sorted)}，最近一次是$latestText。"
else -> "与本次话题相同的记录还有1次：${valuesOf(sorted)}，那次是$latestText。"
}
return line.take(300)
}

private fun valuesOf(entries: List<MemoryEntry>): String =
entries.take(5).joinToString("、") { formatValue(it)}

private fun formatValue(entry: MemoryEntry): String {
val value = entry.measureValue?: return entry.measureUnit
return formatNumberValue(value, entry.measureKind, entry.measureUnit)
}

private fun formatNumberValue(value: Double, measureKind: String, unit: String): String {
if (measureKind == "bedtime") {
val minutes = (value * 60).toInt().mod(24 * 60)
return "%02d:%02d".format(minutes / 60, minutes % 60)
}
val text = if (value == value.toLong().toDouble()) value.toLong().toString() else "%.1f".format(value)
return text + unit
}

private fun deviationClause(sorted: List<MemoryEntry>, measureKind: String): String {
if (sorted.size < 3) return ""
val latest = sorted.first()
val latestValue = latest.measureValue?: return ""
val rest = sorted.drop(1).mapNotNull { it.measureValue}.sorted()
if (rest.isEmpty()) return ""
val median = rest[rest.size / 2]
val deviates = if (measureKind == "bedtime") {
circularHours(latestValue, median) > 1.5
} else {
abs(latestValue - median) > 1.0
}
if (!deviates) return ""
return "最新这条与之前不同（${formatValue(latest)}，之前约${formatNumberValue(median, measureKind, latest.measureUnit)}）。"
}

private fun circularHours(a: Double, b: Double): Double {
val diff = abs(a - b).mod(24.0)
return min(diff, 24.0 - diff)
}

private fun daysBetween(entry: MemoryEntry, now: Instant, zone: ZoneId): Long {
val day = LocalDate.ofInstant(entry.occurredAt?: entry.createdAt, zone)
val today = LocalDate.ofInstant(now, zone)
return ChronoUnit.DAYS.between(day, today)
}

private fun relativeDay(entry: MemoryEntry, now: Instant, zone: ZoneId): String {
val day = LocalDate.ofInstant(entry.occurredAt?: entry.createdAt, zone)
val today = LocalDate.ofInstant(now, zone)
return when (ChronoUnit.DAYS.between(day, today)) {
0L -> "今天"
1L -> "昨天"
2L -> "前天"
else -> "${day.monthValue}月${day.dayOfMonth}日"
}
}

private fun weeklyBest(sorted: List<MemoryEntry>, now: Instant, zone: ZoneId): Pair<Int, DayOfWeek?> {
val today = LocalDate.ofInstant(now, zone)
val best = sorted
.filter { ChronoUnit.DAYS.between(LocalDate.ofInstant(it.occurredAt?: it.createdAt, zone), today) in 0..41}
.groupBy { LocalDate.ofInstant(it.occurredAt?: it.createdAt, zone).dayOfWeek}
.maxByOrNull { it.value.size}
return (best?.value?.size?: 0) to best?.key
}

private fun weeklyEntries(sorted: List<MemoryEntry>, day: DayOfWeek, now: Instant, zone: ZoneId): List<MemoryEntry> {
val today = LocalDate.ofInstant(now, zone)
return sorted.filter {
val entryDay = LocalDate.ofInstant(it.occurredAt?: it.createdAt, zone)
ChronoUnit.DAYS.between(entryDay, today) in 0..41 && entryDay.dayOfWeek == day
}
}

private fun weekdayName(day: DayOfWeek): String =
listOf("周一", "周二", "周三", "周四", "周五", "周六", "周日")[day.value - 1]
}
