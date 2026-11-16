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

    @Test fun fractionalTrackingRemainsCenteredAndTouchMatchesRender() {
        val camera = relicMapCamera(800f, 360f, 18, 15, true, 4.25f, 5.75f)
        val x = camera.offsetX + (4.25f + .5f) * camera.tileSize
        val y = camera.offsetY + (5.75f + .5f) * camera.tileSize
        assertEquals(400f, x, .001f)
        assertEquals(180f, y, .001f)
        assertEquals(4 to 6, camera.tileAt(400f, 180f, 18, 15))
    }
    @Test fun pinchZoomAndDragUseTheSameCameraForSelectionAndDrawing() {
        val camera = relicMapCamera(800f, 360f, 18, 15, true,
            4.25f, 5.75f, zoom = 1.6f, panX = 100f, panY = -44f)
        assertEquals(500f, camera.offsetX + (4.25f + .5f) * camera.tileSize, .001f)
        assertEquals(136f, camera.offsetY + (5.75f + .5f) * camera.tileSize, .001f)
        assertEquals(4 to 6, camera.tileAt(500f, 136f, 18, 15))
        assertEquals(4 to 6, camera.tileAt(
            camera.offsetX + 4.5f * camera.tileSize,
            camera.offsetY + 6.5f * camera.tileSize, 18, 15))
        // Zoom and pan never turn an outside-board tap into a legal tile.
        assertNull(camera.tileAt(camera.offsetX - 1f, camera.offsetY, 18, 15))
    }

    @Test fun finiteExtremeCoordinatesAndTinyTilesNeverSaturateIntoRealTiles() {
        val camera = RelicMapCamera(Float.MIN_VALUE, 0f, 0f)
        assertNull(camera.tileAt(Float.MAX_VALUE, 0f, Int.MAX_VALUE, 15))
        assertNull(camera.tileAt(0f, Float.MAX_VALUE, 18, Int.MAX_VALUE))
        assertNull(camera.tileAt(-Float.MAX_VALUE, 0f, 18, 15))
        // A real coordinate still resolves correctly after double-precision projection.
        assertEquals(0 to 0, RelicMapCamera(20f, 0f, 0f).tileAt(10f, 10f, 18, 15))
    }

    @Test fun invalidViewportAndGestureCannotCreateSelectableTiles() {
        val invalid = listOf(
            relicMapCamera(0f, 360f, 18, 15, true, 4 to 5),
            relicMapCamera(800f, 0f, 18, 15, true, 4 to 5),
            relicMapCamera(800f, 360f, 18, 0, true, 4 to 5),
            relicMapCamera(800f, 360f, 0, 15, false, 4 to 5),
            relicMapCamera(Float.NaN, 360f, 18, 15, true, 4 to 5),
            relicMapCamera(800f, 360f, 18, 15, true, Float.NaN, 5f),
            relicMapCamera(800f, 360f, 18, 15, true, 4f, 5f, panX = Float.NaN)
        )
        invalid.forEach { camera ->
            assertEquals(0f, camera.tileSize, 0f)
            assertNull(camera.tileAt(400f, 180f, 18, 15))
        }
        // Invalid pinch zoom must not make an otherwise valid map untouchable.
        val recovered = relicMapCamera(800f, 360f, 18, 15, true,
            4f, 5f, zoom = Float.NaN)
        assertTrue(recovered.tileSize > 0f)
        assertEquals(4 to 5, recovered.tileAt(400f, 180f, 18, 15))
    }

    @Test fun invalidGestureCoordinatesNeverSelectARealMapTile() {
        val camera = relicMapCamera(800f, 360f, 18, 15, true, 4 to 5)
        for (bad in listOf(Float.NaN, Float.POSITIVE_INFINITY, Float.NEGATIVE_INFINITY)) {
            assertNull(camera.tileAt(bad, 180f, 18, 15))
            assertNull(camera.tileAt(400f, bad, 18, 15))
        }
        assertNull(RelicMapCamera(0f, 0f, 0f).tileAt(0f, 0f, 18, 15))
        assertNull(RelicMapCamera(Float.NaN, 0f, 0f).tileAt(0f, 0f, 18, 15))
        assertNull(RelicMapCamera(20f, Float.NaN, 0f).tileAt(0f, 0f, 18, 15))
        assertEquals(4 to 5, camera.tileAt(400f, 180f, 18, 15))
    }


    @Test fun visibleBoundsExcludeFarPannedWorldAndPreserveEdgeOverscan() {
        val normal = RelicMapCamera(20f, -20f, -20f)
        assertArrayEquals(intArrayOf(0, 7, 0, 6), normal.visibleBounds(100f, 80f, 18, 15))
        // The old Float.toInt saturation could clamp a far-offscreen world to
        // an edge strip, wasting per-frame drawing on a nonvisible board.
        val farRight = RelicMapCamera(20f, -Float.MAX_VALUE, 0f)
        assertArrayEquals(intArrayOf(18, 18, 0, 5), farRight.visibleBounds(100f, 80f, 18, 15))
        val farLeft = RelicMapCamera(20f, Float.MAX_VALUE, 0f)
        assertArrayEquals(intArrayOf(0, 0, 0, 5), farLeft.visibleBounds(100f, 80f, 18, 15))
        assertArrayEquals(intArrayOf(0, 0, 0, 0),
            RelicMapCamera(0f, 0f, 0f).visibleBounds(100f, 80f, 18, 15))
        assertArrayEquals(intArrayOf(0, 0, 0, 0),
            normal.visibleBounds(0f, 80f, 18, 15))
    }

}
