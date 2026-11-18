package com.jiacimu.lulu.system

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.json.JSONObject

@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE, sdk = [28])
class LuluBackgroundPhoneContinuationYieldTest {
    @Test fun distinctFailedRoutesRemainExploreableOnlyWithRealCurrentPageEvidence() {
        val ownedPage = JSONObject()
            .put("targetWindowVerified", true)
            .put("uiTreeFingerprint", "real-current-editor-tree")
        assertFalse(LuluBackgroundPhoneContinuation.failuresExhaustedWithoutScreen(
            2, ownedPage, null))
        assertFalse(LuluBackgroundPhoneContinuation.failuresExhaustedWithoutScreen(
            5, JSONObject().put("targetWindowVerified", true), "actual-current-frame"))
        assertTrue(LuluBackgroundPhoneContinuation.failuresExhaustedWithoutScreen(
            2, JSONObject().put("targetWindowVerified", false), "unowned-image"))
        assertTrue(LuluBackgroundPhoneContinuation.failuresExhaustedWithoutScreen(
            2, JSONObject().put("targetWindowVerified", true), null))
        assertFalse(LuluBackgroundPhoneContinuation.failuresExhaustedWithoutScreen(
            1, null, null))
    }

    @Test fun historicalPhoneReceiptsNeverBecomeNewAttemptsOrGestures() {
        val current = JSONObject().put("mode", "independent_virtual_display")
            .put("status", "observed").put("success", true)
            .put("screenSnapshotId", "live-snapshot")
        assertTrue(LuluBackgroundPhoneContinuation.hasFreshExecutionReceipt(current))
        val old = JSONObject(current.toString())
            .put("replayedHistoricalReceipt", true)
            .put("originalRequestId", "half-an-hour-ago")
            .put("originalFinishedAt", "2026-11-18T15:28:00+08:00")
        assertFalse("Cached success is not a new user-requested attempt",
            LuluBackgroundPhoneContinuation.hasFreshExecutionReceipt(old))
        assertFalse("Cached failure must not become this turn's failed retry",
            LuluBackgroundPhoneContinuation.hasFreshExecutionReceipt(
                old.put("status", "failed").put("success", false)))
        assertFalse(LuluBackgroundPhoneContinuation.hasFreshExecutionReceipt(null))
    }

    @Test fun phoneActionReceiptsStayDistinctBeyondFormerTwelveStepCutoff() {
        val ids = (1L..128L).map { ordinal ->
            LuluBackgroundPhoneContinuation.stepRequestId("owner-task", ordinal)
        }
        assertTrue(ids.distinct().size == ids.size)
        assertTrue(ids[11] == "owner-task-phone-12")
        assertTrue(ids[12] == "owner-task-phone-13")
        assertTrue(ids.last() == "owner-task-phone-128")
    }

    @Test fun visualWechatPreSendFailurePermitsOnlySafeReinspection() {
        assertTrue(LuluBackgroundPhoneContinuation.mayRecoverUnsentChat(
            JSONObject().put("errorCode", "visual_chat_draft_or_send_unverified")
                .put("inputDispatched", true)))
        assertTrue(LuluBackgroundPhoneContinuation.mayRecoverUnsentChat(
            JSONObject().put("errorCode", "visual_chat_keyboard_not_on_display")))
        assertFalse(LuluBackgroundPhoneContinuation.mayRecoverUnsentChat(
            JSONObject().put("errorCode", "visual_chat_send_click_unverified")
                .put("messageSendAttempted", true)))
        assertFalse(LuluBackgroundPhoneContinuation.mayRecoverUnsentChat(
            JSONObject().put("errorCode", "visual_chat_draft_or_send_unverified")
                .put("messageSendClicked", true)))
    }

    @Test fun emptyAccessibilityControlsStillAdvertiseActualPictureNavigation() {
        val receipt = JSONObject().put("screenSnapshotId", "picture-without-tree")
            .put("interactiveElements", org.json.JSONArray())
            .put("imageForwardedToRole", true).toString()
        val method = LuluBackgroundPhoneContinuation::class.java
            .getDeclaredMethod("uiReferenceSummary", String::class.java).apply { isAccessible = true }
        val summary = method.invoke(LuluBackgroundPhoneContinuation, receipt) as String
        assertTrue(summary.contains("picture-without-tree"))
        assertTrue(summary.contains("无障碍控件树为空"))
        assertTrue(summary.contains("tap/long_press"))
        assertTrue(summary.contains("composerX/composerY"))
    }

    @Test fun ownerStopOrRevocationDuringPaidModelWaitBlocksStaleNextGesture() {
        assertFalse(LuluBackgroundPhoneContinuation.mustAbortAfterModel(
            ownerPaused = false, authorized = true))
        assertTrue("STOP must invalidate the already generated click",
            LuluBackgroundPhoneContinuation.mustAbortAfterModel(
                ownerPaused = true, authorized = true))
        assertTrue("Revoked role consent must invalidate the already generated click",
            LuluBackgroundPhoneContinuation.mustAbortAfterModel(
                ownerPaused = false, authorized = false))
        assertTrue(LuluBackgroundPhoneContinuation.mustAbortAfterModel(
            ownerPaused = true, authorized = false))
    }

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
    @Test fun longCommentListDoesNotHideUnnamedDraftFieldOrItsSubmitButton() {
        val elements = org.json.JSONArray()
        repeat(40) { index -> elements.put(JSONObject().put("ref", "e$index")
            .put("name", "回复").put("clickable", true).put("y", 200)) }
        elements.put(JSONObject().put("ref", "draft").put("name", "")
            .put("editable", true).put("y", 850).put("x", 400))
        elements.put(JSONObject().put("ref", "publish").put("name", "发布")
            .put("clickable", true).put("y", 850).put("x", 930))
        val receipt = JSONObject().put("screenSnapshotId", "current-comment-page")
            .put("interactiveElements", elements).toString()
        val method = LuluBackgroundPhoneContinuation::class.java
            .getDeclaredMethod("uiReferenceSummary", String::class.java).apply { isAccessible = true }
        val summary = method.invoke(LuluBackgroundPhoneContinuation, receipt) as String
        assertTrue(summary.contains("draft=文字输入框"))
        assertTrue(summary.contains("publish=发布"))
        assertTrue(summary.contains("current-comment-page"))
    }

}
