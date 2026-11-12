package com.jiacimu.lulu.system

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
