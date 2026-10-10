package com.jiacimu.lulu

import com.jiacimu.lulu.data.CompanionPresenceState
import com.jiacimu.lulu.data.PerceptionWakePlan
import com.jiacimu.lulu.data.ProactivePerceptionPolicy
import org.junit.Assert.*
import org.junit.Test
import java.time.Instant
import java.time.ZoneId

class PerceptionStatusPresentationTest {
    private val now = Instant.parse("2026-10-10T08:00:00Z")
    private val zone = ZoneId.of("Asia/Shanghai")
    private val policy = ProactivePerceptionPolicy(quietHoursEnabled = false)
    private val plan = PerceptionWakePlan("role", now, now.plusSeconds(3600), 60, "hour")
    @Test fun oldThoughtDoesNotHideLaterPerceptionOrActualAdaptiveDeadline() {
        val state = CompanionPresenceState("role", innerThought = "之前的心声", updatedAt = now.minusSeconds(7200),
            lastPerceptionAt = now, lastPerceptionNote = "最近已更新")
        val lines = PerceptionStatusPresentation.lines(state, policy, plan, false, false, now, zone)
        assertTrue(lines.any { it.contains("最近感知 · 10-10 16:00") })
        assertTrue(lines.any { it.contains("下次预计醒来 · 10-10 17:00") })
        assertTrue(lines.any { it.contains("60 分钟（自适应）") })
    }
    @Test fun failedAttemptAndOverdueWakeAreVisibleRatherThanShownAsSuccess() {
        val state = CompanionPresenceState("role", lastPerceptionAt = now,
            lastPerceptionNote = "感知失败 · 网络连接中断")
        val lines = PerceptionStatusPresentation.lines(state, policy, plan.copy(dueAt = now), false, false, now, zone)
        assertTrue(lines.any { it.contains("感知失败 · 网络连接中断") })
        assertTrue(lines.any { it.contains("已到时间，等待执行") })
        assertFalse(lines.any { it.contains("已感知") })
    }
    @Test fun callRemainsOnlineWithoutATemporaryWakeWindowAndStartedWorkIsNotClaimedFinished() {
        val state = CompanionPresenceState("role", lastPerceptionAt = now.minusSeconds(7200),
            lastPerceptionNote = "感知启动 · 角色时间间隔")
        val lines = PerceptionStatusPresentation.lines(state, policy, plan, false, true, now, zone)
        assertTrue(lines.any { it.contains("通话中 · 持续在线陪伴") })
        assertTrue(lines.any { it.contains("尚无完成记录") })
        assertFalse(lines.any { it.contains("下次预计醒来") || it.contains("已感知") })
    }
}
