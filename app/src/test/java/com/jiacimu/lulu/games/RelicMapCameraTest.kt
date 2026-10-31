package com.jiacimu.lulu.games

import org.junit.Assert.*
import org.junit.Test

class RelicMapCameraTest {
    @Test fun spawnAndEdgesRemainInTheUnobstructedCenter() {
        for ((width, height) in listOf(800f to 360f, 360f to 800f)) {
            for (focus in listOf(2 to 2, 16 to 13, 9 to 7)) {
                val camera = relicMapCamera(width, height, 18, 15, true, focus)
                assertEquals(width / 2, camera.offsetX + (focus.first + .5f) * camera.tileSize, .001f)
                assertEquals(height / 2, camera.offsetY + (focus.second + .5f) * camera.tileSize, .001f)
                assertEquals(focus, camera.tileAt(width / 2, height / 2, 18, 15))
            }
        }
    }
    @Test fun touchingOutsideBoardDoesNotSelectTheBoundaryTile() {
        val camera = relicMapCamera(360f, 800f, 18, 15, true, 2 to 2)
        assertNull(camera.tileAt(camera.offsetX - .1f, camera.offsetY, 18, 15))
        assertNull(camera.tileAt(camera.offsetX, camera.offsetY - .1f, 18, 15))
        assertNull(camera.tileAt(camera.offsetX + 18 * camera.tileSize, camera.offsetY, 18, 15))
    }
    @Test fun overviewUsesTheSameTouchTransform() {
        val camera = relicMapCamera(360f, 300f, 18, 15, false, 2 to 2)
        assertEquals(20f, camera.tileSize, .001f)
        assertEquals(17 to 14, camera.tileAt(359f, 299f, 18, 15))
    }
}
