package com.jiacimu.lulu.data

import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE, sdk = [29])
class ConversationDominantImpulseTest {
    @Test fun shortCasualCueExamplesIllustrateChoosingOneIntent() {
        val guide = ConversationDominantImpulse.planningGuidance("喂")
        assertTrue(guide.contains("怎么啦宝宝？"))
        assertTrue(guide.contains("歪？"))
        assertTrue(guide.contains("玩心占主导"))
        assertTrue(guide.contains("求救"))
        assertTrue(guide.contains("不能复制示例口癖"))
    }

    @Test fun seriousOrSubstantiveUserRequestsAreNotGivenCuteSamples() {
        assertFalse(ConversationDominantImpulse.planningGuidance("我很难受").contains("歪？"))
        assertFalse(ConversationDominantImpulse.planningGuidance(
            "请详细分析六个方案各自的优缺点和风险").contains("歪？"))
        assertTrue(ConversationDominantImpulse.causalRule.contains("复杂求助"))
    }

    @Test fun oneCommonDecisionMaintainsPluralThoughtsAndMultiActionSupport() {
        assertTrue(CharacterDecisionProtocol.causalPrinciples.contains("并存心念→主导交流动作→说出口"))
        assertTrue(CharacterDecisionProtocol.causalPrinciples.contains("多个执行动作"))
        assertTrue(ConversationDominantImpulse.expressionRule.contains("speechIntent / dialogueMove"))
    }

    @Test fun onlineChatUsesTheSameShortTurnSelectionGuidance() {
        val privateChat = privateConversationInstruction("宝宝？", "上条在讨论头像")
        assertTrue(privateChat.contains("怎么啦宝宝？"))
        assertTrue(privateChat.contains("主导交流动作"))
        assertTrue(privateChat.contains("text 会原样进入聊天气泡"))
    }
}
