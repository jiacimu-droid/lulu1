package com.jiacimu.lulu

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class Live2DGpuSurfaceFrameCheckTest {
    @Test fun highRefreshUsesBestSameResolutionMode() {
        assertTrue(Live2DHighRefreshPolicy.choose(60f, listOf(60f, 90f, 120f)) == 120f)
        assertTrue(Live2DHighRefreshPolicy.choose(60f, listOf(60f)) == 60f)
        assertTrue(Live2DHighRefreshPolicy.choose(120f, emptyList()) == 120f)
        assertTrue(Live2DHighRefreshPolicy.choose(Float.NaN, listOf(90f)) == 90f)
        assertTrue(Live2DHighRefreshPolicy.choose(60f, listOf(Float.NaN, -1f)) == 60f)
    }

    @Test fun blackAndDarkStageNeverClaimWorkingGpu() {
        val dark = IntArray(96 * 160) { 0xFF151719.toInt() }
        assertFalse(Live2DGpuSurfaceFrameCheck.hasCharacterPixels(dark))
        assertFalse(Live2DGpuSurfaceFrameCheck.hasCharacterPixels(IntArray(0)))
        val specks = dark.copyOf()
        for (i in 0 until 120) specks[i] = 0xFFFFFFFF.toInt()
        assertFalse(Live2DGpuSurfaceFrameCheck.hasCharacterPixels(specks))
    }

    @Test fun actualPortraitPixelsPassWithoutRequiringColoredCostume() {
        val mostlyDark = IntArray(96 * 160) { 0xFF151719.toInt() }
        for (i in 0 until 1250) mostlyDark[i] = 0xFF8A8A8A.toInt()
        assertTrue(Live2DGpuSurfaceFrameCheck.hasCharacterPixels(mostlyDark))
    }
}
