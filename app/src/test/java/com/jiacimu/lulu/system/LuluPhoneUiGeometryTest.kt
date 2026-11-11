package com.jiacimu.lulu.system

import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

class LuluPhoneUiGeometryTest {
    @Test fun normalizedCoordinatesRemainOnScreen() {
        assertEquals(0f, LuluPhoneUiGeometry.pixel(0, 1080))
        assertEquals(1079f, LuluPhoneUiGeometry.pixel(1000, 1080))
        assertEquals(539f, LuluPhoneUiGeometry.pixel(500, 1080))
    }

    @Test fun coordinatesRejectOutOfBounds() {
        assertThrows(IllegalArgumentException::class.java) { LuluPhoneUiGeometry.pixel(-1, 1080) }
        assertThrows(IllegalArgumentException::class.java) { LuluPhoneUiGeometry.pixel(1001, 1080) }
        assertThrows(IllegalArgumentException::class.java) { LuluPhoneUiGeometry.pixel(500, 0) }
    }

    @Test fun swipeDurationIsBounded() {
        assertEquals(400L, LuluPhoneUiGeometry.duration(400))
        assertThrows(IllegalArgumentException::class.java) { LuluPhoneUiGeometry.duration(50) }
        assertThrows(IllegalArgumentException::class.java) { LuluPhoneUiGeometry.duration(5000) }
    }
}
