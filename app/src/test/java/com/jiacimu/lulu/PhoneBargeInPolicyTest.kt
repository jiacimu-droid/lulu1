package com.jiacimu.lulu

import org.junit.Assert.*
import org.junit.Test

class PhoneBargeInPolicyTest {
    @Test fun actualSpeakerPlaybackNeverFeedsCloudRecognition() {
        assertFalse(PhoneBargeInPolicy.shouldCapture(false, true, false))
        assertFalse(PhoneBargeInPolicy.shouldCapture(true, false, true))
        assertTrue(PhoneBargeInPolicy.shouldCapture(false, false, false))
        // Headset/receiver keeps microphone open for interruption.
        assertTrue(PhoneBargeInPolicy.shouldCapture(false, true, true))
    }

    @Test fun echoIsDroppedEvenWhenScribeReturnsOnlyAShortFragment() {
        val agent = "宝宝你先去休息，我待会儿再过来找你。"
        assertTrue(PhoneBargeInPolicy.isLikelyPlaybackEcho("宝宝你先去休息", agent, "", true))
        assertTrue(PhoneBargeInPolicy.isLikelyPlaybackEcho("宝宝", agent, "", true))
        assertFalse(PhoneBargeInPolicy.isLikelyPlaybackEcho("宝宝", agent, "", false))
    }

    @Test fun smallMisrecognitionOfAgentTtsDoesNotBecomeUserMessage() {
        val agent = "我今天在阅读馆看了一会儿书，后来出去散步。"
        assertTrue(PhoneBargeInPolicy.isLikelyPlaybackEcho(
            "我今天在阅读馆看了会儿书", agent, "", true))
        assertTrue(PhoneBargeInPolicy.isLikelyPlaybackEcho(
            "我今天在阅读馆，看了一会儿书", agent, "", true))
    }

    @Test fun unrelatedHumanSpeechCanStillInterruptWithHeadphones() {
        val agent = "我今天在阅读馆看了一会儿书，后来走出去散步。"
        assertFalse(PhoneBargeInPolicy.isLikelyPlaybackEcho(
            "等等，我还有话要说", agent, "", true))
        assertTrue(PhoneBargeInPolicy.safePartialBargeIn("等等我还没说完", true, true))
        assertFalse(PhoneBargeInPolicy.safePartialBargeIn("等", true, true))
        assertFalse(PhoneBargeInPolicy.safePartialBargeIn("等等，我还有话说", false, true))
        assertTrue(PhoneBargeInPolicy.safePartialBargeIn("等等", false, false))
    }

    @Test fun recentPlaybackIsNotMistakenForNewHumanTurn() {
        assertTrue(PhoneBargeInPolicy.isLikelyPlaybackEcho(
            "你好，你刚才说什么", "你好，你刚才说什么？", "", true))
        assertFalse(PhoneBargeInPolicy.isLikelyPlaybackEcho(
            "我现在想换个话题", "你好，你刚才说什么？", "", true))
    }
}
