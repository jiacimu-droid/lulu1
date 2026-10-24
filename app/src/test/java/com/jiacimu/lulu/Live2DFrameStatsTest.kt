package com.jiacimu.lulu

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.assertNull
import org.junit.Test

class Live2DFrameStatsTest {
    @Test fun reportsReceivedFramesInsteadOfAssumingGpuTickerFps() {
        val stats = Live2DFrameStats(reportIntervalMs = 1_000)
        for (i in 0 until 20) {
            assertNull(stats.frame(i * 50L, 1024, 6, 1000, 1600, "高速"))
        }
        val result = stats.frame(1000, 1024, 6, 1000, 1600, "高速")!!
        assertTrue(result.fps in 20f..22f)
        assertEquals(50L, result.p95GapMs)
        assertEquals(6f, result.averageDecodeMs)
        assertEquals(1f, result.averageFrameKiB)
        assertTrue(result.label().contains("p95 50ms"))
        assertTrue(result.label().contains("1000×1600px"))
    }

    @Test fun smoothModePrioritizesStableFramesOverUpscalingAt25Fps() {
        // Observed on a real HyperOS native bridge: 25.4 FPS, p95 59ms.
        assertEquals(895, Live2DNativeFrameBudget.next(980, "smooth", 25.4f, 59L))
        assertEquals(800, Live2DNativeFrameBudget.next(800, "smooth", 25.4f, 59L))
        assertEquals(980, Live2DNativeFrameBudget.next(980, "smooth", 31f, 45L))
        assertEquals(1030, Live2DNativeFrameBudget.next(980, "smooth", 34f, 35L))
        assertEquals(1280, Live2DNativeFrameBudget.next(1280, "smooth", 36f, 34L))
    }

    @Test fun nativeResolutionHysteresisRespectsQualityMode() {
        assertEquals(1320, Live2DNativeFrameBudget.next(1320, "quality", 24f, 59L))
        assertEquals(1245, Live2DNativeFrameBudget.next(1320, "quality", 19f, 76L))
        assertEquals(1395, Live2DNativeFrameBudget.next(1320, "quality", 30f, 44L))
        assertEquals(1000, Live2DNativeFrameBudget.next(1000, "quality", 8f, 125L))
    }

    @Test fun detectsLongFrameGapsEvenWhenFramesStillArrive() {
        val stats = Live2DFrameStats(reportIntervalMs = 300)
        assertNull(stats.frame(0, 1000, 5, 500, 800, "兼容"))
        assertNull(stats.frame(50, 1000, 5, 500, 800, "兼容"))
        assertNull(stats.frame(100, 1000, 5, 500, 800, "兼容"))
        val result = stats.frame(300, 1000, 5, 500, 800, "兼容")!!
        // The p95 gap captures an obvious 200ms stall masked by the
        // simple fact that all three frames were eventually decoded.
        assertEquals(200L, result.p95GapMs)
        assertTrue(result.fps < 20)
    }
}
