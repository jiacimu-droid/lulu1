package com.jiacimu.lulu.data

import java.time.Duration
import java.time.Instant
import java.time.ZoneId

/**
 * Read-only time-of-day projections of real user-facing activities in the canonical timeline.
 * These are candidates, not declarations that the user wants anything or is asleep.
 * No second storage, background polling, or model call is introduced.
 */
internal object UserRoutinePatterns {
    const val SOURCE_PREFIX = "user-activity:"

    data class Pattern(
        val activity: String,
        val label: String,
        val days: Int,
        val approximateMinute: Int,
        val latest: Instant,
        val examples: List<SharedTimelineEvent>,
    ) {
        val sourceIds: List<String> get() = examples.map { it.id }
        val timeLabel: String
            get() = "%02d:%02d".format(approximateMinute / 60, approximateMinute % 60)
    }

    fun isStart(event: SharedTimelineEvent): Boolean =
        event.evidenceKind == EventEvidenceKind.Observation &&
            event.source.startsWith(SOURCE_PREFIX) && event.source.endsWith(":start") &&
            event.source.removePrefix(SOURCE_PREFIX).removeSuffix(":start").isNotBlank() &&
            event.sessionId.isNotBlank()

    private fun minuteOfDay(event: SharedTimelineEvent, zone: ZoneId): Int {
        val local = event.occurredAt.atZone(zone)
        return local.hour * 60 + local.minute
    }

    private fun distanceMinutes(a: Int, b: Int): Int {
        val diff = kotlin.math.abs(a - b)
        return minOf(diff, 1440 - diff)
    }

    fun detect(
        events: List<SharedTimelineEvent>,
        now: Instant = Instant.now(),
        zone: ZoneId = ZoneId.systemDefault(),
    ): List<Pattern> {
        val threshold = now.minus(Duration.ofDays(45))
        return events.asSequence().filter(::isStart)
            .filter { !it.occurredAt.isBefore(threshold) && !it.occurredAt.isAfter(now) }
            .distinctBy { it.id }
            .groupBy { it.source }
            .flatMap { (source, sourceEvents) ->
                val unclaimed = sourceEvents.sortedByDescending { it.occurredAt }.toMutableList()
                val found = mutableListOf<Pattern>()
                var attempts = 0
                while (unclaimed.isNotEmpty() && found.size < 2 && attempts++ < 12) {
                    val anchor = minuteOfDay(unclaimed.first(), zone)
                    val matches = unclaimed.filter {
                        distanceMinutes(anchor, minuteOfDay(it, zone)) <= 120
                    }
                    unclaimed.removeAll(matches.toSet())
                    val independentDays = matches.distinctBy { it.occurredAt.atZone(zone).toLocalDate() }
                    if (independentDays.size < 2) continue
                    val activity = source.removePrefix(SOURCE_PREFIX).removeSuffix(":start")
                    // Circular clock average: 23:50 and 00:10 should be near midnight.
                    val centered = independentDays.map {
                        val minute = minuteOfDay(it, zone)
                        anchor + (minute - anchor + 2160) % 1440 - 720
                    }.average().toInt()
                    val middle = (centered % 1440 + 1440) % 1440
                    found += Pattern(
                        activity = activity,
                        label = when (activity) {
                            "sleep-guidance" -> "通话中开启哄睡模式"
                            "phone-call" -> "与角色接通电话"
                            "listen-together" -> "在一起听中分享音乐"
                            else -> sourceEvents.first().channel + "中的活动"
                        },
                        days = independentDays.size,
                        approximateMinute = middle,
                        latest = matches.maxOf { it.occurredAt },
                        examples = independentDays.take(8),
                    )
                }
                found
            }.sortedWith(compareByDescending<Pattern> { it.days }.thenByDescending { it.latest })
    }

    fun recent(characterId: String, now: Instant = Instant.now()): List<Pattern> =
        detect(SharedExperienceTimeline.recentUserActivities(characterId, now), now)

    /** Existing character decision decides whether to mention/act; this is only an observed cue. */
    fun nearTimeContext(characterId: String, now: Instant = Instant.now()): String {
        val minute = now.atZone(ZoneId.systemDefault()).let { it.hour * 60 + it.minute }
        val near = recent(characterId, now).filter {
            it.days >= 2 && distanceMinutes(it.approximateMinute, minute) <= 90
        }.take(2)
        if (near.isEmpty()) return ""
        return buildString {
            appendLine("【此刻附近反复出现过的真实活动｜仅是观察线索】")
            near.forEach {
                appendLine("- ${it.label}：近45天在${it.days}个不同日期、约${it.timeLabel}出现过；最后一次=${it.latest}。")
            }
            appendLine("今天是否还会发生并不知道；开启哄睡不等于确认入睡。由你按当前关系、心情与现场自主决定是否想起、提及、询问、行动或安静，不自动安排日程或联系主人。")
        }.trim()
    }

    fun reflectionContext(patterns: List<Pattern>): String {
        if (patterns.isEmpty()) return ""
        return buildString {
            appendLine("【从真实操作时间识别的候选规律，供你审慎理解】")
            patterns.take(5).forEach { pattern ->
                appendLine("- ${pattern.label}：近45天在${pattern.days}个不同自然日、约${pattern.timeLabel}发生；来源ID=${pattern.sourceIds.joinToString(",")}。")
            }
            appendLine("只可引用输入中真实存在的事件ID；至少三天相近活动才可提议 UserHabit。不能从使用哄睡模式推断主人已经睡着、每天都会如此或要求你主动打电话。")
        }.trim()
    }
}
