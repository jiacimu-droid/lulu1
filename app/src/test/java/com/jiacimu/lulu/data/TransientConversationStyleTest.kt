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
}
