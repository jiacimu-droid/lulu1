package com.jiacimu.lulu

import com.jiacimu.lulu.games.WorldRectangle
import com.jiacimu.lulu.games.WorldVector
import com.jiacimu.lulu.games.circleIntersects
import com.jiacimu.lulu.games.worldFollowPath
import org.junit.Assert.*
import org.junit.Test

class DigitalStreetCollisionTest {
    @Test fun solidBuildingHasApproachableDoorOnActualSidewalk() {
        val door = StreetDoor(DigitalStreetAddress("shared:cafe", "浮光咖啡角"),
            WorldVector(270f, 365f))
        assertTrue(circleIntersects(door.point, 34f, door.obstacle.bounds))
        assertFalse(circleIntersects(door.entrance, 34f, door.obstacle.bounds))
        val bounds = WorldRectangle(86f, 90f, 1_194f, 2_300f)
        val start = WorldVector(650f, 365f)
        val route = worldFollowPath(start, door.entrance, bounds,
            listOf(door.obstacle), 34f)
        assertTrue("Building must remain reachable without walking through its walls", route.isNotEmpty())
        assertEquals(door.entrance, route.last())
        (listOf(start) + route).zipWithNext().forEach { (from, to) ->
            (0..30).forEach { t ->
                val step = from + (to - from) * (t / 30f)
                assertFalse("Route went through building at $step",
                    circleIntersects(step, 34f, door.obstacle.bounds))
            }
        }
    }
}
