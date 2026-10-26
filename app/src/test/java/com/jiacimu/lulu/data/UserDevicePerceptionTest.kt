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
