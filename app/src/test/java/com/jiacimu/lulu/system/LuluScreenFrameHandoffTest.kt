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

    @Test fun newerObservationReplacesEarlierUnconsumedFrame() {
        LuluScreenFrameHandoff.put("role-a", "reply-old", frame)
        LuluScreenFrameHandoff.put("role-a", "reply-new", frame)
        assertNull(LuluScreenFrameHandoff.take("role-a", "reply-old"))
        assertEquals(frame, LuluScreenFrameHandoff.take("role-a", "reply-new"))
        LuluScreenFrameHandoff.discard("role-a", "reply-new")
        assertNull(LuluScreenFrameHandoff.take("role-a", "reply-new"))
    }
}
