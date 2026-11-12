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
