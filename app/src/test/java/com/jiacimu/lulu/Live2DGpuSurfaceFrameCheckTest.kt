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

    @Test fun pastelRoomBackdropAloneDoesNotPassAsRenderedCharacter() {
        val roomColors = listOf(
            0xFFE9EDF4.toInt(), 0xFFDFE8F2.toInt(), 0xFFD3E2EE.toInt(),
            0xFFEAF1F8.toInt(), 0xFFD8E2EB.toInt(), 0xFFEFF3F8.toInt(),
        )
        roomColors.forEach { color ->
            assertTrue(Live2DGpuSurfaceFrameCheck.isBackdropPixel(color))
            assertFalse(Live2DGpuSurfaceFrameCheck.hasCharacterPixels(IntArray(96 * 160) { color }))
        }
        val mixedRoom = IntArray(96 * 160) { index -> roomColors[index % roomColors.size] }
        assertFalse(Live2DGpuSurfaceFrameCheck.hasCharacterPixels(mixedRoom))
    }

    @Test fun actualCharacterPixelsStillPassOnPastelBackdrop() {
        val scene = IntArray(96 * 160) { 0xFFE9EDF4.toInt() }
        repeat(1700) { scene[it + 1500] = 0xFF82648A.toInt() }
        assertTrue(Live2DGpuSurfaceFrameCheck.hasCharacterPixels(scene))
        // A handful of title/text antialias pixels is not evidence of a model.
        val withoutAvatar = IntArray(96 * 160) { 0xFFE9EDF4.toInt() }
        repeat(90) { withoutAvatar[it] = 0xFF34465F.toInt() }
        assertFalse(Live2DGpuSurfaceFrameCheck.hasCharacterPixels(withoutAvatar))
    }

    @Test fun actualPortraitPixelsPassWithoutRequiringColoredCostume() {
        val mostlyDark = IntArray(96 * 160) { 0xFF151719.toInt() }
        for (i in 0 until 1250) mostlyDark[i] = 0xFF8A8A8A.toInt()
        assertTrue(Live2DGpuSurfaceFrameCheck.hasCharacterPixels(mostlyDark))
    }
}
