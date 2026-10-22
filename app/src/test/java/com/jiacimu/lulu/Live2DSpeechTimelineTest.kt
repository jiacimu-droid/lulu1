package com.jiacimu.lulu

import org.junit.Assert.assertEquals
import org.junit.Test
import java.util.UUID

class Live2DSpeechTimelineTest {
    @Test fun oldUtteranceCannotClearNewPlaybackOrOtherCall() {
        val role = "wolf-" + UUID.randomUUID()
        val call = "call-" + UUID.randomUUID()
        Live2DSpeechTimeline.started(role, call, "first")
        assertEquals(Live2DSpeechTimeline.Phase.Playing,
            Live2DSpeechTimeline.events.value.phase)
        Live2DSpeechTimeline.started(role, call, "second")
        Live2DSpeechTimeline.finished(role, call, "first")
        assertEquals("second", Live2DSpeechTimeline.events.value.utteranceId)
        assertEquals(Live2DSpeechTimeline.Phase.Playing,
            Live2DSpeechTimeline.events.value.phase)
        Live2DSpeechTimeline.interrupted(role, "wrong-call")
        assertEquals(Live2DSpeechTimeline.Phase.Playing,
            Live2DSpeechTimeline.events.value.phase)
        Live2DSpeechTimeline.finished(role, call, "second")
        assertEquals(Live2DSpeechTimeline.Phase.Finished,
            Live2DSpeechTimeline.events.value.phase)
        Live2DSpeechTimeline.started(role, call, "third")
        Live2DSpeechTimeline.interrupted(role, call)
        assertEquals(Live2DSpeechTimeline.Phase.Interrupted,
            Live2DSpeechTimeline.events.value.phase)
        val rev = Live2DSpeechTimeline.events.value.revision
        Live2DSpeechTimeline.finished(role, call, "third")
        assertEquals(rev, Live2DSpeechTimeline.events.value.revision)
    }
}
