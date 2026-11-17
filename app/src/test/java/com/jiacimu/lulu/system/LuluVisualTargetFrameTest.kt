package com.jiacimu.lulu.system

import org.junit.Assert.*
import org.junit.Test

class LuluVisualTargetFrameTest {
    @Test fun minorCompressionNoiseKeepsTheSameTarget() {
        val button = IntArray(64) { if (it % 8 in 2..5 && it / 8 in 2..5) 35 else 235 }
        val compressed = button.mapIndexed { i, v -> v + (i % 3 - 1) * 3 }.toIntArray()
        assertTrue(LuluVisualTargetFrame.matches(LuluVisualTargetFrame.signature(button),
            LuluVisualTargetFrame.signature(compressed)))
    }

    @Test fun blankBlackAndWhiteTargetsAreDifferentDespiteIdenticalAverageHashes() {
        val black = LuluVisualTargetFrame.signature(IntArray(64) { 0 })
        val white = LuluVisualTargetFrame.signature(IntArray(64) { 255 })
        assertEquals(black?.hash, white?.hash)
        assertFalse(LuluVisualTargetFrame.matches(black, white))
    }

    @Test fun movedOrMissingControlCannotReuseOldVisualAuthority() {
        val first = LuluVisualTargetFrame.signature(IntArray(64) { if (it % 8 < 4) 20 else 230 })
        val moved = LuluVisualTargetFrame.signature(IntArray(64) { if (it % 8 >= 4) 20 else 230 })
        assertFalse(LuluVisualTargetFrame.matches(first, moved))
        assertFalse(LuluVisualTargetFrame.matches(first, null))
        assertNull(LuluVisualTargetFrame.signature(IntArray(63)))
    }
}
