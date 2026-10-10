package com.jiacimu.lulu

import com.jiacimu.lulu.data.CompanionPresenceState
import com.jiacimu.lulu.data.PerceptionWakePlan
import com.jiacimu.lulu.data.ProactivePerceptionPolicy
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

/** The time of a saved thought and the time of a perception attempt are different facts. */
internal object PerceptionStatusPresentation {
    fun lines(state: CompanionPresenceState?, policy: ProactivePerceptionPolicy,
        plan: PerceptionWakePlan?, online: Boolean, inCall: Boolean,
        now: Instant, zone: ZoneId = ZoneId.systemDefault()): List<String> = buildList {
        fun time(at: Instant) = at.atZone(zone).format(DateTimeFormatter.ofPattern("MM-dd HH:mm"))
        state?.lastPerceptionAt?.let {
            val note = state.lastPerceptionNote
            val outcome = when {
                note.contains("失败") || note.contains("暂停") || note.contains("未完成") || note.contains("不完整") -> note.take(110)
                note.contains("启动") -> "已启动 · 尚无完成记录"
                note.contains("silent", ignoreCase = true) -> "已感知 · 选择安静"
                else -> "已感知"
            }
            add("最近感知 · ${time(it)} · $outcome")
        } ?: add("尚无感知记录")
        when {
            inCall -> add("通话中 · 持续在线陪伴")
            online -> add("在线中 · 持续感知与思考")
            !policy.enabled -> add("自动唤醒已关闭")
            plan == null -> add("下次唤醒尚未排定")
            else -> {
                add("下次预计醒来 · ${time(plan.dueAt)}" + if (plan.dueAt <= now) " · 已到时间，等待执行" else "")
                add("本轮间隔 · ${plan.intervalMinutes} 分钟" + if (policy.adaptiveFrequency) "（自适应）" else "")
            }
        }
    }
}
