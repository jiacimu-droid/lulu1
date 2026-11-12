package com.jiacimu.lulu.system

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class LuluScreenFrameHandoffTest {
    private val frame = "data:image/jpeg;base64,YWJj"

    @Test fun roleAndRequestMustMatchAndConsumptionIsOneShot() {
        LuluScreenFrameHandoff.put("role-a", "reply-a", frame)
        assertNull(LuluScreenFrameHandoff.take("role-b", "reply-a"))
        assertNull(LuluScreenFrameHandoff.take("role-a", "reply-b"))
        assertEquals(frame, LuluScreenFrameHandoff.take("role-a", "reply-a"))
        assertNull(LuluScreenFrameHandoff.take("role-a", "reply-a"))
    }

    @Test fun abandonedFramesCannotGrowUnboundedAcrossRoles() {
        LuluScreenFrameHandoff.clear()
        for (i in 1..4) LuluScreenFrameHandoff.put("role-$i", "request-$i", frame)
        assertNull(LuluScreenFrameHandoff.take("role-1", "request-1"))
        for (i in 2..4) {
            assertEquals(frame, LuluScreenFrameHandoff.take("role-$i", "request-$i"))
        }
    }

    @Test fun lowMemoryEvictionDiscardsOldPixelsWithoutLeakingToNextTurn() {
        LuluScreenFrameHandoff.clear()
        LuluScreenFrameHandoff.put("role-a", "request-1", frame)
        LuluScreenFrameHandoff.clear()
        assertNull(LuluScreenFrameHandoff.take("role-a", "request-1"))
        LuluScreenFrameHandoff.put("role-a", "request-2", frame)
        assertEquals(frame, LuluScreenFrameHandoff.take("role-a", "request-2"))
    }

    @Test fun newerObservationReplacesEarlierUnconsumedFrame() {
        LuluScreenFrameHandoff.put("role-a", "reply-old", frame)
        LuluScreenFrameHandoff.put("role-a", "reply-new", frame)
        assertNull(LuluScreenFrameHandoff.take("role-a", "reply-old"))
        assertEquals(frame, LuluScreenFrameHandoff.take("role-a", "reply-new"))
        LuluScreenFrameHandoff.discard("role-a", "reply-new")
        assertNull(LuluScreenFrameHandoff.take("role-a", "reply-new"))
    }
}
