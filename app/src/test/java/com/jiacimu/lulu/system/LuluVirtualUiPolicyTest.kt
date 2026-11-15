package com.jiacimu.lulu.system

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class LuluVirtualUiPolicyTest {
    @Test fun onlyTheCurrentSnapshotCanAuthorizeAnElementRef() {
        val tree = LuluVirtualUiPolicy.fingerprint(listOf("e1|search", "e2|笔记"))
        assertTrue(LuluVirtualUiPolicy.sameSnapshot("screen-01", "screen-01", tree, tree))
        assertFalse(LuluVirtualUiPolicy.sameSnapshot("screen-00", "screen-01", tree, tree))
        assertFalse(LuluVirtualUiPolicy.sameSnapshot("", "screen-01", tree, tree))
        assertFalse(LuluVirtualUiPolicy.sameSnapshot("screen-01", "screen-01", "", tree))
        assertNotEquals(tree, LuluVirtualUiPolicy.fingerprint(listOf("e1|search", "e2|视频")))
        assertNotEquals(tree, LuluVirtualUiPolicy.fingerprint(listOf("e2|笔记", "e1|search")))
    }

    @Test fun deviceEffectIsNotBusinessCompletion() {
        val changed = LuluVirtualUiPolicy.fingerprint(listOf("next-page"))
        val before = LuluVirtualUiPolicy.fingerprint(listOf("landing-page"))
        val kind = LuluVirtualUiPolicy::effectKind
        assertTrue(kind("observe", true, before, changed, false) == "read_only")
        assertTrue(kind("tap_element", true, before, changed, false) == "tree_changed")
        assertTrue(kind("swipe", true, before, before, false) == "tree_unchanged")
        assertTrue(kind("submit", true, before, "", false) == "unknown")
        assertTrue(kind("type", true, before, before, true) == "input_verified")
        assertTrue(kind("type", true, before, before, false) == "input_unverified")
        assertTrue(kind("tap", false, before, before, false) == "not_accepted")
    }

    @Test fun aDifferentFeedPostInvalidatesOldControlsEvenAtSamePositions() {
        val identicalButtons = listOf("e1|search|720x1280", "e2|card|720x1280")
        val old = LuluVirtualUiPolicy.fingerprint(identicalButtons + "page:旧笔记")
        val next = LuluVirtualUiPolicy.fingerprint(identicalButtons + "page:下一条笔记")
        assertNotEquals(old, next)
        assertFalse(LuluVirtualUiPolicy.sameSnapshot("screen-1", "screen-1", old, next))
    }

    @Test fun virtualBackPrefersTheRealOrdersPageArrowOverAnEdgeGesture() {
        // The user-observed Douyin order page has a left arrow next to a
        // long order-number search box, not a guaranteed Android BACK route.
        fun c(l: Int, t: Int, r: Int, b: Int, label: String = "", id: String = "") =
            LuluVirtualUiPolicy.AppBarControl(l, t, r, b, label, id)
        val search = c(140, 138, 540, 196, "商品名/订单号/快递单号", "search")
        val arrow = c(65, 136, 121, 192, "返回", "com.example:id/btn_back")
        val controls = listOf(search, arrow, c(610, 145, 660, 193, "仓裹", ""))
        assertEquals(1, LuluVirtualUiPolicy.appBackControlIndex(720, 1280, controls))
        assertTrue(LuluVirtualUiPolicy.isNamedAppBack("返回", ""))
        assertTrue(LuluVirtualUiPolicy.isNamedAppBack("", "com.example:id/iv_back"))
        assertFalse(LuluVirtualUiPolicy.isNamedAppBack("订单", "all_orders"))
        assertEquals(1, LuluVirtualUiPolicy.appBackControlIndex(720, 1280,
            listOf(search, arrow.copy(label = "", resourceId = ""))))
    }

    @Test fun virtualBackNeverGuessesAmbiguousOrUnrelatedTopLeftTargets() {
        fun c(l: Int, t: Int, r: Int, b: Int, label: String = "", id: String = "") =
            LuluVirtualUiPolicy.AppBarControl(l, t, r, b, label, id)
        val unknown = c(65, 140, 115, 194)
        assertEquals(null, LuluVirtualUiPolicy.appBackControlIndex(
            720, 1280, listOf(unknown)))
        assertEquals(null, LuluVirtualUiPolicy.appBackControlIndex(
            720, 1280, listOf(c(65, 140, 115, 194, "菜单", "menu"))))
        val wideSearch = c(140, 138, 540, 196, "订单搜索")
        assertEquals(null, LuluVirtualUiPolicy.appBackControlIndex(
            720, 1280, listOf(unknown, wideSearch, c(8, 144, 54, 192))))
        // A mid-page arrow or a full-screen container is not a toolbar BACK.
        assertEquals(null, LuluVirtualUiPolicy.appBackControlIndex(
            720, 1280, listOf(c(65, 690, 115, 746, "返回"))))
        assertEquals(null, LuluVirtualUiPolicy.appBackControlIndex(
            720, 1280, listOf(c(0, 48, 720, 1200, "返回"))))
    }

    @Test fun onlySafeNamedNavigationMayBeClicked() {
        assertTrue(LuluVirtualUiPolicy.mayClick("搜索", "search_icon", ""))
        assertTrue(LuluVirtualUiPolicy.mayClick("打开笔记", "note_card", ""))
        assertFalse(LuluVirtualUiPolicy.mayClick("", "", ""))
        assertFalse(LuluVirtualUiPolicy.mayClick("确认发布", "button", ""))
        assertFalse(LuluVirtualUiPolicy.mayClick("关注", "follow", ""))
        assertFalse(LuluVirtualUiPolicy.mayClick("打开", "button", "里面是发送评论"))
        assertFalse(LuluVirtualUiPolicy.mayClick("删除", "trash", ""))
        assertFalse(LuluVirtualUiPolicy.mayClick("下一步", "confirm_order", ""))
    }
}
