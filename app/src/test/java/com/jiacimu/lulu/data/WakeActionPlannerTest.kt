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

    @Test fun chosenAcousticBurstCountAndDurationsAreBounded() {
        val many = WakeActionPlanner.parseChoice(
            """{"cue":"ring","ringCount":99,"ringSeconds":99,"pauseSeconds":0}""", true, true)
        assertEquals(WakeActionPlanner.Cue.RING, many.cue)
        assertEquals(4, many.ringCount)
        assertEquals(10, many.ringSeconds)
        assertEquals(1, many.pauseSeconds)
        val specific = WakeActionPlanner.parseChoice(
            """{"cue":"ring","ringCount":3,"ringSeconds":4,"pauseSeconds":3}""", true, true)
        assertEquals(3, specific.ringCount)
        assertEquals(4, specific.ringSeconds)
        assertEquals(3, specific.pauseSeconds)
        assertEquals(2, WakeActionPlanner.parseChoice("oops", true, true).ringCount)
        assertEquals(WakeActionPlanner.Cue.RING, WakeActionPlanner.parseChoice(
            """{"cue":"music","ringCount":3}""", false, true).cue)
    }

    @Test fun invalidVolumeIsNotCarriedIntoExecutor() {
        val raw = """{"cue":"music","volumePercent":200}"""
        assertNull(WakeActionPlanner.parseChoice(raw, true, true).volumePercent)
    }
}
