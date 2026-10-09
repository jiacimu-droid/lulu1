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
        assertTrue(prompt.contains("不是必须独占 60 分钟的单一模式"))
        assertTrue(prompt.contains("不要定时轮换"))
    }

    @Test fun focusChangesPriorityWithoutIgnoringListenerOrInventingReplies() {
        for (focus in SleepGuidanceFocus.values()) {
            val prompt = SleepGuidanceGuide.instruction(focus, true, 21 * 60_000L)
            assertTrue(prompt.contains(focus.label))
            assertTrue(prompt.contains(focus.emphasis))
            assertTrue(prompt.contains("以她的新要求为先"))
            assertTrue(prompt.contains("不凭空模拟用户回答"))
            assertTrue(prompt.contains("降低语言密度"))
            assertTrue(prompt.contains("60 分钟只是连续无人回应后的通话结束上限"))
        }
    }

    @Test fun eachPreferenceAllowsNaturalCombinationRatherThanAnHourOfOneExercise() {
        val body = SleepGuidanceGuide.instruction(SleepGuidanceFocus.Body, true, 15 * 60_000L)
        val affection = SleepGuidanceGuide.instruction(SleepGuidanceFocus.Affection, true, 2 * 60_000L)
        val story = SleepGuidanceGuide.instruction(SleepGuidanceFocus.Story, true, 8 * 60_000L)
        assertTrue(body.contains("不从头反复报身体部位"))
        assertTrue(body.contains("安静陪伴"))
        assertTrue(affection.contains("不要一小时不停夸"))
        assertTrue(story.contains("不要突然暂停剧情强行插入整套练习"))
        assertTrue(story.contains("故事要记得已讲过的人物与情节"))
    }

    @Test fun laterSilenceReducesMentalEffortInsteadOfStartingNewActivities() {
        val early = SleepGuidanceGuide.instruction(SleepGuidanceFocus.Natural, false, 0L)
        val middle = SleepGuidanceGuide.instruction(SleepGuidanceFocus.Natural, true, 25 * 60_000L)
        val late = SleepGuidanceGuide.instruction(SleepGuidanceFocus.Natural, true, 45 * 60_000L)
        assertTrue(early.contains("慢慢铺陈一个舒服的睡前主题"))
        assertTrue(middle.contains("减少追问和新话题"))
        assertTrue(late.contains("不再开新冒险"))
        assertTrue(late.contains("大段安静"))
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
