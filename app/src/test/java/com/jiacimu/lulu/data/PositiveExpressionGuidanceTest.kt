package com.jiacimu.lulu.data

import org.junit.Assert.*
import org.junit.Test

/** Prompt-contract checks only; these do not claim real-model personality quality. */
class PositiveExpressionGuidanceTest {
    @Test fun privateDecisionAndRenderingShareTurnTakingInsteadOfOnlyBubbleSplitting() {
        val decision = CharacterExpressionGuide.channelNote(CharacterSpeechChannel.PRIVATE_CHAT)
        val renderer = CharacterExpressionGuide.forGeneration("聊天表达渲染")
        for (prompt in listOf(decision, renderer)) {
            assertTrue(prompt.contains("日常接话以有来有回为自然节奏"))
            assertTrue(prompt.contains("不替对方编造下一句话"))
            assertTrue(prompt.contains("表达已经足够时"))
            assertTrue(prompt.contains("交还话轮"))
            assertTrue(prompt.contains("分泡本身不会减少内容"))
            assertTrue(prompt.contains("复杂问题、多项需求"))
        }
    }

    @Test fun everyGatewayModeHasOneCommonPersonalityGuide() {
        listOf("普通聊天", "聊天表达渲染", "聊天表达自然度修复", "电话回复").forEach { source ->
            val prompt = CharacterExpressionGuide.forGeneration(source)
            assertEquals(1, prompt.split("【以本人为表达的起点】").size - 1)
            assertTrue(prompt.contains("性格同时影响关注点、立场、主动性、措辞与节奏"))
            assertTrue(prompt.contains("执行成功必须有真正回执"))
            assertFalse(prompt.contains("严禁"))
            assertFalse(prompt.contains("一到三句"))
            assertFalse(prompt.contains("不要"))
        }
    }

    @Test fun rendererKeepsNegationConditionsAndModelChosenMeaning() {
        val prompt = CharacterExpressionGuide.forGeneration("聊天表达渲染")
        assertTrue(prompt.contains("必要答案、条件、否定与不确定性完整保留"))
        assertTrue(prompt.contains("称呼、句式、停顿、玩笑与修辞由表达层"))
        assertTrue(prompt.contains("实际行动仍由原执行器负责"))
        assertTrue(prompt.contains("句数、字数和气泡数量都留有空间"))
        assertEquals(1, prompt.split("【本人已经作出选择，现在对她说话】").size - 1)
    }

    @Test fun channelAdaptersDescribeAudienceAndMediumRatherThanAssigningOneTemperament() {
        CharacterSpeechChannel.values().forEach { channel ->
            val prompt = CharacterExpressionGuide.channelNote(channel)
            assertTrue(prompt.contains("沿用本人的判断、稳定称呼与语言习惯"))
            assertFalse(prompt.contains("必须温柔"))
            assertFalse(prompt.contains("必须幽默"))
            assertFalse(prompt.contains("严禁"))
            assertFalse(prompt.contains("不要"))
        }
        assertTrue(CharacterExpressionGuide.channelNote(CharacterSpeechChannel.GROUP_CHAT).contains("隐私"))
        assertTrue(CharacterExpressionGuide.channelNote(CharacterSpeechChannel.PHONE).contains("低延迟"))
    }

    @Test fun sameSelectionGuidanceDoesNotClassifyOrCopyTheUsersWords() {
        val casual = ConversationDominantImpulse.planningGuidance("杀了你")
        assertEquals(casual, ConversationDominantImpulse.planningGuidance("请分析学习安排"))
        assertFalse(casual.contains("杀了你"))
        assertTrue(casual.contains("复杂求助"))
        assertTrue(casual.contains("多个执行动作"))
        // An authored conversational beat may be short without converting
        // the role's knowledge into an exhaustive report.
        assertTrue(casual.contains("选择此刻真正想说出口的那一拍"))
        assertTrue(casual.contains("复杂求助、多项明确需求与重要边界可以充分讲清"))
        assertFalse(casual.contains("逐项回应清单"))
    }
    @Test fun sharedTurnTakingTreatsOneWordGameMovesAsCompleteAndLeavesOtherTasksOpen() {
        val prompts = listOf(
            CharacterExpressionGuide.channelNote(CharacterSpeechChannel.PRIVATE_CHAT),
            CharacterExpressionGuide.forGeneration("聊天表达渲染"),
            proactiveDecisionInstruction("game-rhythm-contract", directPrivateChat = true),
        )
        prompts.forEach { prompt ->
            assertTrue(prompt.contains("【共同活动的回合感】"))
            assertTrue(prompt.contains("答案本身就可以是一轮完整的参与"))
            assertTrue(prompt.contains("下一轮要等真实新输入"))
            assertTrue(prompt.contains("对方在问规则、讨论策略"))
            assertTrue(prompt.contains("认真请教、复杂问题、多项需求"))
            assertFalse(prompt.contains("每条不超过"))
            assertFalse(prompt.contains("一到三句"))
        }
        // The guide is not a user-text keyword classifier or a separate model.
        assertEquals(
            CharacterExpressionGuide.channelNote(CharacterSpeechChannel.PRIVATE_CHAT),
            CharacterExpressionGuide.channelNote(CharacterSpeechChannel.PRIVATE_CHAT),
        )
    }

    @Test fun identityOwnsExpressionAndWorldbookOnlySuppliesCulture() {
        val plan = CharacterExpressionGuide.forGeneration("普通聊天")
        val renderer = CharacterExpressionGuide.forGeneration("聊天表达渲染")
        assertTrue(plan.contains("世界书提供文化、场景与交流习惯的背景知识"))
        assertTrue(plan.contains("个人人格"))
        assertTrue(renderer.contains("内部对照本人的完整分层人格"))
        assertTrue(renderer.contains("这个核对过程留在内部"))
        assertFalse(renderer.contains("重新选择行动"))
    }

}
