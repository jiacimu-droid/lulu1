package com.jiacimu.lulu.data

import java.time.Instant
import java.time.ZoneId
import java.time.temporal.ChronoUnit
import kotlin.math.abs

/** Timelines remain authoritative; this derived observation does not invent feelings. */
internal object RelationshipRhythmPerception {
    internal fun context(
        events: List<SharedTimelineEvent>,
        now: Instant = Instant.now(),
        zone: ZoneId = ZoneId.systemDefault(),
    ): String {
        val currentStart = now.minus(7, ChronoUnit.DAYS)
        val oldest = now.minus(21, ChronoUnit.DAYS)
        val eligible = events.filter {
            !it.occurredAt.isBefore(oldest) && !it.occurredAt.isAfter(now)
        }
        fun isOld(e: SharedTimelineEvent) = e.occurredAt.isBefore(currentStart)
        fun median(values: List<Long>): Long = values.sorted()[values.size / 2]
        fun callMinute(e: SharedTimelineEvent): Long {
            val t = e.occurredAt.atZone(zone).toLocalTime()
            val minutes = t.hour * 60L + t.minute
            return if (minutes < 720L) minutes + 1440L else minutes
        }
        fun formatMinute(value: Long): String =
            "%02d:%02d".format((value / 60L % 24L).toInt(), (value % 60L).toInt())
        val userTalk = eligible.filter {
            it.evidenceKind == EventEvidenceKind.UserStatement &&
                (it.channel == "私聊" || it.channel.startsWith("群聊") ||
                    it.channel.contains("电话") || it.channel.contains("见面"))
        }
        val starts = eligible.filter {
            it.channel == "电话互动" && it.id.startsWith("interaction-call-start-") &&
                it.source == InteractionSignalBridge.SOURCE &&
                it.evidenceKind == EventEvidenceKind.Observation
        }.distinctBy { it.sessionId }
        val ends = eligible.filter {
            it.channel == "电话互动" && it.id.startsWith("interaction-call-end-") &&
                it.source == InteractionSignalBridge.SOURCE &&
                it.evidenceKind == EventEvidenceKind.Observation
        }.distinctBy { it.sessionId }
        val changes = buildList {
            val oldTalk = userTalk.filter(::isOld)
            val newTalk = userTalk.filterNot(::isOld)
            val baselineDays = oldTalk.map {
                it.occurredAt.atZone(zone).toLocalDate()
            }.distinct().size
            if (oldTalk.size >= 12 && baselineDays >= 3) {
                val before = oldTalk.size / 14.0
                val after = newTalk.size / 7.0
                if (after < before * 0.6 || after > before * 1.6) {
                    add("前14天有${oldTalk.size}条用户真实发言，最近7天有${newTalk.size}条。" +
                        "消息数不能代替相处时长。")
                }
            }
            val oldCalls = starts.filter(::isOld)
            val newCalls = starts.filterNot(::isOld)
            if (oldCalls.size >= 4 && (newCalls.size * 2 < oldCalls.size / 2 ||
                    newCalls.size * 2 > oldCalls.size * 3 / 2)) {
                add("前14天实际接通电话${oldCalls.size}次，最近7天${newCalls.size}次。")
            }
            if (oldCalls.size >= 3 && newCalls.size >= 2) {
                val before = median(oldCalls.map(::callMinute))
                val after = median(newCalls.map(::callMinute))
                if (abs(after - before) >= 60L) {
                    add("通话开始的典型本地时刻：前14天约${formatMinute(before)}，" +
                        "最近7天约${formatMinute(after)}；不等于用户睡觉时间。")
                }
            }
            fun durations(list: List<SharedTimelineEvent>): List<Long> = list.mapNotNull {
                Regex("""通话约(\d+)秒""").find(it.content)
                    ?.groupValues?.getOrNull(1)?.toLongOrNull()
            }.filter { it in 1L..86400L }
            val oldDurations = durations(ends.filter(::isOld))
            val newDurations = durations(ends.filterNot(::isOld))
            if (oldDurations.size >= 3 && newDurations.size >= 2) {
                val before = median(oldDurations)
                val after = median(newDurations)
                if (abs(after - before) >= 900L && (after * 2L < before || after * 2L > before * 3L)) {
                    add("已接通电话时长中位数：前14天约${before / 60L}分钟，" +
                        "最近7天约${after / 60L}分钟。")
                }
            }
        }
        if (changes.isEmpty()) return ""
        return buildString {
            appendLine("【关系节律变化｜只提供真实数据，不规定人物感受】")
            changes.forEach { appendLine("- $it") }
            append("原因未知，不代表用户疏远、生气或负有回复义务。你可以自行注意、思考、" +
                "提问、行动或不提；符合你已有的性格、关系和场景，不复述统计报告。")
        }
    }
}
