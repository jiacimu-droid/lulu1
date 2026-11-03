package com.jiacimu.lulu.games

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** A held finger must steer without creating a new tap for each tile. */
class GameJoystickDirectionTest {
    @Test fun centerIsDeadZoneNotMovement() {
        assertNull(gameJoystickDirection(69f, 69f, 138f, 138f))
        assertNull(gameJoystickDirection(73f, 73f, 138f, 138f))
        assertNull(gameJoystickDirection(69f, 69f, 0f, 138f))
    }

    @Test fun sameContactCanDragThroughFourDirections() {
        val positions = listOf(
            69f to 10f,
            130f to 69f,
            69f to 133f,
            5f to 69f,
        )
        assertEquals(listOf(0 to -1, 1 to 0, 0 to 1, -1 to 0),
            positions.map { (x,y) -> gameJoystickDirection(x, y, 138f, 138f) })
    }

    @Test fun diagonalThumbMovementUsesDominantAxis() {
        assertEquals(0 to -1, gameJoystickDirection(85f, 3f, 138f, 138f))
        assertEquals(1 to 0, gameJoystickDirection(135f, 90f, 138f, 138f))
        assertEquals(-1 to 0, gameJoystickDirection(0f, 40f, 138f, 138f))
        assertEquals(0 to 1, gameJoystickDirection(60f, 140f, 138f, 138f))
    }

    @Test fun offPadDragKeepsDirectionUntilFingerIsReleased() {
        assertEquals(0 to 1, gameJoystickDirection(69f, 220f, 138f, 138f))
        assertEquals(-1 to 0, gameJoystickDirection(-80f, 66f, 138f, 138f))
    }
}
