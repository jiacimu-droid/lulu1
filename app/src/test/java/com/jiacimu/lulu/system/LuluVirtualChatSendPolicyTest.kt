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
        val prefs = ctx.getSharedPreferences("lulu_execution_policy", Context.MODE_PRIVATE)
        val a = "wechat-test-a"
        val b = "wechat-test-b"
        prefs.edit().remove("$a:screen").remove("$b:screen").commit()
        try {
            assertFalse(LuluVirtualChatSendPolicy.isEnabled(ctx, a))
            assertFalse(LuluVirtualChatSendPolicy.isEnabled(ctx, b))
            assertTrue(LuluRolePhoneAuthorization.setGranted(ctx, a, true))
            assertTrue(LuluVirtualChatSendPolicy.isEnabled(ctx, a))
            assertFalse(LuluVirtualChatSendPolicy.isEnabled(ctx, b))
            assertFalse(LuluRolePhoneAuthorization.setGranted(ctx, "resident:foo", true))
            assertFalse(LuluVirtualChatSendPolicy.isEnabled(ctx, "resident:foo"))
            assertTrue(LuluRolePhoneAuthorization.setGranted(ctx, a, false))
            assertFalse(LuluVirtualChatSendPolicy.isEnabled(ctx, a))
        } finally { prefs.edit().remove("$a:screen").remove("$b:screen").commit() }
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

    @Test fun voiceComposerSwitchFindsOnlyTheUniqueLeftKeyboardTarget() {
        fun b(l: Int, t: Int, r: Int, bt: Int) =
            LuluVirtualChatSendPolicy.ComposerBounds(l, t, r, bt)
        fun c(bounds: LuluVirtualChatSendPolicy.ComposerBounds, label: String,
              resourceId: String, clickable: Boolean) =
            LuluVirtualChatSendPolicy.ComposerControl(bounds, label, resourceId, clickable)
        val talk = b(125, 1040, 610, 1100)
        assertTrue(LuluVirtualChatSendPolicy.isPressToTalk("按住 说话"))
        assertTrue(LuluVirtualChatSendPolicy.isPressToTalk("按住讲话"))
        assertFalse(LuluVirtualChatSendPolicy.isPressToTalk("聊天记录：按住说话就行"))
        val controls = listOf(
            c(b(50, 1040, 103, 1100), "", "", true),
            c(b(640, 1038, 695, 1100), "表情", "", true),
            c(b(10, 50, 85, 120), "返回", "", true))
        assertEquals(0, LuluVirtualChatSendPolicy.voiceToTextControl(
            talk, controls, screenWidth = 720, screenHeight = 1280))
        assertNull("Two different targets next to the composer are ambiguous",
            LuluVirtualChatSendPolicy.voiceToTextControl(talk, controls +
                c(b(4, 1040, 55, 1100), "", "", true), 720, 1280))
        assertNull("Never click a Send control to toggle voice mode",
            LuluVirtualChatSendPolicy.voiceToTextControl(
                talk, listOf(c(b(50, 1040, 103, 1100), "发送", "", true)), 720, 1280))
        assertNull("Cannot guess a keyboard toggle from chat history",
            LuluVirtualChatSendPolicy.voiceToTextControl(
                b(125, 200, 610, 260), controls, 720, 1280))
        assertNull("Without a verified clickable control, never invent a tap",
            LuluVirtualChatSendPolicy.voiceToTextControl(
                talk, listOf(c(b(50, 1040, 103, 1100), "", "", false)), 720, 1280))
    }

    @Test fun chatHeaderUsesExactVisibleTitleAndToleratesAccessibilityDescription() {
        assertTrue(LuluVirtualChatSendPolicy.headerMatches(
            "文件传输助手", null, "文件传输助手"))
        assertTrue(LuluVirtualChatSendPolicy.headerMatches(
            null, "文件传输助手", "文件传输助手"))
        assertTrue(LuluVirtualChatSendPolicy.headerMatches(
            " 文件传输助手 ", null, "文件传输助手"))
        assertFalse(LuluVirtualChatSendPolicy.headerMatches(
            "文件传输助手", null, "佳辞"))
        assertFalse(LuluVirtualChatSendPolicy.headerMatches(
            "佳辞的聊天", null, "佳辞"))
        assertFalse(LuluVirtualChatSendPolicy.headerMatches(
            null, null, "文件传输助手"))
    }

    @Test fun actualWindowTitleCanVerifyCustomWeChatToolbarWithoutMatchingTextNodes() {
        assertTrue(LuluVirtualChatSendPolicy.windowTitleMatches("文件传输助手", "文件传输助手"))
        assertTrue(LuluVirtualChatSendPolicy.windowTitleMatches(" 佳辞 ", "佳辞"))
        assertFalse(LuluVirtualChatSendPolicy.windowTitleMatches("微信", "微信"))
        assertFalse(LuluVirtualChatSendPolicy.windowTitleMatches("WeChat", "WeChat"))
        assertFalse(LuluVirtualChatSendPolicy.windowTitleMatches("文件传输助手", "佳辞"))
        assertFalse(LuluVirtualChatSendPolicy.windowTitleMatches(null, "佳辞"))
    }

    @Test fun nestedChatToolbarLabelsCountAsOneButTwoPositionsNeverDo() {
        fun b(l: Int, t: Int, r: Int, bt: Int) =
            LuluVirtualChatSendPolicy.ComposerBounds(l, t, r, bt)
        // A wide parent wraps the true title: centres differ by ~100 px.
        assertTrue(LuluVirtualChatSendPolicy.uniqueHeaderTitle(
            listOf(b(25, 80, 690, 170), b(135, 102, 270, 142)), 1280))
        assertFalse(LuluVirtualChatSendPolicy.uniqueHeaderTitle(
            listOf(b(135, 102, 270, 142), b(135, 198, 270, 238)), 1280))
        assertFalse(LuluVirtualChatSendPolicy.uniqueHeaderTitle(emptyList(), 1280))
    }

    @Test fun sendMustBeBesideVerifiedComposerNotAnywhereInChatHistory() {
        fun b(l: Int, t: Int, r: Int, bt: Int) =
            LuluVirtualChatSendPolicy.ComposerBounds(l, t, r, bt)
        val editor = b(125, 1038, 580, 1100)
        val send = b(590, 1039, 695, 1098)
        assertTrue(LuluVirtualChatSendPolicy.sendControlNearEditor(
            send, editor, 720, 1280))
        assertFalse(LuluVirtualChatSendPolicy.sendControlNearEditor(
            b(590, 700, 695, 765), editor, 720, 1280))
        assertFalse(LuluVirtualChatSendPolicy.sendControlNearEditor(
            b(5, 1039, 110, 1098), editor, 720, 1280))
        assertEquals(0, LuluVirtualChatSendPolicy.uniqueSendTarget(
            listOf(send, b(588, 1038, 698, 1100)), 720, 1280))
        assertEquals("An outer clickable wrapper and inner Send label are one button",
            1, LuluVirtualChatSendPolicy.uniqueSendTarget(
                listOf(b(580, 1030, 711, 1110), b(620, 1047, 695, 1090)), 720, 1280))
        assertNull(LuluVirtualChatSendPolicy.uniqueSendTarget(
            listOf(send, b(505, 1040, 560, 1098)), 720, 1280))
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
