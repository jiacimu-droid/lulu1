package com.jiacimu.lulu

import org.junit.Assert.*
import org.junit.Test

class PhoneBargeInPolicyTest {
    @Test fun rejectsLongExactPlaybackEchoOnlyWhileAudioIsPlaying() {
        val spoken = "我今天在阅读馆看了一会儿书，后来走出去散步。"
        assertTrue(PhoneBargeInPolicy.isLikelyPlaybackEcho(
            "我今天在阅读馆看了一会儿书", spoken, "", true))
        assertFalse(PhoneBargeInPolicy.isLikelyPlaybackEcho(
            "我今天在阅读馆看了一会儿书", spoken, "", false))
    }

    @Test fun realUserSpeechAndShortInterjectionsStillInterrupt() {
        assertFalse(PhoneBargeInPolicy.isLikelyPlaybackEcho(
            "等等，我还有话要说", "我今天在阅读馆看了一会儿书", "", true))
        assertFalse(PhoneBargeInPolicy.isLikelyPlaybackEcho(
            "不对", "不对，你之前不是这样说的", "", true))
    }

    @Test fun whitespaceAndPunctuationAreNotEvidenceOfAnotherVoice() {
        assertTrue(PhoneBargeInPolicy.isLikelyPlaybackEcho(
            "我今天在阅读馆，看了一会儿书", "我今天在阅读馆看了一会儿书，然后散步", "", true))
    }
}
