package com.jiacimu.lulu.data

import org.junit.Assert.*
import org.junit.Test

class OnlineContinuationPolicyTest {
    @Test fun lowArousalIsAChoiceCueNotAutomaticSleep() {
        val calm = CharacterInnerLifeStore.EmotionInfluence(
            elapsedMinutes = 30, halfLifeMinutes = 120,
            initialStrength = 3, remainingStrength = 2.5,
            valence = 0.4, arousal = -0.7,
        )
        val cue = OnlineContinuationPolicy.affectCue(calm)
        assertTrue(cue.contains("当前唤醒度=-0.70"))
        assertTrue(cue.contains("当前愉悦度=+0.40"))
        assertTrue(cue.contains("偏低可以愿意休息"))
        assertTrue(cue.contains("不自动续时，也不自动下线"))
        // At the very same low arousal the role can still choose to continue,
        // if it has a grounded concrete wish; the axis is NOT an extra gate.
        assertTrue(OnlineContinuationPolicy.permits(
            "continue", "还想把读到的这一段好好记下来", false, true, "", Long.MAX_VALUE))
    }

    @Test fun highArousalCanStillChooseSleepAndMissingAxesAddNoFakeState() {
        val activated = CharacterInnerLifeStore.EmotionInfluence(
            elapsedMinutes = 0, halfLifeMinutes = 60,
            initialStrength = 3, remainingStrength = 3.0,
            valence = -0.65, arousal = 0.85,
        )
        val cue = OnlineContinuationPolicy.affectCue(activated)
        assertTrue(cue.contains("当前唤醒度=+0.85"))
        assertTrue(cue.contains("当前愉悦度=-0.65"))
        assertTrue(cue.contains("同样可以主动暂停"))
        assertFalse(OnlineContinuationPolicy.permits(
            "sleep", "现在想先休息一阵", false, true, "", Long.MAX_VALUE))
        assertEquals("", OnlineContinuationPolicy.affectCue(null))
        assertEquals("", OnlineContinuationPolicy.affectCue(activated.copy(
            arousal = null, valence = null)))
    }

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
