package com.jiacimu.lulu.data

import org.junit.Assert.*
import org.junit.Test

class TransientConversationStyleTest {
    @Test fun shortMessageBiasesTowardBriefWithoutChangingPersona() {
        val style = TransientConversationStyle.analyze("好累啊")
        assertEquals(TransientConversationStyle.Pace.BRIEF, style.pace)
        assertEquals(TransientConversationStyle.TonePressure.LIGHT, style.tonePressure)
        assertTrue(TransientConversationStyle.context("好累啊").contains("只影响这次表达"))
    }

    @Test fun explicitMisunderstandingBecomesRepairPressure() {
        val style = TransientConversationStyle.analyze("你没有get到我什么意思")
        assertEquals(TransientConversationStyle.TonePressure.REPAIR, style.tonePressure)
        assertTrue(TransientConversationStyle.context("你没有get到我什么意思").contains("修复共同理解"))
    }

    @Test fun multiPartInputCanAllowBurstButNotSentenceFragments() {
        val style = TransientConversationStyle.analyze("我学累了\n脑子都转不动了\n想歇一下")
        assertTrue(style.prefersShortBurst)
        val context = TransientConversationStyle.context("我学累了\n脑子都转不动了\n想歇一下")
        assertTrue(context.contains("绝不能把一个语法未结束的句子拆成多个气泡"))
    }

    @Test fun complexRequestAllowsFullerResponse() {
        val style = TransientConversationStyle.analyze(
            "首先我想说今天学习安排有点乱，其次我还需要重新排一下专业课，然后英语也要一起考虑，具体应该怎么调整？"
        )
        assertEquals(TransientConversationStyle.Pace.EXPANSIVE, style.pace)
    }

    @Test fun longVoiceTranscriptIsNotAutomaticallyARequestForALongAnswer() {
        val text = "今天先去上了课，然后回家路上看到了很漂亮的晚霞，还有那家一直没开门的小店今天也开门了。" +
            "我就在门口站了好一会儿，其实也没买什么，就是忽然觉得今天挺好的。" +
            "刚才回来又把房间整理了一遍，想跟你说说，听你说两句就好。"
        assertEquals(TransientConversationStyle.Pace.NORMAL, TransientConversationStyle.analyze(text).pace)
        assertFalse(TransientConversationStyle.allowsExpansion(text))
    }

    @Test fun casualWhyDoesNotRequestAnEssayButExplicitDetailDoes() {
        assertFalse(TransientConversationStyle.allowsExpansion("为什么更喜欢这个名字？"))
        assertFalse(TransientConversationStyle.allowsExpansion("为什么，简单说就好，别详细解释"))
        assertTrue(TransientConversationStyle.allowsExpansion("请详细对比这两个名字的字义和读音"))
        assertTrue(TransientConversationStyle.allowsExpansion("帮我分析一下今天的学习计划怎么调整"))
    }

    @Test fun storyAndContinuationRemainExpansiveWithoutCasualExamples() {
        listOf("讲个长一点的故事哄我睡", "接着讲", "继续", "认真展开聊聊你的想法").forEach { input ->
            assertEquals(input, TransientConversationStyle.Pace.EXPANSIVE,
                TransientConversationStyle.analyze(input).pace)
            assertFalse(TransientConversationStyle.context(input).contains("虚构的多轮节奏示例"))
        }
        assertFalse(TransientConversationStyle.allowsExpansion("今天不想听故事"))
    }

    @Test fun styleCorrectionOverridesTheLengthOfTheCorrectionItself() {
        val text = "我不想你每次都写小作文，把名字的字义和我们之间的感情都解释一遍。" +
            "我想你说得简洁一点，也有自己的看法和情绪，给我接话的空间。"
        assertEquals(TransientConversationStyle.Pace.BRIEF, TransientConversationStyle.analyze(text).pace)
        assertTrue(TransientConversationStyle.context(text).contains("虚构的多轮节奏示例"))
        assertFalse(TransientConversationStyle.context(text, includeExamples = false)
            .contains("虚构的多轮节奏示例"))
    }

    @Test fun requestedWritingAndListsAreCompleteTasksRatherThanCasualReactions() {
        listOf("帮我写一篇作文", "替我写封邮件", "再给我一批名字", "列三个方案", "帮我整理考点").forEach { input ->
            assertTrue(input, TransientConversationStyle.allowsExpansion(input))
        }
        assertFalse(TransientConversationStyle.allowsExpansion("这三个名字里你喜欢哪个？"))
    }
}
