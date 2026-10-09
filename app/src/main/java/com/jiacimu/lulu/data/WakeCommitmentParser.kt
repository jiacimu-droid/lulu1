package com.jiacimu.lulu.data

import java.time.Instant
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneId

/**
 * Extract the one case where we must not rely on an LLM remembering to create a future task:
 * the user gives an explicit local wake-up time and the role accepts the request.
 * This creates a *real* local AlarmManager job, not just a diary or private thought.
 */
internal object WakeCommitmentParser {
    private val clock = Regex("""(?:([01]?\d|2[0-3]))\s*[点时](?:钟)?(?:\s*([0-5]?\d)\s*分?)?""")
    private val wakeSignals = listOf("叫我", "喊我", "叫醒我", "喊醒我", "叫我起床", "叫醒", "喊醒", "叫起床", "提醒我起床")
    private val rejected = listOf("不行", "做不到", "不能帮", "没办法", "不会叫", "没法叫", "不负责", "不答应", "拒绝", "无法保证", "不能保证", "不敢保证", "我不叫", "别指望", "不想叫")
    private val accepted = listOf("好", "行", "可以", "没问题", "知道了", "记住了", "交给我", "我来", "我会", "我叫", "我喊", "会叫", "提醒你", "叫醒你", "我负责", "到时候", "设置闹钟", "设个闹钟", "安排好了", "准时")

    fun acceptedRequest(user: String, role: String): Boolean {
        if (!wakeSignals.any(user::contains) || role.isBlank()) return false
        val condensed = role.replace(Regex("""\s+"""), "").take(850)
        if (rejected.any(condensed::contains)) return false
        return accepted.any(condensed::contains)
    }

    fun parse(
        user: String,
        role: String,
        now: Instant = Instant.now(),
        zone: ZoneId = ZoneId.systemDefault(),
    ): CommitmentTaskDraft? {
        if (!acceptedRequest(user, role)) return null
        val compact = user.replace(Regex("""\s+"""), "")
        val found = clock.find(compact) ?: return null
        var hour = found.groupValues[1].toIntOrNull() ?: return null
        val minute = found.groupValues[2].toIntOrNull() ?: 0
        val before = compact.substring(0, found.range.first)
        val after = compact.substring(found.range.last + 1)
        val timingContext = (before.takeLast(18) + after.take(7))
        if (listOf("下午", "晚上", "傍晚").any(timingContext::contains) && hour in 1..11) hour += 12
        if (timingContext.contains("中午") && hour in 1..5) hour += 12
        if (hour !in 0..23 || minute !in 0..59) return null
        val dayShift = when {
            compact.contains("后天") -> 2L
            compact.contains("明天") || compact.contains("明早") || compact.contains("明日") || compact.contains("明晚") -> 1L
            compact.contains("今天") || compact.contains("今早") || compact.contains("今晚") || compact.contains("今天早") -> 0L
            else -> return null // No date is not permission to guess tonight or tomorrow.
        }
        val dueAt = LocalDate.ofInstant(now, zone).plusDays(dayShift)
            .atTime(LocalTime.of(hour, minute)).atZone(zone).toInstant()
        if (!dueAt.isAfter(now.plusSeconds(5))) return null
        val wantsPhone = listOf("打电话叫", "打电话喊", "电话叫醒", "来电叫醒", "电话叫我", "给我打电话", "打电话提醒").any(compact::contains)
        val readable = dueAt.atZone(zone)
        val target = "${readable.monthValue}月${readable.dayOfMonth}日${String.format("%02d:%02d", hour, minute)}"
        return CommitmentTaskDraft(
            action = "create",
            goal = "$target 按约定叫醒用户",
            dueAt = dueAt,
            timezone = zone.id,
            completionCondition = "到点实际触发手机叫醒提醒；用户明确说醒了或取消后再结束",
            steps = listOf("提前安排真实的手机闹钟", "到点触发有声通知", "按角色约定发出叫醒消息" +
                if (wantsPhone) "与真实主动来电" else ""),
            deliveryAction = if (wantsPhone) "start_call" else "send_private_message",
        )
    }
}
