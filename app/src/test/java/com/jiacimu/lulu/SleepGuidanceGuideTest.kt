package com.jiacimu.lulu

import org.junit.Assert.*
import org.junit.Test

class SleepGuidanceGuideTest {
    @Test fun onlyTwoPublicBedtimeModesRemainAvailable() {
        assertEquals(listOf(SleepGuidanceFocus.Body, SleepGuidanceFocus.Story), SleepGuidanceGuide.choices)
    }

    @Test fun bodyModeFollowsActualProgressWithoutRepeatedFarewells() {
        val prompt = SleepGuidanceGuide.instruction(SleepGuidanceFocus.Body, true, 25 * 60_000L)
        assertTrue(prompt.contains("【持续哄睡：放松身体】"))
        assertTrue(prompt.contains("继续上段真实讲过的位置"))
        assertTrue(prompt.contains("不转去讲故事"))
        assertTrue(prompt.contains("不要每段末尾"))
        assertTrue(prompt.contains("不强求闭眼"))
        assertTrue(prompt.contains("更长停顿"))
        assertTrue(prompt.contains("用户不回应不代表已经睡着"))
    }

    @Test fun storyModePreservesRealNarrativeAndDoesNotInventSleep() {
        val prompt = SleepGuidanceGuide.instruction(SleepGuidanceFocus.Story, true, 21 * 60_000L)
        assertTrue(prompt.contains("【持续哄睡：睡前故事】"))
        assertTrue(prompt.contains("已经真实播完的人物、地点、情节"))
        assertTrue(prompt.contains("不冒充真实共同回忆"))
        assertTrue(prompt.contains("突然插入一套身体扫描"))
        assertTrue(prompt.contains("不因此中断"))
        assertTrue(prompt.contains("虚构她已睡着"))
    }

    @Test fun oldSavedFocusesFallBackToBodyWithoutExposingOldModes() {
        val baseline = SleepGuidanceGuide.instruction(SleepGuidanceFocus.Body, false, 0L)
        for (legacy in listOf(
            SleepGuidanceFocus.Natural, SleepGuidanceFocus.Breath,
            SleepGuidanceFocus.Thoughts, SleepGuidanceFocus.Imagery,
            SleepGuidanceFocus.Affection
        )) {
            assertEquals(baseline, SleepGuidanceGuide.instruction(legacy, false, 0L))
        }
    }

    @Test fun longSilenceChangesDensityButNotChosenActivity() {
        for (focus in SleepGuidanceGuide.choices) {
            val early = SleepGuidanceGuide.instruction(focus, true, 0L)
            val late = SleepGuidanceGuide.instruction(focus, true, 25 * 60_000L)
            assertFalse(early.contains("已经很安静"))
            assertTrue(late.contains("已经很安静"))
            assertTrue(late.contains("留出更长停顿"))
            assertTrue(late.contains("30分钟上限"))
            assertTrue(late.contains("不应靠收尾套话"))
        }
    }
}
