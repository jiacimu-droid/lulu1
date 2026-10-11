package com.jiacimu.lulu

import org.junit.Assert.*
import org.junit.Test

class ScribeRealtimeTranscriptStateTest {
    @Test fun partialSpeechIsPreviewOnlyUntilServerCommits() {
        val state = ScribeRealtimeTranscriptState()
        assertEquals("今天我", state.preview("今天我"))
        assertEquals("今天我想", state.preview("今天我想"))
        assertEquals("", state.takeFinal()) // Partial words never become user messages.
    }

    @Test fun longSpeechCommitsAccumulateButOnlyFinalFlushesOnce() {
        val state = ScribeRealtimeTranscriptState()
        assertEquals("我今天去了超市", state.commit("我今天去了超市"))
        assertEquals("我今天去了超市还看见一只小猫", state.preview("还看见一只小猫"))
        assertEquals("我今天去了超市还看见一只小猫", state.commit("还看见一只小猫"))
        assertEquals("我今天去了超市还看见一只小猫", state.takeFinal())
        assertEquals("", state.takeFinal())
    }

    @Test fun repeatedChineseSyllablesInSeparateAudioChunksStayDistinct() {
        val state = ScribeRealtimeTranscriptState()
        state.commit("哈哈")
        state.commit("哈哈")
        assertEquals("哈哈哈哈", state.takeFinal())
    }
}
