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
        assertTrue(c.x in -7.05f..7.05f)
        assertTrue(c.z in -6.30f..6.30f)
    }

    @Test fun rotatingAndInspectingAreIndependentFromPersistentFurniture() {
        val c = TrialRoomWalkController()
        assertTrue(c.inspect().isNotBlank())
        c.look(300f, -100f)
        assertTrue(c.yaw > 0f)
        c.reset()
        assertEquals(0f, c.yaw, 0.0001f)
        assertEquals(0f, c.x, 0.0001f)
        assertEquals(5.5f, c.z, 0.0001f)
    }

    @Test fun movementCannotEnterActorEvenInsideExpandedRoom() {
        val c = TrialRoomWalkController()
        // Move to the avatar's x lane while remaining near the entrance.
        repeat(41) { c.move(-1f, 0f, .016f) }
        repeat(175) { c.move(0f, 1f, .016f) }
        val stopZ = c.z
        repeat(15) { c.move(0f, 1f, .016f) }
        assertEquals(stopZ, c.z, .001f)
        assertTrue(c.z >= .6f + .74f / 2f + .2f)
    }
}
