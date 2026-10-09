package com.jiacimu.lulu.data

import java.time.Instant
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneId

/**
 * Accepted calendar appointments: calling and messaging have different
 * delivery actions. A date-less "8点" must be clarified, not invented.
 */
internal object TimedContactCommitmentParser {
    private val clock = Regex("""(?:([01]?\d|2[0-3]))\s*[点时](?:钟)?(?:\s*([0-5]?\d)\s*分?)?""")
    private val signals = listOf("来找我", "找我一下", "联系我", "给我发消息", "发消息给我",
        "给我打电话", "打个电话给我", "打电话给我", "电话联系我")
    private val rejected = listOf("不行", "做不到", "不能", "不会", "不答应", "无法保证", "拒绝")

    fun parse(user: String, role: String, now: Instant = Instant.now(),
        zone: ZoneId = ZoneId.systemDefault()): CommitmentTaskDraft? {
        val text = user.replace(Regex("""\s+"""), "")
        if (signals.none(text::contains) || listOf("不要", "不用", "别", "取消").any(text::contains)) return null
        val answer = role.replace(Regex("""\s+"""), "").take(850)
        if (answer.isBlank() || rejected.any(answer::contains) ||
            answer.endsWith("?") || answer.endsWith("？")) return null
        if (listOf("好", "行", "可以", "没问题", "我会", "我来", "我找你",
                "到时候", "当然", "约好了", "记住了").none(answer::contains)) return null
        val found = clock.find(text) ?: return null
        var hour = found.groupValues[1].toIntOrNull() ?: return null
        val minute = found.groupValues[2].toIntOrNull() ?: 0
        val around = text.substring(0, found.range.first).takeLast(18) +
            text.substring(found.range.last + 1).take(8)
        if (listOf("晚上", "下午", "傍晚").any(around::contains) && hour in 1..11) hour += 12
        if (around.contains("中午") && hour in 1..5) hour += 12
        if (hour !in 0..23 || minute !in 0..59) return null
        val days = when {
            text.contains("后天") -> 2L
            listOf("明天", "明早", "明日", "明晚").any(text::contains) -> 1L
            listOf("今天", "今早", "今晚").any(text::contains) -> 0L
            else -> null
        }
        val due = days?.let {
            LocalDate.ofInstant(now, zone).plusDays(it).atTime(LocalTime.of(hour, minute))
                .atZone(zone).toInstant()
        }?.takeIf { it.isAfter(now.plusSeconds(5)) }
        val phone = listOf("打电话", "打个电话", "电话联系", "来电").any(text::contains)
        val target = if (due != null) {
            val local = due.atZone(zone)
            local.monthValue.toString() + "月" + local.dayOfMonth + "日" +
                "%02d:%02d".format(hour, minute)
        } else "%02d:%02d".format(hour, minute) + "（日期待确认）"
        return CommitmentTaskDraft(
            action = "create",
            goal = target + if (phone) " 按约定给用户打电话" else " 按约定联系用户",
            dueAt = due,
            timezone = zone.id,
            completionCondition = if (phone)
                "到点发起真实来电，记录接听结果；拨号不能冒充接通"
                else "到点发出真实私聊消息，保存执行回执",
            steps = listOf(if (phone) "安排手机定时主动来电" else "安排手机定时消息"),
            deliveryAction = if (phone) "start_call" else "send_private_message",
            needsClarification = due == null,
        )
    }
}
