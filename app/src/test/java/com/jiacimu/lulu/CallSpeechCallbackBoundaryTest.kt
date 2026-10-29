package com.jiacimu.lulu

import org.junit.Assert.assertEquals
import org.junit.Test

class CallSpeechCallbackBoundaryTest {
    @Test fun brokenTranscriptObserverCannotSkipSpeechCleanupOrNextQueuedTurn() {
        val events = mutableListOf<String>()
        CallSpeechCallbackBoundary.dispatch(
            { events += "reported:${it.message}" },
            { throw IllegalStateException("save failed") },
            { events += "ended" },
            { events += "play next" },
        )
        assertEquals(listOf("reported:save failed", "ended", "play next"), events)
    }

    @Test fun brokenErrorReporterCannotCrashPlaybackOrHideLaterCallbacks() {
        val events = mutableListOf<String>()
        CallSpeechCallbackBoundary.dispatch(
            { throw IllegalArgumentException("logger unavailable") },
            { throw IllegalArgumentException("visual callback failed") },
            { events += "tts completed" },
        )
        assertEquals(listOf("tts completed"), events)
    }
}
