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

    @Test fun durationAnswerToACharacterWakeQuestionGetsOneSemanticRecoveryChance() {
        assertTrue(acceptedWakeAfterSleepDurationQuestion(
            "宝宝想睡多久呀？", "20分钟", "好，二十分钟后我叫你起床"))
        assertTrue(acceptedWakeAfterSleepDurationQuestion(
            "想睡多久？", "半小时", "等会儿我来叫醒你"))
        assertFalse(acceptedWakeAfterSleepDurationQuestion(
            "你这次打算睡多久？", "20分钟", "好好睡吧，我在这里"))
        assertFalse(acceptedWakeAfterSleepDurationQuestion(
            "今晚准备做什么？", "20分钟", "二十分钟后叫你"))
        assertFalse(acceptedWakeAfterSleepDurationQuestion(
            "你想睡多久？", "20分钟", "不行，我不能叫醒你"))
        assertFalse(acceptedWakeAfterSleepDurationQuestion(
            "你想睡多久？", "等会儿吧", "我会叫醒你"))
    }

    @Test fun genuineFutureObligationAndFactualLifeNotesRemainEligible() {
        assertTrue(looksLikeTaskTurn("明天8点叫我起床", "好，我会叫醒你"))
        assertTrue(looksLikeTaskTurn("你等会儿给我打电话", "好"))
        assertTrue(looksLikeLexiconCandidate("我今天要准备考试"))
        assertFalse(looksLikeLexiconCandidate("我担心这件事"))
        assertTrue(looksLikeLexiconCandidate("我最近搬家了"))
        assertTrue(looksLikeLexiconCandidate("昨晚两点睡，今天早一点"))
        assertTrue(looksLikeLexiconCandidate("我凌晨两点才睡"))
        assertTrue(looksLikeLexiconCandidate("我今天起床了"))
    }
}
