package com.jiacimu.lulu.system

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.json.JSONObject

class LuluBackgroundPhoneContinuationYieldTest {
    @Test fun newMessageWhilePhoneModelWaitsYieldsBeforeAnyNextTool() {
        val frozen = setOf("old-user-message", "old-group-message")
        assertFalse(LuluBackgroundPhoneContinuation.hasNewUnreadInput(
            frozen, setOf("old-user-message")))
        assertTrue(LuluBackgroundPhoneContinuation.hasNewUnreadInput(
            frozen, setOf("old-user-message", "new-user-message")))
    }

    @Test fun acknowledgingOldMessagesDoesNotFakeNewInput() {
        assertFalse(LuluBackgroundPhoneContinuation.hasNewUnreadInput(
            setOf("old-user-message", "old-group-message"), emptySet()))
    }

    @Test fun onlyProvenPreClickChatFailuresAllowOneRoleRecoveryDecision() {
        val rejectedBeforeClick = JSONObject()
            .put("errorCode", "chat_recipient_unverified")
            .put("messageSendClicked", false)
        assertTrue(LuluBackgroundPhoneContinuation.mayRecoverUnsentChat(rejectedBeforeClick))
        assertTrue(LuluBackgroundPhoneContinuation.mayRecoverUnsentChat(
            JSONObject().put("errorCode", "chat_keyboard_toggle_unverified")))
        assertTrue(LuluBackgroundPhoneContinuation.mayRecoverUnsentChat(
            JSONObject().put("errorCode", "chat_send_button_or_context_unverified")
                .put("messageInputReadbackVerified", true)))
        assertFalse("An accepted click is never safely repeatable",
            LuluBackgroundPhoneContinuation.mayRecoverUnsentChat(
                JSONObject().put("errorCode", "chat_recipient_unverified")
                    .put("messageSendClicked", true)))
        assertFalse("An ambiguous click must not trigger a second send",
            LuluBackgroundPhoneContinuation.mayRecoverUnsentChat(
                JSONObject().put("errorCode", "chat_send_click_unverified")
                    .put("messageSendClicked", false)))
        assertFalse("Unknown or untyped errors must not become automatic retries",
            LuluBackgroundPhoneContinuation.mayRecoverUnsentChat(JSONObject()))
        assertFalse(LuluBackgroundPhoneContinuation.mayRecoverUnsentChat(null))
    }

    @Test fun newVoiceTurnYieldsEvenWhenChatUnreadSetHasNotChanged() {
        val unread = setOf("previous")
        assertFalse(LuluBackgroundPhoneContinuation.shouldYieldToOwner(
            unread, unread, voiceTurnInvalidated = false))
        assertTrue(LuluBackgroundPhoneContinuation.shouldYieldToOwner(
            unread, unread, voiceTurnInvalidated = true))
        assertTrue(LuluBackgroundPhoneContinuation.shouldYieldToOwner(
            unread, unread + "new-chat-message", voiceTurnInvalidated = false))
    }
}
