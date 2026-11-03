package com.jiacimu.lulu.data

import org.junit.Assert.*
import org.junit.Test

/** A phone signal may support curiosity, never turn into an invented user sleep fact. */
class UserDevicePerceptionTest {
    @Test fun realRecentInteractionIsTimestampedButNeverCalledSleepOrContinuousAttention() {
        val now = 10_000_000L
        val cue = UserDevicePerception.activityEvidenceCue(
            now, now - 90_000L, now - 4 * 60_000L, true)
        assertTrue(cue.contains("约1 分钟前有应用交互事件"))
        assertTrue(cue.contains("约4 分钟前发生应用前台切换"))
        assertTrue(cue.contains("不等于持续使用"))
        assertTrue(cue.contains("没有事件不代表主人睡了"))
        assertFalse(cue.contains("主人已经睡着"))
    }

    @Test fun missingAndStaleAndroidEventsNeverClaimUserIsSleepingOrIdle() {
        val now = 10_000_000L
        for (cue in listOf(
            UserDevicePerception.activityEvidenceCue(now, 0L, 0L, false),
            UserDevicePerception.activityEvidenceCue(
                now, now - 3 * 60 * 60_000L, now - 3 * 60 * 60_000L, true),
        )) {
            assertTrue(cue.contains("没有可用的应用交互/切换事件记录"))
            assertTrue(cue.contains("没有事件不代表主人睡了"))
            assertFalse(cue.contains("主人正在休息"))
        }
    }

    @Test fun scheduledOutcomeReviewPrioritizesRealPhoneEvidenceWithoutFabricatingSleep() {
        val interaction = UserDevicePerception.activityEvidenceCue(
            10_000_000L, 10_000_000L - 40_000L, 10_000_000L - 2 * 60_000L, true)
        assertTrue(interaction.contains("确有应用交互活动"))
        assertFalse(interaction.contains("主人已经入睡"))
        val report = UserDevicePerception.commitmentReviewEvidenceCue(
            "2026-11-04T01:30:00+08:00", true, interaction,
            "正在显示露露机（近期真实应用来源）",
            "露露机确有触摸事件；距今1分钟",
            "未连接健康 App",
        )
        assertTrue(report.contains("当前屏幕=设备报告可交互/亮屏"))
        assertTrue(report.contains("真实使用事件="))
        assertTrue(report.contains("约0 分钟前有应用交互事件"))
        assertTrue(report.contains("露露机确有触摸事件；距今1分钟"))
        assertTrue(report.contains("最近前台应用="))
        assertTrue(report.contains("健康/穿戴来源="))
        assertTrue(report.contains("正向线索"))
        assertTrue(report.contains("已睡") || report.contains("入睡"))
        assertFalse(report.contains("主人已经睡着"))
        assertTrue("Review should not discard prioritized usage events behind a long preamble",
            report.indexOf("真实使用事件=") < report.indexOf("健康/穿戴来源="))
        assertTrue("Review callback must be able to forward the whole bounded evidence",
            report.length < 2_500)
        val screenOff = UserDevicePerception.commitmentReviewEvidenceCue(
            "2026-11-04T01:30:00+08:00", false,
            "近30分钟没有可用的应用交互/切换事件记录",
            "未授权", "无可核实的露露机触摸", "未连接健康 App")
        assertTrue(screenOff.contains("不能证明已经睡着"))
        assertFalse(screenOff.contains("用户已入睡"))
    }

    @Test fun reviewEvidenceCapsLengthyHealthTextWithoutCuttingLatestAndroidEvents() {
        val report = UserDevicePerception.commitmentReviewEvidenceCue(
            "2026-11-04T01:30:00+08:00", true,
            "Android 记录刚刚有应用交互事件",
            "真实前台应用", "真实触摸", "健康说明".repeat(600))
        assertTrue(report.contains("Android 记录刚刚有应用交互事件"))
        assertTrue(report.contains("真实触摸"))
        assertTrue(report.contains("健康说明"))
        assertTrue(report.length < 2_500)
    }

    @Test fun batteryDecreaseShowsOnlyChargeTrendNotAwakeOrAsleep() {
        val now = 10_000_000L
        val cue = UserDevicePerception.batteryDeltaCue(
            57, false, now, 62, false, now - 12 * 60_000L)
        assertTrue(cue.contains("12 分钟"))
        assertTrue(cue.contains("减少了5 个百分点"))
        assertTrue(cue.contains("不能推断主人是否睡着"))
        assertEquals("", UserDevicePerception.batteryDeltaCue(
            56, false, now, 57, false, 0L))
        assertEquals("", UserDevicePerception.batteryDeltaCue(
            56, false, now, 57, false, now + 10_000L))
    }
}
