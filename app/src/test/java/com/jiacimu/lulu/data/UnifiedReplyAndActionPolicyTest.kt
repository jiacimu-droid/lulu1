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
