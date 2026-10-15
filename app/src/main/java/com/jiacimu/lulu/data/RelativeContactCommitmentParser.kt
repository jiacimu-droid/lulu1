package com.jiacimu.lulu.data

import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

/**
 * User-confirmed relative reminders/messages/calls get a real deadline even
 * when the language model omits or corrupts its JSON scheduling plan.
 */
internal object RelativeContactCommitmentParser {
    private val duration = Regex("""(?:(\d{1,4}|[零〇一二两三四五六七八九十百]{1,5})(?:个)?分钟|半个?小时)\s*(?:后|之后|以后)""")
    private val refusals = listOf("不行", "不能", "不会", "不答应", "做不到", "拒绝", "无法保证")
    private val agreements = listOf("好", "可以", "我会", "我来", "没问题", "当然",
        "记住", "收到", "到时候", "行", "答应", "包在我身上")
    private val messageSignals = listOf("发消息", "给我发", "发信息", "给我发信息", "联系我", "找我")
    private val callSignals = listOf("打电话", "电话联系", "给我打", "来电", "拨电话")

    fun parse(user: String, role: String, now: Instant = Instant.now(),
              zone: ZoneId = ZoneId.systemDefault()): CommitmentTaskDraft? {
        val text = user.replace(Regex("""\s+"""), "")
        val answer = role.replace(Regex("""\s+"""), "").take(850)
        if (answer.isBlank() || refusals.any(answer::contains) ||
            answer.endsWith("？") || answer.endsWith("?")) return null
        if (agreements.none(answer::contains)) return null
        if (listOf("不要", "不用", "别给", "别发", "别打", "取消").any(text::contains)) return null
        val isCall = callSignals.any(text::contains)
        val isMessage = messageSignals.any(text::contains)
        if (!isCall && !isMessage) return null
        val match = duration.findAll(text).lastOrNull() ?: return null
        val minutes = if (match.value.startsWith("半")) 30L
            else minuteNumber(match.groupValues[1]) ?: return null
        if (minutes !in 1..1440) return null
        val due = now.plusSeconds(minutes * 60)
        val time = due.atZone(zone).format(DateTimeFormatter.ofPattern("M月d日 HH:mm"))
        val action = if (isCall) "start_call" else "send_private_message"
        return CommitmentTaskDraft(
            action = "create",
            goal = "$time " + if (isCall) "按约定给用户打电话" else "按约定给用户发消息",
            dueAt = due,
            timezone = zone.id,
            completionCondition = if (isCall) "到点真实发起电话，保留接听结果"
                else "到点真实发送一条私聊消息，记录发送结果",
            steps = listOf(if (isCall) "安排真实定时来电" else "安排真实定时私聊"),
            deliveryAction = action,
        )
    }

    fun draftsFor(raw: CommitmentTaskDraft, activeTasks: List<CommitmentTask>,
                  userText: String): List<CommitmentTaskDraft> {
        // Only unambiguous explicit reschedules may replace a previous task.
        val change = listOf("改成", "改到", "改为", "换成", "再", "推迟", "延后").any(userText::contains)
        if (!change) return listOf(raw)
        val previous = activeTasks.filter {
            it.deliveryAction == raw.deliveryAction &&
                (it.goal.contains("按约定给用户发消息") ||
                    it.goal.contains("按约定联系用户") ||
                    it.goal.contains("按约定给用户打电话"))
        }.singleOrNull() ?: return listOf(raw)
        if (previous.dueAt == raw.dueAt) return emptyList()
        return listOf(raw.copy(action = "reschedule", targetTaskId = previous.id))
    }

    private fun minuteNumber(raw: String): Long? {
        raw.toLongOrNull()?.let { return it }
        val digits = mapOf('零' to 0, '〇' to 0, '一' to 1, '二' to 2, '两' to 2,
            '三' to 3, '四' to 4, '五' to 5, '六' to 6, '七' to 7,
            '八' to 8, '九' to 9)
        var total = 0
        var pending = 0
        for (ch in raw) {
            when (ch) {
                '十' -> { total += (if (pending == 0) 1 else pending) * 10; pending = 0 }
                '百' -> { total += (if (pending == 0) 1 else pending) * 100; pending = 0 }
                else -> pending = digits[ch] ?: return null
            }
        }
        return (total + pending).toLong()
    }
}
