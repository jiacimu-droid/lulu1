package com.jiacimu.lulu.data

import org.junit.Assert.*
import org.junit.Test

class OnlineContinuationPolicyTest {
    @Test fun emptyWaitingDoesNotKeepAnExpensiveOnlineLeaseForever() {
        assertFalse(OnlineContinuationPolicy.permits(
            "continue", "我想继续在线等她发消息", false, false, "", Long.MAX_VALUE))
        assertFalse(OnlineContinuationPolicy.permits(
            "continue", "还是随便逛逛，保持在线", false, false, "", Long.MAX_VALUE))
        assertFalse(OnlineContinuationPolicy.permits(
            "continue", "想继续在线", false, true, "", Long.MAX_VALUE))
        assertFalse(OnlineContinuationPolicy.permits(
            "sleep", "准备睡觉", false, true, "", Long.MAX_VALUE))
    }

    @Test fun genuinePersonalInterestOrMentalWorkCanStillExtendTheLease() {
        assertTrue(OnlineContinuationPolicy.permits(
            "continue", "还想继续探索庭院里的真实植物，整理观察过的细节",
            true, false, "", Long.MAX_VALUE))
        assertTrue(OnlineContinuationPolicy.permits(
            "continue", "之前那件事还在想，想清楚自己为什么犹豫",
            false, true, "", Long.MAX_VALUE))
    }

    @Test fun shortButSincereCharacterThoughtsAreNotRejectedByArbitraryLengthOrKeywords() {
        assertTrue(OnlineContinuationPolicy.permits(
            "continue", "睡不着", false, false, "", Long.MAX_VALUE))
        assertTrue(OnlineContinuationPolicy.permits(
            "continue", "在想她", false, false, "", Long.MAX_VALUE))
        assertTrue(OnlineContinuationPolicy.permits(
            "continue", "刚读完的书还有点意思，想继续琢磨",
            false, false, "", Long.MAX_VALUE))
        assertFalse(OnlineContinuationPolicy.permits(
            "continue", "保持在线", false, true, "", Long.MAX_VALUE))
    }

    @Test fun repeatingTheSameQuietReasonInEveryFiveMinuteWindowIsRejected() {
        val reason = "之前那件事还在想，想清楚自己为什么犹豫"
        assertFalse(OnlineContinuationPolicy.permits(
            "continue", reason, false, true, reason, 5L))
        assertTrue(OnlineContinuationPolicy.permits(
            "continue", reason, false, true, reason, 24L))
    }
}
