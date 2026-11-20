package com.jiacimu.lulu.data

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class UnifiedReplyAndActionPolicyTest {
    @Test fun deviceOperationIsNotAChatReply() {
        assertFalse(UnifiedReplyAndActionPolicy.markSeen(
            unreadPresent = true, chosenAction = "tool",
            chosenTool = "control_volume", replyChoice = "",
            actionSucceeded = true, replyActuallyDelivered = false,
            conversationMatches = false))
    }

    @Test fun roleMayReplyAndUseDeviceInSameDecision() {
        assertTrue(UnifiedReplyAndActionPolicy.markSeen(
            unreadPresent = true, chosenAction = "message",
            chosenTool = "", replyChoice = "",
            actionSucceeded = true, replyActuallyDelivered = false,
            conversationMatches = true))
        assertTrue(UnifiedReplyAndActionPolicy.markSeen(
            unreadPresent = true, chosenAction = "tool",
            chosenTool = "control_music", replyChoice = "",
            actionSucceeded = true, replyActuallyDelivered = true,
            conversationMatches = true))
    }

    @Test fun explicitQuietChoiceIsRespected() {
        assertTrue(UnifiedReplyAndActionPolicy.markSeen(
            unreadPresent = true, chosenAction = "tool",
            chosenTool = "control_music", replyChoice = "silent",
            actionSucceeded = true, replyActuallyDelivered = false,
            conversationMatches = false))
    }

    @Test fun failedToolCannotEraseUnreadMessage() {
        assertFalse(UnifiedReplyAndActionPolicy.markSeen(
            unreadPresent = true, chosenAction = "tool",
            chosenTool = "control_music", replyChoice = "silent",
            actionSucceeded = false, replyActuallyDelivered = false,
            conversationMatches = false))
        assertTrue(UnifiedReplyAndActionPolicy.markSeen(
            unreadPresent = true, chosenAction = "tool",
            chosenTool = "control_music", replyChoice = "",
            actionSucceeded = false, replyActuallyDelivered = true,
            conversationMatches = true))
    }

    @Test fun stickerOnlyReplyUsesTheSameDeliveryEvidenceAsText() {
        val target = setOf("private-chat")
        val messages = listOf("delivered-id")
        for (tool in listOf("send_private_message", "send_private_sticker")) {
            assertTrue(UnifiedReplyAndActionPolicy.hasPrivateReplyReceipt(
                tool, true, "private-chat", messages, target))
            assertFalse(UnifiedReplyAndActionPolicy.hasPrivateReplyReceipt(
                tool, true, "private-chat", emptyList(), target))
            assertFalse(UnifiedReplyAndActionPolicy.hasPrivateReplyReceipt(
                tool, true, "other-chat", messages, target))
            assertFalse(UnifiedReplyAndActionPolicy.hasPrivateReplyReceipt(
                tool, false, "private-chat", messages, target))
        }
        assertFalse(UnifiedReplyAndActionPolicy.hasPrivateReplyReceipt(
            "send_group_sticker", true, "private-chat", messages, target))
    }

    @Test fun directMessageToolNeedsRealConversationReceipt() {
        assertFalse(UnifiedReplyAndActionPolicy.markSeen(
            unreadPresent = true, chosenAction = "tool",
            chosenTool = "send_private_message", replyChoice = "",
            actionSucceeded = true, replyActuallyDelivered = false,
            conversationMatches = false))
        assertTrue(UnifiedReplyAndActionPolicy.markSeen(
            unreadPresent = true, chosenAction = "tool",
            chosenTool = "send_private_message", replyChoice = "",
            actionSucceeded = true, replyActuallyDelivered = false,
            conversationMatches = true))
    }
}
