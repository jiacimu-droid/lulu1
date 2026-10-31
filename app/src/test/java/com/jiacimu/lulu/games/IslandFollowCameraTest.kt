package com.jiacimu.lulu.games

import org.junit.Assert.*
import org.junit.Test

class IslandFollowCameraTest {
    @Test fun actorStaysCenteredAtSpawnAndAllWorldEdges() {
        for ((width, height, columns, rows) in listOf(
            listOf(800f, 360f, 18f, 7f),
            listOf(360f, 800f, 14f, 26f),
        )) {
            for ((x, y) in listOf(0f to 0f, 4f to 4f, 27f to 21f)) {
                val camera = islandFollowCamera(width, height, columns.toInt(), rows.toInt(), x, y, 28, 22)
                assertEquals(width / 2, camera.originX + (x + .5f) * camera.tileSize, .001f)
                assertEquals(height / 2, camera.originY + (y + .5f) * camera.tileSize, .001f)
                assertEquals(x.toInt() to y.toInt(), camera.tileAt(width / 2, height / 2, 28, 22))
            }
        }
    }

    @Test fun mapScrollsInTheOppositeDirectionWithNoCameraJump() {
        val before = islandFollowCamera(800f, 360f, 18, 7, 4f, 4f, 28, 22)
        val half = islandFollowCamera(800f, 360f, 18, 7, 4.5f, 4f, 28, 22)
        val after = islandFollowCamera(800f, 360f, 18, 7, 5f, 4f, 28, 22)
        assertEquals(-before.tileSize * .5f, half.originX - before.originX, .001f)
        assertEquals(-before.tileSize * .5f, after.originX - half.originX, .001f)
        assertEquals(before.originY, after.originY, .001f)
    }

    @Test fun selectingTilesUsesExactlyTheDrawnTransform() {
        val camera = islandFollowCamera(360f, 800f, 14, 26, 4.35f, 4.7f, 28, 22)
        val x = 7
        val y = 9
        assertEquals(x to y, camera.tileAt(
            camera.originX + (x + .5f) * camera.tileSize,
            camera.originY + (y + .5f) * camera.tileSize,
            28, 22,
        ))
        assertNull(camera.tileAt(camera.originX - .1f, camera.originY, 28, 22))
        assertNull(camera.tileAt(camera.originX, camera.originY - .1f, 28, 22))
    }

    @Test fun visibleRangesIncludePartiallyExposedCellsOnlyWithinTheWorld() {
        val camera = islandFollowCamera(800f, 360f, 18, 7, 27f, 21f, 28, 22)
        assertTrue(27 in camera.firstColumn until camera.endColumnExclusive)
        assertTrue(21 in camera.firstRow until camera.endRowExclusive)
        assertTrue(camera.firstColumn >= 0 && camera.firstRow >= 0)
        assertTrue(camera.endColumnExclusive <= 28 && camera.endRowExclusive <= 22)
    }
}
