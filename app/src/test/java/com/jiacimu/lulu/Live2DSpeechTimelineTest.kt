package com.jiacimu.lulu

import org.junit.Assert.assertEquals
import org.junit.Test
import java.util.UUID

class Live2DSpeechTimelineTest {
    @Test fun audioPositionCannotCrossUtterancesRolesOrGoBackwards() {
        val role = "clock-" + UUID.randomUUID()
        val call = "call-" + UUID.randomUUID()
        Live2DSpeechTimeline.started(role, call, "a", 0, 90, "s1")
        Live2DSpeechTimeline.progressed(role, call, "a", 300)
        Live2DSpeechTimeline.progressed(role, call, "a", 100)
        Live2DSpeechTimeline.progressed("another", call, "a", 1000)
        assertEquals(300L, Live2DSpeechTimeline.position.value.elapsedMs)
        Live2DSpeechTimeline.started(role, call, "b", 1, 90, "s2")
        Live2DSpeechTimeline.progressed(role, call, "a", 500)
        assertEquals(0L, Live2DSpeechTimeline.position.value.elapsedMs)
        assertEquals("s2", Live2DSpeechTimeline.position.value.segmentId)
        Live2DSpeechTimeline.interrupted(role, call)
        Live2DSpeechTimeline.progressed(role, call, "b", 900)
        assertEquals(0L, Live2DSpeechTimeline.position.value.elapsedMs)
    }

    @Test fun realSegmentEventsPreserveVisualDecisionIdentity() {
        val role = "wolf-" + UUID.randomUUID()
        val call = "call-" + UUID.randomUUID()
        Live2DSpeechTimeline.started(role, call, "part-1", 0, 42L)
        assertEquals(0, Live2DSpeechTimeline.events.value.segmentIndex)
        assertEquals(42L, Live2DSpeechTimeline.events.value.visualSequence)
        Live2DSpeechTimeline.finished(role, call, "part-1")
        Live2DSpeechTimeline.started(role, call, "part-2", 1, 42L)
        Live2DSpeechTimeline.finished(role, call, "part-1")
        assertEquals(1, Live2DSpeechTimeline.events.value.segmentIndex)
        assertEquals("part-2", Live2DSpeechTimeline.events.value.utteranceId)
        assertEquals(Live2DSpeechTimeline.Phase.Playing, Live2DSpeechTimeline.events.value.phase)
    }

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
