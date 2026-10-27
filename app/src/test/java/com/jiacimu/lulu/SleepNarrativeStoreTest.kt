package com.jiacimu.lulu

import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE, sdk = [28])
class SleepNarrativeStoreTest {
    @Test fun heardStoryAndSummaryPersistAcrossReinitializationWithoutBecomingRealMemories() {
        val context = RuntimeEnvironment.getApplication()
        val id = "sleep-story-test"
        SleepNarrativeStore.initialize(context)
        SleepNarrativeStore.clear(id)
        SleepNarrativeStore.recordPlayed(id, "played-1", "小狐狸走进旧书屋，遇见了守门的猫。")
        SleepNarrativeStore.accept(id, "turn-1", JSONObject().put("kind", "story")
            .put("title", "旧书屋").put("cast", "狐狸、猫").put("summary", "狐狸第一次来到书屋")
            .put("openThread", "猫准备带狐狸看书架").toString())
        SleepNarrativeStore.initialize(context)
        val scene = SleepNarrativeStore.context(id, SleepGuidanceFocus.Story)
        assertTrue(scene.contains("守门的猫"))
        assertTrue(scene.contains("猫准备带狐狸看书架"))
        assertTrue(scene.contains("故事是虚构"))
        SleepNarrativeStore.clear(id)
    }
    @Test fun completedBodyGuidanceAdvancesAndStoriesDoNotStopForAQuietListener() {
        val context = RuntimeEnvironment.getApplication()
        val id = "sleep-body-test"
        SleepNarrativeStore.initialize(context)
        SleepNarrativeStore.clear(id)
        SleepNarrativeStore.recordPlayed(id, "heard", "找到一个舒服的姿势。")
        SleepNarrativeStore.accept(id, "turn", "{\"kind\":\"body\",\"nextBodyStage\":1}")
        assertTrue(SleepNarrativeStore.context(id, SleepGuidanceFocus.Body).contains("当前第2/"))
        val late = SleepGuidanceGuide.instruction(SleepGuidanceFocus.Story, true, 45 * 60_000L)
        assertTrue(late.contains("不能按安静了几分钟就中断故事"))
        assertEquals(2_500L, SleepCallContinuationPolicy.nextPauseMillis(45 * 60_000L, story = true))
        SleepNarrativeStore.clear(id)
    }
    @Test fun bedtimeChoicesRemainTwoDistinctContinuousExperiences() {
        assertEquals(listOf(SleepGuidanceFocus.Body, SleepGuidanceFocus.Story),
            SleepGuidanceGuide.choices)
        val body = SleepGuidanceGuide.instruction(SleepGuidanceFocus.Body, true, 24 * 60_000L)
        assertTrue(body.contains("只有放松身体"))
        assertTrue(body.contains("严格禁止机械收尾"))
        assertTrue(body.contains("不要每段末尾"))
        assertTrue(body.contains("继续当前身体部位"))
        assertFalse(body.contains("自然穿插："))
        assertFalse(body.contains("讲完整个故事"))
        val story = SleepGuidanceGuide.instruction(SleepGuidanceFocus.Story, true, 25 * 60_000L)
        assertTrue(story.contains("只有睡前故事"))
        assertTrue(story.contains("延续") || story.contains("接着往前走"))
        assertFalse(story.contains("讲完一个部位"))
    }

    @Test fun explicitSpokenStoryPreferenceWinsWithoutTreatingRefusalAsARequest() {
        assertEquals(SleepGuidanceFocus.Story, SleepGuidanceGuide.requestedFocus("我想听一个长故事"))
        assertEquals(SleepGuidanceFocus.Body, SleepGuidanceGuide.requestedFocus("帮我放松身体吧"))
        assertNull(SleepGuidanceGuide.requestedFocus("我不想听故事"))
        assertNull(SleepGuidanceGuide.requestedFocus("她昨天讲了一个故事"))
    }
}
