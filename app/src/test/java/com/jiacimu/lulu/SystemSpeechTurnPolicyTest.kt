package com.jiacimu.lulu

import org.junit.Assert.*
import org.junit.Test

class SystemSpeechTurnPolicyTest {
    @Test fun shortAnswersCanCommitQuicklyButAnExplanationKeepsBreathingRoom() {
        assertEquals(850L, SystemSpeechTurnPolicy.continuationMillis("嗯"))
        assertEquals(1_150L, SystemSpeechTurnPolicy.continuationMillis("是的，我已经起床啦"))
        assertEquals(1_650L, SystemSpeechTurnPolicy.continuationMillis("今天早上我还想和你聊一下昨天那件有意思的事"))
        assertEquals(2_100L, SystemSpeechTurnPolicy.continuationMillis("我今天早上本来想出去买一杯热咖啡，但是后来又觉得外面有点冷，所以现在还没决定到底要不要出门"))
    }

    @Test fun reopeningRecognizerAcrossUnfinishedBufferDoesNotDependOnThinking() {
        assertTrue(SystemSpeechTurnPolicy.canListen(true, false, false, true, false, false))
        assertTrue(SystemSpeechTurnPolicy.canListen(true, false, false, false, true, true))
        assertFalse(SystemSpeechTurnPolicy.canListen(true, false, false, false, true, false))
        assertFalse(SystemSpeechTurnPolicy.canListen(true, true, false, false, false, true))
        assertFalse(SystemSpeechTurnPolicy.canListen(true, false, true, false, false, true))
    }

    @Test fun interruptRequiresRealUserSpeechAndPrivateOutputWhenTtsIsPlaying() {
        assertFalse(SystemSpeechTurnPolicy.shouldInterrupt(false, true, true, true))
        assertTrue(SystemSpeechTurnPolicy.shouldInterrupt(true, true, false, false))
        assertTrue(SystemSpeechTurnPolicy.shouldInterrupt(true, false, true, true))
        assertFalse(SystemSpeechTurnPolicy.shouldInterrupt(true, false, true, false))
    }

    @Test fun mutingFinishesTheAlreadyRecognizedUtteranceExactlyOnce() {
        assertEquals("前面已经讲的话", SystemSpeechTurnPolicy.wordsToSubmitOnMute(
            "前面已经讲的话", ""))
        assertEquals("前面已经讲的话", SystemSpeechTurnPolicy.wordsToSubmitOnMute(
            "前面已经讲的话", "前面已经讲的话"))
        assertEquals("我先说这一段再继续", SystemSpeechTurnPolicy.wordsToSubmitOnMute(
            "我先说这一段", "再继续"))
        assertEquals("手机又没识别出来吗", SystemSpeechTurnPolicy.wordsToSubmitOnMute(
            "", "手机又没识别出来吗"))
        assertEquals("", SystemSpeechTurnPolicy.wordsToSubmitOnMute("", ""))
    }
}
