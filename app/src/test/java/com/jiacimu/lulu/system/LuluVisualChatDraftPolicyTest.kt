package com.jiacimu.lulu.system

import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * No fake AccessibilityNodeInfo is required. Visual evidence is a separate
 * sensor from the empty tree in the Redmi/HyperOS regression.
 */
@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE, sdk = [34])
class LuluVisualChatDraftPolicyTest {
    private fun line(text: String, left: Int, top: Int, right: Int, bottom: Int) =
        LuluVirtualChatTitleOcr.Line(text,
            LuluVirtualChatSendPolicy.ComposerBounds(left, top, right, bottom))

    @Test fun exactRecipientAndDraftYieldOnlyVisibleSendButtonCoordinates() {
        val lines = listOf(
            line("文件传输助手", 288, 58, 438, 99),
            line("你好佳辞", 112, 905, 324, 950),
            line("发送", 631, 913, 692, 965),
        )
        val result = LuluVisualChatDraftPolicy.verifiedDraft(
            lines, "文件传输助手", "你好佳辞", 720, 1280)
        assertNotNull(result)
        assertTrue(result!!.sendX in 850..970)
        assertTrue(result.sendY in 680..790)
        assertEquals(0, result.previousOutgoing)
    }

    @Test fun wrongRecipientAndOldOutgoingBubbleCannotAuthorizeVisualSend() {
        val oldBubble = line("你好佳辞", 390, 480, 670, 528)
        val header = line("小小草儿", 288, 58, 438, 99)
        val send = line("发送", 631, 913, 692, 965)
        assertNull(LuluVisualChatDraftPolicy.verifiedDraft(
            listOf(header, oldBubble, send), "文件传输助手", "你好佳辞", 720, 1280))
        assertNull(LuluVisualChatDraftPolicy.verifiedDraft(
            listOf(line("文件传输助手", 288, 58, 438, 99), oldBubble, send),
            "文件传输助手", "你好佳辞", 720, 1280))
    }

    @Test fun longerComposerTextCannotAuthorizeSendingOnlyItsSubstring() {
        val header = line("文件传输助手", 288, 58, 438, 99)
        val send = line("发送", 631, 913, 692, 965)
        val longerDraft = line("你好佳辞，明天见", 112, 905, 430, 950)
        assertNull(LuluVisualChatDraftPolicy.verifiedDraft(
            listOf(header, longerDraft, send), "文件传输助手", "你好佳辞", 720, 1280))
        val exactDraft = line("你好佳辞", 112, 905, 324, 950)
        assertNotNull(LuluVisualChatDraftPolicy.verifiedDraft(
            listOf(header, exactDraft, send), "文件传输助手", "你好佳辞", 720, 1280))
    }

    @Test fun noUniqueBottomSendLabelMeansNoIrreversibleClick() {
        val title = line("文件传输助手", 288, 58, 438, 99)
        val draft = line("你好佳辞", 112, 905, 324, 950)
        assertNull(LuluVisualChatDraftPolicy.verifiedDraft(
            listOf(title, draft), "文件传输助手", "你好佳辞", 720, 1280))
        assertNull(LuluVisualChatDraftPolicy.verifiedDraft(
            listOf(title, draft, line("发送", 631, 913, 692, 965),
                line("发送", 630, 850, 690, 890)),
            "文件传输助手", "你好佳辞", 720, 1280))
    }

    @Test fun keyboardSendActionCanNeverBeMistakenForAppSendButton() {
        val header = line("文件传输助手", 288, 58, 438, 99)
        val draft = line("你好佳辞", 112, 905, 324, 950)
        val imeSend = line("发送", 631, 913, 692, 965)
        assertNotNull(LuluVisualChatDraftPolicy.verifiedDraft(
            listOf(header, draft, imeSend), "文件传输助手", "你好佳辞",
            720, 1280))
        // If the IME starts at y=850, that Send belongs to the keyboard
        // and must NOT authorize an irreversible app-send gesture.
        assertNull(LuluVisualChatDraftPolicy.verifiedDraft(
            listOf(header, draft, imeSend), "文件传输助手", "你好佳辞",
            720, 1280, imeTopY = 850))
    }

    @Test fun composerPointRequiresActualBottomLeftAreaAndBothCoordinates() {
        assertTrue(LuluVisualChatDraftPolicy.composerPointSafe(480, 900))
        assertFalse(LuluVisualChatDraftPolicy.composerPointSafe(960, 900))
        assertFalse(LuluVisualChatDraftPolicy.composerPointSafe(480, 100))
        val base = JSONObject()
            .put("recipient", "文件传输助手")
            .put("text", "测试")
            .put("screenSnapshotId", "current")
        assertNull(LuluBackgroundPhoneArgumentPolicy.invalidReason("send_chat_message", base))
        assertNotNull(LuluBackgroundPhoneArgumentPolicy.invalidReason("send_chat_message",
            JSONObject(base.toString()).put("composerX", 450)))
        assertNotNull(LuluBackgroundPhoneArgumentPolicy.invalidReason("send_chat_message",
            JSONObject(base.toString()).put("composerX", 450).put("composerY", 1002)))
        assertNull(LuluBackgroundPhoneArgumentPolicy.invalidReason("send_chat_message",
            JSONObject(base.toString()).put("composerX", 450).put("composerY", 900)))
    }

    @Test fun shellUnicodeTransportRefusesOwnerScreenWrongAppOrEmptyText() {
        assertFalse(com.wangzan101.mika.cap.phone.shell.LuluShellTextInput.mayPaste(
            0, "com.tencent.mm", "你好", "com.tencent.mm"))
        assertFalse(com.wangzan101.mika.cap.phone.shell.LuluShellTextInput.mayPaste(
            71, "com.tencent.mm", "你好", "com.ss.android.ugc.aweme"))
        assertFalse(com.wangzan101.mika.cap.phone.shell.LuluShellTextInput.mayPaste(
            71, "com.tencent.mm", "", "com.tencent.mm"))
        assertTrue(com.wangzan101.mika.cap.phone.shell.LuluShellTextInput.mayPaste(
            71, "com.tencent.mm", "你好", "com.tencent.mm"))
    }
}
