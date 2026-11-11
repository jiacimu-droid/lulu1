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

    @Test fun nativeAcknowledgmentAndSingleTurnRhythmRemainModelAuthored() {
        val guide = CharacterExpressionGuide.forGeneration("聊天表达渲染")
        val scene = CharacterExpressionGuide.channelNote(CharacterSpeechChannel.PRIVATE_CHAT)
        val noDuplicate = CharacterExpressionGuide.forGeneration(
            "聊天表达渲染", includeTurnTaking = false)
        assertTrue("A one-beat acknowledgment can naturally be sent before a longer continuation",
            guide.contains("一个短回应（如收到）"))
        assertTrue("Keep model-selected independent bubbles, not a forced comma splitter",
            guide.contains("各是一个 bubbles 元素") && guide.contains("不是逢逗号就拆"))
        assertTrue("The character naturally owns the preference instead of repeating a prohibition",
            guide.contains("过去相处形成的阅读默契，自然体现在此刻的措辞与发送停顿里"))
        assertTrue("Removing a duplicate scene rule must not remove the character-owned bubble contract",
            noDuplicate.contains(CharacterExpressionGuide.livingLanguagePrinciples))
        assertFalse(noDuplicate.contains(CharacterExpressionGuide.casualChatRhythm))
        assertEquals(1, (scene + "\n" + noDuplicate)
            .split(CharacterExpressionGuide.casualChatRhythm).size - 1)
        assertTrue(noDuplicate.contains("必要答案、条件、否定与不确定性完整保留"))
    }

    @Test fun aLongerLaterReplyKeepsTheSameCharacterAuthoredSendBeats() {
        val full = CharacterExpressionGuide.forGeneration("后台主动感知")
        val lean = CharacterExpressionGuide.forGeneration("聊天表达渲染")
        for (prompt in listOf(full, lean)) {
            assertTrue("Send rhythm also belongs to the second half of a reply",
                prompt.contains("一轮的后半段也沿用这个节奏"))
            assertTrue("Naturally separate a new reaction from a next-step proposal",
                prompt.contains("说出一句评价就能先发"))
            assertTrue("This remains authored speaking rhythm, not a hard punctuation splitter",
                prompt.contains("不是逢逗号就拆、固定三条或按字数截断"))
            assertTrue("Explanations and stories still have room to breathe",
                prompt.contains("认真答疑、完整创作和连续故事"))
        }
        val oneCall = proactiveDecisionInstruction("native-longer-chat", directPrivateChat = true)
        assertTrue(oneCall.contains("依次写每次会按发送的内容"))
        assertTrue(oneCall.contains("其元素数量由这次真实表达决定"))
        assertFalse("Bubble pacing is speech, not a private thought",
            spontaneousInnerVoiceGuide.contains("一轮的后半段也沿用这个节奏"))
    }

    @Test fun privateChatCanSendPhraseSizedMessagesWithoutMandatoryPunctuation() {
        // The same character-owned guidance reaches one-call online chat and
        // the separate foreground expression renderer; no hard comma splitter.
        for (source in listOf("普通聊天", "聊天表达渲染")) {
            val prompt = CharacterExpressionGuide.forGeneration(source)
            assertTrue(prompt.contains("不必等到整句、完整语法或句号才发送"))
            assertTrue(prompt.contains("末尾不必特意补逗号、句号"))
            assertTrue(prompt.contains("不是逢逗号就拆、固定三条或按字数截断"))
            assertTrue(prompt.contains("已经选择分别发送的片段"))
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
            assertFalse(prompt.contains("必须统一撒娇"))
            assertTrue(prompt.contains("具体选择来自当前这个人") ||
                prompt.contains("此处承接这一次选择的立场"))
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
            assertFalse(prompt.contains("不要主动联系"))
            assertFalse(prompt.contains("每泡必须完整"))
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

    @Test fun conversationIsJointlyBuiltAndEachTurnHasItsOwnCompletionPoint() {
        // Model selects the dialogue move. The renderer only realizes that
        // same move in the person's own voice, not an assistant-style essay.
        val decision = CharacterDecisionProtocol.causalPrinciples
        val rendering = CharacterExpressionGuide.forGeneration("聊天表达渲染")
        val channel = CharacterExpressionGuide.channelNote(CharacterSpeechChannel.PRIVATE_CHAT)
        assertTrue(decision.contains("共同推进而非单人完成"))
        assertTrue(decision.contains("对方的下一步反应仍是未知"))
        assertTrue(decision.contains("复杂求助、多项明确需求与重要边界可以充分讲清"))
        assertTrue(rendering.contains("以本人已选的交流动作是否在这一拍完成为准"))
        assertTrue(rendering.contains("不以“是否把整段话题的全部可能信息都说尽”为准"))
        assertTrue(channel.contains("整个话题还远远没有聊完也没关系"))
        assertTrue(channel.contains("反馈共同建立理解"))
        assertTrue(channel.contains("下一轮要等真实新输入"))
        assertFalse(channel.contains("最多回复"))
        assertFalse(channel.contains("每条不得超过"))
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

    // The concise runtime contract and the full private-life guide jointly cover spontaneity.
    @Test fun immediateAndReflectiveReactionsBothBelongToOnePersonWithoutQuotas() {
        val ordinary = CharacterExpressionGuide.forGeneration("普通聊天")
        val expression = CharacterExpressionGuide.forGeneration("聊天表达渲染")
        val directPrivate = CharacterExpressionGuide.channelNote(CharacterSpeechChannel.PRIVATE_CHAT)
        val phone = CharacterExpressionGuide.channelNote(CharacterSpeechChannel.PHONE)
        val protocol = CharacterDecisionProtocol.runtimePrinciples
        val privateLife = spontaneousInnerVoiceGuide

        assertTrue(ordinary.contains("并非总是完成一整套审慎推演之后"))
        assertTrue(ordinary.contains("复杂问题、现实行动或重要关系分歧"))
        assertTrue(expression.contains("保留临场反应的简洁"))
        assertTrue(expression.contains("对已选定的审慎回应"))
        assertTrue(directPrivate.contains("顺势脱口而出的自然接话"))
        assertTrue(phone.contains("沿用本人的判断"))
        assertTrue(protocol.contains("性格、关系、情绪与现场共同决定接话或沉默"))
        assertTrue(protocol.contains("不强制填心理字段"))
        assertTrue(privateLife.contains("第一反应可以直接说出口"))
        assertTrue(privateLife.contains("已经说出去的直觉不需要重复记录成心声"))
        assertFalse(expression.contains("必须先写心声"))
        assertFalse(directPrivate.contains("每句话必须先思考"))
    }

    @Test fun spontaneousReplyIsNotFakeTwoBrainsOrExtraModelCall() {
        val planner = ConversationDominantImpulse.planningGuidance("真的吗")
        val renderer = ConversationDominantImpulse.expressionRule
        assertTrue(planner.contains("直觉反应与审慎回应，都出自同一人物"))
        assertTrue(planner.contains("不要求每轮出现冲动"))
        assertTrue(planner.contains("权限、伪造事实"))
        assertTrue(renderer.contains("若本轮已选的是脱口而出的短反应"))
        assertTrue(renderer.contains("事实、条件与分寸"))
        assertFalse(planner.contains("左脑"))
        assertFalse(planner.contains("右脑"))
        assertEquals(planner, ConversationDominantImpulse.planningGuidance("请完整讲解法律问题"))
    }

    @Test fun onlineChatSchemaDoesNotTeachThreeBubblesOrOneDefaultReaction() {
        val prompt = proactiveDecisionInstruction("style-example-check", directPrivateChat = true)
        assertTrue(prompt.contains("message 可直接用 text"))
        assertTrue(prompt.contains("其元素数量由这次真实表达决定"))
        assertTrue(prompt.contains("同一个意思可以先说半句、下一泡接着说"))
        assertFalse(prompt.contains("第三句"))
        assertFalse(prompt.contains("bubbles=[{\"text\":\"第一句\""))
        assertTrue(prompt.contains("复杂问题、多项需求"))
    }

}
