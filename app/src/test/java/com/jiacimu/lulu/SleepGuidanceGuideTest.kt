package com.jiacimu.lulu

import org.junit.Assert.*
import org.junit.Test

class SleepGuidanceGuideTest {
    @Test fun naturalBedtimeIncludesMultiplePossibleWaysIntoSleep() {
        val prompt = SleepGuidanceGuide.instruction(SleepGuidanceFocus.Natural, false, 0L)
        assertTrue(prompt.contains("身体扫描"))
        assertTrue(prompt.contains("放下思绪"))
        assertTrue(prompt.contains("想象画面"))
        assertTrue(prompt.contains("被夸"))
        assertTrue(prompt.contains("不要求马上睡着"))
        assertTrue(prompt.contains("不是统一的催眠口播"))
    }

    @Test fun focusChangesPriorityWithoutIgnoringListenerOrInventingReplies() {
        for (focus in SleepGuidanceFocus.values()) {
            val prompt = SleepGuidanceGuide.instruction(focus, true, 21 * 60_000L)
            assertTrue(prompt.contains(focus.label))
            assertTrue(prompt.contains(focus.emphasis))
            assertTrue(prompt.contains("以她的新要求为先"))
            assertTrue(prompt.contains("不凭空模拟用户回答"))
            assertTrue(prompt.contains("更少、更温柔"))
        }
    }

    @Test fun breathingAndBodyGuidanceDoNotImposeUncomfortableExercise() {
        val breath = SleepGuidanceGuide.instruction(SleepGuidanceFocus.Breath, false, 0L)
        val body = SleepGuidanceGuide.instruction(SleepGuidanceFocus.Body, false, 0L)
        assertTrue(breath.contains("不强制深呼吸"))
        assertTrue(breath.contains("不强迫屏息"))
        assertTrue(body.contains("脚趾"))
        assertTrue(body.contains("不必一口气念完全部部位"))
    }
}
