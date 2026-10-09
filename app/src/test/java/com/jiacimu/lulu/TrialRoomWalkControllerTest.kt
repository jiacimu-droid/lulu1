package com.jiacimu.lulu

import org.junit.Assert.*
import org.junit.Test

class TrialRoomWalkControllerTest {
    @Test fun walkingForwardChangesRealPositionAndStaysInsideWalls() {
        val c = TrialRoomWalkController()
        val before = c.z
        repeat(40) { c.move(strafe = 0f, forward = 1f, seconds = .016f) }
        assertTrue(c.z < before)
        repeat(500) { c.move(1f, 0f, .016f) }
        assertTrue(c.x in -3.38f..3.38f)
        assertTrue(c.z in -3f..3f)
    }

    @Test fun rotatingAndInspectingAreIndependentFromPersistentFurniture() {
        val c = TrialRoomWalkController()
        assertTrue(c.inspect().isNotBlank())
        c.look(300f, -100f)
        assertTrue(c.yaw > 0f)
        c.reset()
        assertEquals(0f, c.yaw)
        assertEquals(0f, c.x)
        assertEquals(2.7f, c.z)
    }

    @Test fun movementCannotEnterAvatarOrCoffeeTableSolidGeometry() {
        val c = TrialRoomWalkController()
        repeat(100) { c.move(0f, 1f, .016f) }
        val originZ = c.z
        repeat(10) { c.move(0f, 1f, .016f) }
        assertTrue(c.z <= originZ)
        assertTrue(c.z >= -3f)
    }
}
