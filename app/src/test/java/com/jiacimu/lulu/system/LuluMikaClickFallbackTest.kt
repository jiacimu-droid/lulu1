/* Mika upstream regression tests (Apache-2.0, Copyright 2026 wangzan101).
 * Adapted to Lulu module names and available kotlinx.coroutines.runBlocking.
 */
package com.jiacimu.lulu.system

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class LuluMikaClickFallbackTest {
    private val w = 1080
    private val h = 2400

    @Test fun picksSmallestClickableTargetContainingPoint() {
        val card = LuluMikaClickFallback.Candidate("card", 0, 200, 1080, 600, label = "Repository card")
        val star = LuluMikaClickFallback.Candidate("star", 900, 240, 1040, 360, label = "Star")
        assertEquals("star", LuluMikaClickFallback.pickTarget(960, 300, w, h, listOf(card, star)))
    }

    @Test fun rejectsLargeContainerScrollableAndWebView() {
        val fullScreen = LuluMikaClickFallback.Candidate("root", 0, 0, 1080, 2200, label = "Container")
        val tallPane = LuluMikaClickFallback.Candidate("tall", 0, 0, 500, 1300, label = "HalfHeight")
        val list = LuluMikaClickFallback.Candidate("list", 0, 200, 1080, 500, scrollable = true)
        val web = LuluMikaClickFallback.Candidate("web", 0, 200, 1080, 500, webView = true)
        assertNull(LuluMikaClickFallback.pickTarget(200, 300, w, h, listOf(fullScreen, tallPane, list, web)))
    }

    @Test fun outwardButtonsAreNotRetriedOnSecondChannel() {
        assertTrue(LuluMikaClickFallback.mayBeOutward("发送", null))
        assertTrue(LuluMikaClickFallback.mayBeOutward("立即支付", null))
        assertTrue(LuluMikaClickFallback.mayBeOutward("删除", null))
        assertTrue(LuluMikaClickFallback.mayBeOutward("确认", null))
        assertTrue(LuluMikaClickFallback.mayBeOutward("Submit", null))
        assertTrue(LuluMikaClickFallback.mayBeOutward(null, "com.app:id/btn_send"))
        assertTrue(LuluMikaClickFallback.mayBeOutward(null, "com.app:id/confirmButton"))

        assertFalse(LuluMikaClickFallback.mayBeOutward("搜索", "com.github.android:id/search"))
        assertFalse(LuluMikaClickFallback.mayBeOutward("Repositories", null))
        assertFalse(LuluMikaClickFallback.mayBeOutward("设置", "com.app:id/settings_tab"))

        // 可点控件的名字长也照样认:点之前的不可逆判定不看总长,没名字的容器拿的是子节点的字连起来的
        assertTrue(LuluMikaClickFallback.mayBeOutward("Send message to Alice Johnson", null))
        assertTrue(LuluMikaClickFallback.mayBeOutward("立即支付 ¥1,299.00 (含运费¥12.00)", null))
        // 不可点的长正文不拦;开关词按包含比,长的不查
        assertFalse(LuluMikaClickFallback.mayBeOutward("Post-processing runs on every frame before it is shown", null, clickable = false))
        assertFalse(LuluMikaClickFallback.mayBeOutward("关注我们的公众号,每周推送最新的开源项目和工具教程", null))

        val sendBtn = LuluMikaClickFallback.Candidate("send", 800, 2100, 1040, 2220, label = "发送")
        assertNull(LuluMikaClickFallback.pickTarget(900, 2160, w, h, listOf(sendBtn)))
    }

    /** 开关类按钮点第二次会把刚打开的状态关回去(自绘图标在画面上又看不出变化),只能靠名字拦。 */
    @Test fun statefulTogglesAreNotRetriedOnSecondChannel() {
        assertTrue(LuluMikaClickFallback.mayBeOutward("收藏", null))
        assertTrue(LuluMikaClickFallback.mayBeOutward("已关注", null))
        assertTrue(LuluMikaClickFallback.mayBeOutward("点赞(12)", null))
        assertTrue(LuluMikaClickFallback.mayBeOutward("静音", null))
        assertTrue(LuluMikaClickFallback.mayBeOutward("接听", null))
        assertTrue(LuluMikaClickFallback.mayBeOutward("Follow", null))
        assertTrue(LuluMikaClickFallback.mayBeOutward(null, "com.app:id/btn_follow"))
        assertTrue(LuluMikaClickFallback.mayBeOutward(null, "com.app:id/likeButton"))

        assertFalse(LuluMikaClickFallback.mayBeOutward("刷新", "com.app:id/refresh"))
        assertFalse(LuluMikaClickFallback.mayBeOutward("Repositories", null))
    }
}
