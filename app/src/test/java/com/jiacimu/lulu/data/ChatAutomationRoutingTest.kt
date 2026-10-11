package com.jiacimu.lulu.data

import org.junit.Assert.*
import org.junit.Test

class ChatAutomationRoutingTest {
    @Test fun genericCasualMessageMustNotCallPromiseOrLexiconModel() {
        assertFalse(looksLikeTaskTurn("还没，你等我一下。", "好，我等你。"))
        assertFalse(looksLikeTaskTurn("明天再说吧", "我会听你的"))
        assertFalse(looksLikeTaskTurn("等会儿再聊", "好呀"))
        assertFalse(looksLikeLexiconCandidate("还没，你等我一下。好，我等你。"))
        assertFalse(looksLikeLexiconCandidate("我会想你的"))
    }

    @Test fun genuineFutureObligationAndRelationshipConcernRemainEligible() {
        assertTrue(looksLikeTaskTurn("明天8点叫我起床", "好，我会叫醒你"))
        assertTrue(looksLikeTaskTurn("你等会儿给我打电话", "好"))
        assertTrue(looksLikeLexiconCandidate("我今天要准备考试"))
        assertTrue(looksLikeLexiconCandidate("我对这件事有点担心"))
    }
}
