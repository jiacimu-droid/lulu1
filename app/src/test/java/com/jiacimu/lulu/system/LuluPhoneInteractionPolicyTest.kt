package com.jiacimu.lulu.system

import org.junit.Assert.*
import org.junit.Test

class LuluPhoneInteractionPolicyTest {
    @Test fun ordinaryGrantAllowsFirstSocialClickAcrossUnspecifiedApps() {
        for (label in listOf("发送", "发表", "发布", "评论", "回复", "点赞", "关注", "收藏", "Send", "Post")) {
            assertTrue(label, LuluVirtualUiPolicy.mayClick(label, "id/custom", "", ordinaryInteractionAllowed = true))
            assertFalse(label, LuluVirtualUiPolicy.mayClick(label, "id/custom", ""))
        }
        // Actual unnamed widget refs can be explored; a specific App or a
        // prewritten chat title/editor transaction is not required.
        assertTrue(LuluVirtualUiPolicy.mayClick("", "", "", ordinaryInteractionAllowed = true))
    }

    @Test fun socialGrantDoesNotAuthorizeChargesCredentialsOrDeletion() {
        for (label in listOf("立即支付", "确认订单", "输入验证码", "支付密码", "删除评论", "Delete account"))
            assertFalse(label, LuluVirtualUiPolicy.mayClick(label, "", "", shoppingAllowed = true,
                ordinaryInteractionAllowed = true))
        assertFalse(LuluVirtualUiPolicy.mayClick("", "id/confirm_payment", "", ordinaryInteractionAllowed = true))
        assertFalse(LuluVirtualUiPolicy.mayClick("继续", "", "确认付款", ordinaryInteractionAllowed = true))
    }

    @Test fun outwardFallbackClassificationIsNotPermissionForTheFirstClick() {
        assertTrue(LuluMikaClickFallback.mayBeOutward("发表", "id/post"))
        assertTrue(LuluVirtualUiPolicy.mayClick("发表", "id/post", "", ordinaryInteractionAllowed = true))
        val attempt = LuluPhoneRepeatGuard.Attempt("tap", "500:200", true, "unknown")
        assertTrue(LuluPhoneRepeatGuard.shouldBlock(attempt, "tap", "500:200"))
        assertFalse(LuluPhoneRepeatGuard.shouldBlock(attempt, "observe", ""))
    }
}
