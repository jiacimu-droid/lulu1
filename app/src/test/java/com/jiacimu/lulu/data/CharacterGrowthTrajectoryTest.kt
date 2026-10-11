package com.jiacimu.lulu.data

import org.junit.Assert.*
import org.junit.Test

class CharacterGrowthTrajectoryTest {
    @Test fun explicitDiminishmentAndSafetyComplaintsAreImportantEvenWithoutApology() {
        assertEquals("被轻视或弱化", CharacterGrowthTrajectory.feedbackTopic("你总是把我弱化，我不想再被这样对待。"))
        assertEquals("被轻视或弱化", CharacterGrowthTrajectory.feedbackTopic("你总是在弱化我。"))
        assertEquals("关系中的安全感和信任", CharacterGrowthTrajectory.feedbackTopic("你给了我一种没有安全感的感觉。"))
        assertEquals("关系中的安全感和信任", CharacterGrowthTrajectory.feedbackTopic("我真的不敢信任你了。"))
        assertEquals("尊重与相处边界", CharacterGrowthTrajectory.feedbackTopic("不要替我决定，你需要尊重我的选择。"))
        assertTrue(EmotionFollowThroughCoordinator.qualifies("我听到了。", "你总是把我弱化"))
        assertTrue(EmotionFollowThroughCoordinator.qualifies("我有自己的不同看法。", "你让我没有安全感"))
    }

    @Test fun ordinaryChatAndOtherPeoplesExperienceCannotCreatePersonalRelationshipFeedback() {
        assertNull(CharacterGrowthTrajectory.feedbackTopic("我刚看了一篇讲安全感的小说"))
        assertNull(CharacterGrowthTrajectory.feedbackTopic("那个角色总是弱化主角"))
        assertNull(CharacterGrowthTrajectory.feedbackTopic("宝宝你今天吃了什么"))
        assertNull(CharacterGrowthTrajectory.feedbackTopic(""))
        assertFalse(EmotionFollowThroughCoordinator.qualifies("我在", "今天下雨了"))
    }
}
