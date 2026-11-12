/* Ported from Mika (Apache-2.0), Copyright 2026 wangzan101.
 * Changes: Lulu package/types and local Hant implementation.
 */
package com.jiacimu.lulu.system

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class LuluMikaScreenDiagnosisTest {
    private fun facts(
        hasWindow: Boolean = true, readable: Int = 10, changing: Boolean = false, black: List<Boolean> = listOf(false, false),
        texts: List<String> = listOf("设置", "WLAN"), password: Boolean = false,
    ) = LuluMikaScreenFacts("com.x", hasWindow, readable, changing, black, texts, password)

    @Test fun normalScreen() {
        val c = LuluMikaScreenDiagnosis.classify(facts())
        assertEquals(LuluMikaScreenState.NORMAL, c.state); assertFalse(c.needsHuman)
    }

    @Test fun blankWithoutWindowOrElements() {
        assertEquals(LuluMikaScreenState.BLANK, LuluMikaScreenDiagnosis.classify(facts(hasWindow = false, readable = 0, black = emptyList())).state)
        val c = LuluMikaScreenDiagnosis.classify(facts(readable = 0, texts = emptyList()))
        assertEquals(LuluMikaScreenState.BLANK, c.state); assertFalse(c.needsHuman)
    }

    @Test fun unreadableWindowIsNotNoWindow() {
        val none = LuluMikaScreenFacts(null, false, 0, false, emptyList(), emptyList(), false, anyWindow = false)
        assertEquals(LuluMikaScreenDiagnosis.NO_WINDOW, LuluMikaScreenDiagnosis.classify(none).cause)
        assertEquals(LuluMikaScreenDiagnosis.UNREADABLE_WINDOW, LuluMikaScreenDiagnosis.classify(none.copy(anyWindow = true)).cause)
    }

    @Test fun persistentBlackFramesAreUnavailableWithoutClaimingSecureWindow() {
        val readable = LuluMikaScreenDiagnosis.classify(facts(black = listOf(true, true)))
        assertEquals(LuluMikaScreenState.CAPTURE_UNAVAILABLE, readable.state)
        assertFalse("readable controls remain a usable fallback", readable.needsHuman)
        assertTrue(readable.cause.orEmpty().contains("cannot be distinguished"))

        val empty = LuluMikaScreenDiagnosis.classify(facts(readable = 0, black = listOf(true, true), texts = emptyList()))
        assertEquals(LuluMikaScreenState.CAPTURE_UNAVAILABLE, empty.state)
        assertTrue(empty.needsHuman)
        assertEquals(LuluMikaScreenDiagnosis.DISPLAY_CAPTURE, empty.takeoverReason)
        assertEquals("payment", LuluMikaScreenDiagnosis.classify(facts(black = listOf(true, true), texts = listOf("确认支付"))).takeoverReason)
        assertEquals("captcha", LuluMikaScreenDiagnosis.classify(facts(black = listOf(true, true), texts = listOf("安全验证"))).takeoverReason)
        assertEquals("login", LuluMikaScreenDiagnosis.classify(facts(black = listOf(true, true), password = true)).takeoverReason)
        // 没有窗口时的黑屏只是空白
        assertEquals(LuluMikaScreenState.BLANK, LuluMikaScreenDiagnosis.classify(facts(hasWindow = false, readable = 0, black = listOf(true, true))).state)
        // 根节点不可读但系统仍有 App 窗口/前台任务时,同样不能退回「没有 App」或不断重试。
        val unreadable = LuluMikaScreenFacts(null, false, 0, false, listOf(true, true), emptyList(), false,
            anyWindow = true, foregroundApp = "com.x")
        val unavailable = LuluMikaScreenDiagnosis.classify(unreadable)
        assertEquals(LuluMikaScreenState.CAPTURE_UNAVAILABLE, unavailable.state)
        assertEquals("com.x", unavailable.currentApp)
        assertTrue(unavailable.needsHuman)
    }

    @Test fun oneBlackFrameIsNotPersistentCaptureFailure() {
        assertEquals(LuluMikaScreenState.NORMAL, LuluMikaScreenDiagnosis.classify(facts(black = listOf(true))).state)
        assertEquals(LuluMikaScreenState.NORMAL, LuluMikaScreenDiagnosis.classify(facts(black = listOf(false, true))).state)
    }

    @Test fun blackWhileChangingIsLoading() {
        val c = LuluMikaScreenDiagnosis.classify(facts(changing = true, black = listOf(true, true)))
        assertEquals(LuluMikaScreenState.LOADING, c.state); assertFalse(c.needsHuman)
    }

    @Test fun animationWithReadableContentIsUsable() {
        val c = LuluMikaScreenDiagnosis.classify(facts(changing = true))
        assertEquals(LuluMikaScreenState.NORMAL, c.state); assertFalse(c.needsHuman)
    }

    @Test fun changingWithoutReadableContentStillLoads() {
        assertEquals(LuluMikaScreenState.LOADING, LuluMikaScreenDiagnosis.classify(facts(changing = true, readable = 0)).state)
        assertEquals(LuluMikaScreenState.LOADING, LuluMikaScreenDiagnosis.classify(facts(changing = true, hasWindow = false)).state)
    }

    @Test fun animationDoesNotBypassHumanOnlyPages() {
        assertEquals(LuluMikaScreenState.NEEDS_HUMAN, LuluMikaScreenDiagnosis.classify(facts(changing = true, password = true)).state)
    }

    @Test fun humanOnlyPages() {
        assertEquals("captcha", LuluMikaScreenDiagnosis.classify(facts(texts = listOf("请拖动滑块完成安全验证"))).takeoverReason)
        assertEquals("payment", LuluMikaScreenDiagnosis.classify(facts(texts = listOf("请输入支付密码"))).takeoverReason)
        assertEquals("login", LuluMikaScreenDiagnosis.classify(facts(password = true)).takeoverReason)
        assertEquals("login", LuluMikaScreenDiagnosis.classify(facts(texts = listOf("手机号", "获取验证码"))).takeoverReason)
        assertTrue(LuluMikaScreenDiagnosis.classify(facts(texts = listOf("Verify you are human"))).needsHuman)
        // 普通设置页里的「密码」字样不算
        assertFalse(LuluMikaScreenDiagnosis.classify(facts(texts = listOf("密码与安全", "隐私"))).needsHuman)
    }
}
