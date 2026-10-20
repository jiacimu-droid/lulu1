package com.jiacimu.lulu.data

import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

/** Validates an explicitly accepted relative deadline; does NOT choose how the role wakes the user. */
internal object RelativeWakeCommitmentParser {
    private val duration = Regex("""(?:(\d{1,4}|[零〇一二两三四五六七八九十百]{1,5})\s*(?:个)?分钟|半个?小时)\s*(?:后|之后|以后)""")
    private val phoneSignals = listOf("打电话", "打个电话", "电话叫", "来电", "电话联系", "给我打", "拨电话")

    fun parse(user: String, role: String, now: Instant = Instant.now(),
              zone: ZoneId = ZoneId.systemDefault()): CommitmentTaskDraft? {
        if (!WakeCommitmentParser.acceptedRequest(user, role)) return null
        val text = user.replace(Regex("""\s+"""), "")
        if (listOf("不要", "不用", "别叫", "不用叫", "取消").any(text::contains)) return null
        val match = duration.findAll(text).lastOrNull() ?: return null
        val minutes = if (match.value.startsWith("半")) 30L
            else minuteNumber(match.groupValues[1]) ?: return null
        if (minutes !in 1L..1440L) return null
        val due = now.plusSeconds(minutes * 60L)
        val target = due.atZone(zone).format(DateTimeFormatter.ofPattern("M月d日 HH:mm"))
        val phone = phoneSignals.any(text::contains)
        return CommitmentTaskDraft(
            action = "create",
            goal = "$target 按约定叫醒用户",
            dueAt = due,
            timezone = zone.id,
            completionCondition = "准时触发有声叫醒或来电；用户明确确认已醒或取消后再完成",
            steps = listOf("提前登记真实系统闹钟", "到点实际履行叫醒约定"),
            deliveryAction = if (phone) "start_call" else "",
        )
    }

    /**
     * A later "20分钟后再叫我" adjusts the currently pending alarm, preserving
     * its phone delivery route. Do not silently guess which of several
     * separate promises the user wanted to replace.
     */
    fun draftsFor(raw: CommitmentTaskDraft, activeTasks: List<CommitmentTask>,
                  userText: String): List<CommitmentTaskDraft> {
        val matching = activeTasks.filter { task ->
            task.isWakeResponsibility() ||
                (userText.contains("再叫我") && task.deliveryAction == "start_call" &&
                    task.goal.contains("按约定"))
        }
        if (matching.size != 1) return listOf(raw)
        val previous = matching.single()
        val wake = raw.copy(deliveryAction =
            if (raw.deliveryAction == "start_call" || previous.deliveryAction == "start_call")
                "start_call" else "send_private_message")
        if (previous.dueAt == wake.dueAt && previous.deliveryAction == wake.deliveryAction)
            return emptyList()
        return listOf(wake.copy(action = "reschedule", targetTaskId = previous.id))
    }

    private fun minuteNumber(raw: String): Long? {
        raw.toLongOrNull()?.let { return it }
        val digits = mapOf('零' to 0, '〇' to 0, '一' to 1, '二' to 2,
            '两' to 2, '三' to 3, '四' to 4, '五' to 5, '六' to 6,
            '七' to 7, '八' to 8, '九' to 9)
        var total = 0
        var pending = 0
        for (c in raw) {
            when (c) {
                '十' -> { total += (if (pending == 0) 1 else pending) * 10; pending = 0 }
                '百' -> { total += (if (pending == 0) 1 else pending) * 100; pending = 0 }
                else -> pending = digits[c] ?: return null
            }
        }
        return (total + pending).toLong()
    }
}
