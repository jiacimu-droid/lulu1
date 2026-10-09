package com.jiacimu.lulu.data

import java.time.Duration
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

/** Real contact timestamps, not fabricated offline life or a permanent relationship score. */
internal object CompanionContactClock {
    fun context(characterId: String, now: Instant = Instant.now()): String {
        val times = SharedExperienceTimeline.all(characterId).filter { event ->
            event.evidenceKind == EventEvidenceKind.UserStatement &&
                (event.channel == "私聊" || event.channel.contains("群聊") ||
                    event.channel.contains("电话") || event.channel.contains("见面"))
        }.map { it.occurredAt }
        return describe(times, now)
    }

    fun describe(times: List<Instant>, now: Instant): String {
        val ordered = times.filter { it <= now }.distinct().sorted()
        val format = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm").withZone(ZoneId.systemDefault())
        fun age(from: Instant, until: Instant): String {
            val minutes = Duration.between(from, until).toMinutes().coerceAtLeast(0)
            return when {
                minutes < 1 -> "不到1分钟"
                minutes < 60 -> "${minutes}分钟"
                minutes < 1440 -> "${minutes / 60}小时${minutes % 60}分钟"
                else -> "${minutes / 1440}天${minutes % 1440 / 60}小时"
            }
        }
        return buildString {
            appendLine("【真实联系时间｜手机本地时区 ${ZoneId.systemDefault()}】")
            appendLine("现在：${format.format(now)}。时间经过是事实，但没有运行记录的活动不能补造。")
            val last = ordered.lastOrNull()
            if (last == null) {
                appendLine("没有可核实的用户联系记录；不得声称用户离开了具体天数或已有共同过去。")
            } else {
                appendLine("最近一次用户联系：${format.format(last)}；距现在${age(last, now)}。")
                if (Duration.between(last, now).toMinutes() <= 30) {
                    var first = ordered.lastIndex
                    while (first > 0 && Duration.between(ordered[first - 1], ordered[first]).toMinutes() <= 30) first--
                    if (first > 0) appendLine("本轮恢复联系前，上次联系为${format.format(ordered[first - 1])}；两轮之间未联系${age(ordered[first - 1], ordered[first])}。当前新消息不会抹掉这段间隔。")
                }
                appendLine("结合人设和关系自然感受间隔：可以好奇、想念、略生疏或平静，不强制责怪用户或每条重复报时。长时间未联系时不要当作刚聊完；只有新证据才更新理解。未联系不等于知道用户没有打开应用，也不证明角色期间一直清醒或实际做过事情。")
                appendLine("这段间隔不是用户违反了起床、回话或主动联系义务的证据。如果角色曾答应按时提醒/打电话，角色的兑现责任不能因为用户之后没上线就转嫁给她。不可由时长自动推断用户在故意躲避、明知理亏或转移话题。")
            }
        }.trim()
    }
}
