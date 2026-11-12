package com.jiacimu.lulu

import org.junit.Assert.assertEquals
import org.junit.Test

class QqChatImageViewerTest {
    @Test fun zoomedImageCanPanWithoutExposingEmptyEdges() {
        val result = nextQqPhotoTransform(
            QqPhotoTransform(scale = 2f), 1f, 600f, 400f, 9000f, -9000f,
            1200, 800, 2400, 1600,
        )
        assertEquals(2f, result.scale, 0.001f)
        assertEquals(600f, result.x, 0.001f)
        assertEquals(-400f, result.y, 0.001f)
    }

    @Test fun tallImageOnlyMovesOnScrollableAxis() {
        val result = nextQqPhotoTransform(
            QqPhotoTransform(), 2f, 250f, 500f, 300f, 800f,
            500, 1000, 200, 1000,
        )
        assertEquals(0f, result.x, 0.001f)
        assertEquals(500f, result.y, 0.001f)
    }

    @Test fun offCenterPinchPreservesGestureFocus() {
        val result = nextQqPhotoTransform(
            QqPhotoTransform(), 2f, 400f, 300f, 0f, 0f,
            1200, 800, 2400, 1600,
        )
        assertEquals(200f, result.x, 0.001f)
        assertEquals(100f, result.y, 0.001f)
    }

    @Test fun returningToFitResetsZoomAndPan() {
        val result = nextQqPhotoTransform(
            QqPhotoTransform(4f, 200f, -300f), 0.1f, 100f, 100f, 100f, 100f,
            1200, 800, 2400, 1600,
        )
        assertEquals(QqPhotoTransform(), result)
    }
}
