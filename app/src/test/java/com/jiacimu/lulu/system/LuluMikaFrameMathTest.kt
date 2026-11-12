/* Mika upstream regression tests (Apache-2.0, Copyright 2026 wangzan101).
 * Adapted to Lulu module names and available kotlinx.coroutines.runBlocking.
 */
package com.jiacimu.lulu.system

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class LuluMikaFrameMathTest {
    private val w = 360
    private val h = 800
    private val black = 0xff000000.toInt()
    private val white = 0xffffffff.toInt()

    @Test fun allBlackIsBlack() {
        assertTrue(LuluMikaFrameMath.isBlack(w, h) { _, _ -> black })
        assertTrue(LuluMikaFrameMath.isBlack(w, h) { _, _ -> 0xff070707.toInt() })
    }

    @Test fun darkThemeWithTextIsNotBlack() {
        // 黑底上有一行白字
        assertFalse(LuluMikaFrameMath.isBlack(w, h) { _, y -> if (y in 300..340) white else black })
        assertFalse(LuluMikaFrameMath.isBlack(w, h) { _, _ -> 0xff202020.toInt() })
    }

    @Test fun pageChangeDiffers() {
        val a = LuluMikaFrameMath.lumaGrid(w, h) { _, _ -> white }
        val b = LuluMikaFrameMath.lumaGrid(w, h) { _, y -> if (y < h / 2) black else white }
        assertTrue(LuluMikaFrameMath.differs(a, b))
    }

    @Test fun cursorBlinkDoesNotDiffer() {
        val a = LuluMikaFrameMath.lumaGrid(w, h) { _, _ -> white }
        // 2 像素宽的光标
        val b = LuluMikaFrameMath.lumaGrid(w, h) { x, y -> if (x in 100..101 && y in 200..240) black else white }
        assertFalse(LuluMikaFrameMath.differs(a, b))
        assertFalse(LuluMikaFrameMath.differs(a, null))
    }
}
