package com.jiacimu.lulu

import org.junit.Assert.assertEquals
import org.junit.Test

/** Screenshot presentation must preserve a centred 1x phone capture. */
class ScreenRecordViewerGeometryTest {
    @Test fun unzoomedCaptureIsAlwaysCentredEvenAfterPriorDrag() {
        assertEquals(0f, clampScreenRecordPan(280f, 412, 1f), 0.001f)
        assertEquals(0f, clampScreenRecordPan(-90f, 360, 0.9f), 0.001f)
    }

    @Test fun zoomedCaptureCanPanButCannotLeaveTheViewport() {
        // A 360dp phone at 3x permits up to 360dp displacement.
        assertEquals(360f, clampScreenRecordPan(900f, 360, 3f), 0.001f)
        assertEquals(-360f, clampScreenRecordPan(-900f, 360, 3f), 0.001f)
        assertEquals(64f, clampScreenRecordPan(64f, 360, 3f), 0.001f)
    }

    @Test fun geometryNotReadyCannotRetainStalePan() {
        assertEquals(0f, clampScreenRecordPan(35f, 0, 2.5f), 0.001f)
        assertEquals(103f, clampScreenRecordPan(500f, 412, 1.5f), 0.001f)
    }
}
