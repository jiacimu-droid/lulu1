package com.jiacimu.lulu

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class RainyDioramaOrbitTest {
    @Test fun draggingRotatesFullModelAndClampsVerticalAngle() {
        val camera = RainyDioramaOrbit()
        val initial = camera.azimuth
        camera.drag(200f, 100000f, 400f)
        assertTrue(camera.azimuth > initial)
        assertEquals(1.13f, camera.elevation, .0001f)
        camera.drag(-120f, -100000f, 400f)
        assertEquals(.16f, camera.elevation, .0001f)
    }

    @Test fun pinchRespectsNearFarLimitsAndRejectsBadScale() {
        val camera = RainyDioramaOrbit()
        camera.pinch(500f)
        assertEquals(10f, camera.radius, .0001f)
        camera.pinch(.001f)
        assertEquals(10f, camera.radius, .0001f)
        camera.pinch(.02f)
        assertEquals(27f, camera.radius, .0001f)
        camera.pinch(Float.NaN)
        assertEquals(27f, camera.radius, .0001f)
    }

    @Test fun tinyScreensDoNotCauseCameraInfinity() {
        val camera = RainyDioramaOrbit()
        camera.drag(35f, -40f, 0f)
        assertTrue(camera.azimuth.isFinite())
        assertTrue(camera.elevation.isFinite())
    }
}
