package com.jiacimu.lulu.system

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class LuluVirtualHumanGateTest {
    private fun c(name: String, role: String, id: String = "", clickable: Boolean = true) =
        LuluVirtualHumanGate.Control(name, role, id, clickable, false)

    @Test fun readingPostsAboutPaymentAndVerificationDoesNotBlockBrowsing() {
        val controls = listOf(
            c("如何用验证码保护账号及避免盗号", "FrameLayout", "note_card"),
            c("确认支付前应该检查什么", "FrameLayout", "post_content"),
            c("获取验证码的常见问题", "TextView", "article_title"),
            c("搜索", "ImageButton", "search_icon"),
        )
        assertTrue(LuluVirtualHumanGate.securityLabels(controls).isEmpty())
    }

    @Test fun realVerificationAndPaymentButtonsAreDetected() {
        val controls = listOf(
            c("获取验证码", "Button", "get_sms"),
            c("確認支付", "Button", "pay_action"),
            c("向右滑动完成安全验证", "FrameLayout", "captcha_slider"),
        )
        val labels = LuluVirtualHumanGate.securityLabels(controls)
        assertEquals(3, labels.size)
        val diagnosis = LuluMikaScreenDiagnosis.classify(
            LuluMikaScreenFacts("com.example", true, 3, false, emptyList(),
                labels, false))
        assertTrue(diagnosis.needsHuman)
    }

    @Test fun passwordFieldNeedsHumanEvenWithNoReadableText() {
        val diagnosis = LuluMikaScreenDiagnosis.classify(
            LuluMikaScreenFacts("com.example", true, 0, false, emptyList(),
                emptyList(), true))
        assertTrue(diagnosis.needsHuman)
        assertEquals("login", diagnosis.takeoverReason)
        assertFalse(LuluVirtualHumanGate.securityLabels(
            listOf(c("请先阅读验证码安全说明", "FrameLayout", "post_card"))).isNotEmpty())
    }
}
