package com.jiacimu.lulu.system

import android.content.Context
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE, sdk = [33])
class LuluVirtualChatSendPolicyTest {
    @Test fun roleGrantIsOptInScopedRevocableAndNeverForResident() {
        val ctx = RuntimeEnvironment.getApplication()
        val prefs = ctx.getSharedPreferences("lulu_virtual_chat_send_owner_v1", Context.MODE_PRIVATE)
        val a = "wechat-test-a"
        val b = "wechat-test-b"
        prefs.edit().remove(a).remove(b).commit()
        try {
            assertFalse(LuluVirtualChatSendPolicy.isEnabled(ctx, a))
            assertFalse(LuluVirtualChatSendPolicy.isEnabled(ctx, b))
            assertTrue(LuluVirtualChatSendPolicy.setEnabled(ctx, a, true))
            assertTrue(LuluVirtualChatSendPolicy.isEnabled(ctx, a))
            assertFalse(LuluVirtualChatSendPolicy.isEnabled(ctx, b))
            assertFalse(LuluVirtualChatSendPolicy.setEnabled(ctx, "resident:foo", true))
            assertFalse(LuluVirtualChatSendPolicy.isEnabled(ctx, "resident:foo"))
            assertTrue(LuluVirtualChatSendPolicy.setEnabled(ctx, a, false))
            assertFalse(LuluVirtualChatSendPolicy.isEnabled(ctx, a))
        } finally { prefs.edit().remove(a).remove(b).commit() }
    }

    @Test fun acceptsOnlyExactSendControlNotGenericSubmitOrPost() {
        assertTrue(LuluVirtualChatSendPolicy.sendButton("发送", ""))
        assertTrue(LuluVirtualChatSendPolicy.sendButton("Send", ""))
        assertTrue(LuluVirtualChatSendPolicy.sendButton("", "com.tencent.mm:id/send_btn"))
        assertFalse(LuluVirtualChatSendPolicy.sendButton("提交", ""))
        assertFalse(LuluVirtualChatSendPolicy.sendButton("发布", ""))
        assertFalse(LuluVirtualChatSendPolicy.sendButton("确认", ""))
        assertFalse(LuluVirtualChatSendPolicy.sendButton("发送验证码", ""))
        assertFalse(LuluVirtualChatSendPolicy.sendButton("转发", ""))
    }

    @Test fun rejectsSearchCommentSensitiveAndInvalidRecipients() {
        assertTrue(LuluVirtualChatSendPolicy.chatEditorCandidate(
            "android.widget.EditText", "输入", "com.tencent.mm:id/chatting_content_et"))
        assertFalse(LuluVirtualChatSendPolicy.chatEditorCandidate(
            "android.widget.EditText", "搜索", "com.tencent.mm:id/search"))
        assertFalse(LuluVirtualChatSendPolicy.chatEditorCandidate(
            "android.widget.EditText", "发表评论", null))
        assertFalse(LuluVirtualChatSendPolicy.chatEditorCandidate(
            "android.widget.TextView", "输入", null))
        assertTrue(LuluVirtualChatSendPolicy.validRecipient("张三"))
        assertFalse(LuluVirtualChatSendPolicy.validRecipient(" 张三 "))
        assertFalse(LuluVirtualChatSendPolicy.validRecipient(""))
        assertFalse(LuluVirtualChatSendPolicy.validRecipient("a".repeat(81)))
        assertTrue(LuluVirtualChatSendPolicy.validMessage("你好"))
        assertFalse(LuluVirtualChatSendPolicy.validMessage("  "))
        assertFalse(LuluVirtualChatSendPolicy.validMessage("a".repeat(501)))
    }

    @Test fun chatSendRequiresExplicitFieldsBeforeTransport() {
        assertNotNull(LuluBackgroundPhoneArgumentPolicy.invalidReason("send_chat_message", JSONObject()))
        assertNotNull(LuluBackgroundPhoneArgumentPolicy.invalidReason("send_chat_message",
            JSONObject().put("recipient", "张三").put("text", "你好")))
        assertNull(LuluBackgroundPhoneArgumentPolicy.invalidReason("send_chat_message",
            JSONObject().put("recipient", "张三").put("text", "你好")
                .put("screenSnapshotId", "abc")))
    }
}
