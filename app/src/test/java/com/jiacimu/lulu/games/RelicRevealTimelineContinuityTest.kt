package com.jiacimu.lulu.games

import org.junit.Assert.*
import org.junit.Test

/** Guard the actual chest-to-jewel reveal against blank frames and visual regressions. */
class RelicRevealTimelineContinuityTest {
    @Test fun chestRemainsVisibleUntilPhysicalJewelStartsRevealing() {
        var previous = RelicRevealTimeline.at(0f)
        for (step in 1..100) {
            val frame = RelicRevealTimeline.at(step / 100f)
            assertTrue("lid must never close during reveal at $step", frame.lidOpen >= previous.lidOpen)
            assertTrue("jewel must never reverse at $step", frame.jewelReveal >= previous.jewelReveal)
            assertTrue("hinged lid must never swing shut at $step", frame.lidAngleDegrees <= previous.lidAngleDegrees)
            assertEquals("lid hinge follows the committed reveal clock at $step", -58f * frame.lidOpen, frame.lidAngleDegrees, 0.0001f)
            assertTrue("chest must not reappear at $step", frame.chestAlpha <= previous.chestAlpha)
            assertTrue("jewel must not shrink at $step",
                frame.jewelDiameterFraction >= previous.jewelDiameterFraction)
            assertTrue("the scene must never lose both chest and jewel at $step",
                frame.chestAlpha > 0f || frame.jewelReveal > 0f)
            previous = frame
        }
        assertEquals(-58f, RelicRevealTimeline.at(1f).lidAngleDegrees, 0.0001f)
        assertEquals(0f, RelicRevealTimeline.at(1f).chestAlpha, 0.0001f)
        assertEquals(1f, RelicRevealTimeline.at(1f).jewelReveal, 0.0001f)
    }
}
