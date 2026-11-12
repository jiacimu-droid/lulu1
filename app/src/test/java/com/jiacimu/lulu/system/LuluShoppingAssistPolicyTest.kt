package com.jiacimu.lulu.system

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class LuluShoppingAssistPolicyTest {
    @Test fun ownerApprovedNavigationStopsAtRealPaymentAndOrderCommit() {
        for (safe in listOf("加入购物车", "购物车", "选择规格", "去结算", "立即购买", "add to cart")) {
            assertTrue(safe, LuluShoppingAssistPolicy.mayNavigate(safe, "btn_shop", ""))
        }
        for (unsafe in listOf("立即支付", "确认付款", "提交订单", "确认订单",
            "免密购买", "一键购买", "货到付款", "确认购买", "转账", "确认发布")) {
            assertFalse(unsafe, LuluShoppingAssistPolicy.mayNavigate(unsafe, "button", ""))
        }
        assertFalse(LuluShoppingAssistPolicy.mayNavigate("立即购买", "one_click_purchase", ""))
        assertFalse(LuluShoppingAssistPolicy.mayNavigate("去结算", "pay_now", ""))
        assertFalse(LuluShoppingAssistPolicy.mayNavigate("去结算", "checkout_btn", "确认支付"))
        assertFalse(LuluShoppingAssistPolicy.mayNavigate("下一步", "submit_order", ""))
    }

    @Test fun generalUiSafetyStillRejectsPurchasingWithoutOwnerSwitch() {
        assertFalse(LuluVirtualUiPolicy.mayClick("立即购买", "btn_buy", ""))
        assertFalse(LuluVirtualUiPolicy.mayClick("去结算", "checkout_btn", ""))
        assertTrue(LuluVirtualUiPolicy.mayClick("加入购物车", "cart_button", "", true))
        assertTrue(LuluVirtualUiPolicy.mayClick("立即购买", "buy_button", "", true))
        assertFalse(LuluVirtualUiPolicy.mayClick("确认订单", "btn", "", true))
        assertFalse(LuluVirtualUiPolicy.mayClick("立即支付", "pay_button", "", true))
        assertFalse(LuluVirtualUiPolicy.mayClick("下一步", "submit_order", "", true))
        assertFalse(LuluVirtualUiPolicy.mayClick("", "", "", true))
    }
}
