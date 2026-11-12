package com.jiacimu.lulu.system

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Regression for Xiaohongshu-like virtual-screen search versus irreversible Enter. */
class LuluVirtualSearchFieldPolicyTest {
    @Test fun recognizedSearchEditorCanReceiveExplicitEnter() {
        assertTrue(LuluVirtualSearchFieldPolicy.isSearchEditor(
            "android.widget.EditText", "搜索笔记和用户", "com.xingin.xhs:id/edit_search"))
        assertTrue(LuluVirtualSearchFieldPolicy.isSearchEditor(
            "android.widget.EditText", null, "com.example:id/search_input"))
        assertTrue(LuluVirtualSearchFieldPolicy.isSearchEditor(
            "android.widget.SearchView", "", null))
    }

    @Test fun neverSubmitAnUnknownMessagingOrSensitiveEditor() {
        assertFalse(LuluVirtualSearchFieldPolicy.isSearchEditor(
            "android.widget.EditText", "写评论", "com.example:id/comment_input"))
        assertFalse(LuluVirtualSearchFieldPolicy.isSearchEditor(
            "android.widget.EditText", "发送消息", "com.example:id/search_message_input"))
        assertFalse(LuluVirtualSearchFieldPolicy.isSearchEditor(
            "android.widget.EditText", "密码", "com.example:id/password"))
        assertFalse(LuluVirtualSearchFieldPolicy.isSearchEditor(
            "android.widget.EditText", null, null))
    }

    @Test fun inputContentsCannotTurnAnotherEditorIntoSearch() {
        // The policy has no actual text/input-content argument at all: only
        // the currently focused editor's class/hint/resourceId count.
        assertFalse(LuluVirtualSearchFieldPolicy.isSearchEditor(
            "android.widget.EditText", "说点什么", "com.example:id/edit_text"))
    }
}
