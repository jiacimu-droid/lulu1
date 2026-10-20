package com.jiacimu.lulu.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class WakeActionPlannerTest {
    @Test fun malformedModelAnswerStillUsesAudibleFallback() {
        assertEquals(WakeActionPlanner.Cue.RING,
            WakeActionPlanner.parseChoice("not json", true, true).cue)
    }

    @Test fun musicRequiresPermissionEvenIfModelWantsIt() {
        val raw = """{"cue":"music","reason":"想让她精神一点","volumePercent":42}"""
        assertEquals(WakeActionPlanner.Cue.RING,
            WakeActionPlanner.parseChoice(raw, false, true).cue)
        assertEquals(WakeActionPlanner.Cue.MUSIC,
            WakeActionPlanner.parseChoice(raw, true, true).cue)
    }

    @Test fun speechWithoutLiveCallMustNotSilenceTheAlarm() {
        val raw = """{"cue":"speak","openingLine":"该起床啦"}"""
        assertEquals(WakeActionPlanner.Cue.RING,
            WakeActionPlanner.parseChoice(raw, true, false).cue)
        assertEquals(WakeActionPlanner.Cue.SPEAK,
            WakeActionPlanner.parseChoice(raw, true, true).cue)
    }

    @Test fun invalidVolumeIsNotCarriedIntoExecutor() {
        val raw = """{"cue":"music","volumePercent":200}"""
        assertNull(WakeActionPlanner.parseChoice(raw, true, true).volumePercent)
    }
}
