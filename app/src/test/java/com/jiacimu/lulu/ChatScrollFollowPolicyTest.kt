package com.jiacimu.lulu

import org.junit.Assert.*
import org.junit.Test

class ChatScrollFollowPolicyTest {
    @Test fun returningFromOtherPagesNeverStealsTheUsersPlaceInChatHistory() {
        assertFalse(ChatScrollFollowPolicy.shouldFollow(
            initial = false, appended = false, keyboardVisible = true,
            autoScroll = true, nearEnd = false))
        assertFalse(ChatScrollFollowPolicy.shouldFollow(
            initial = false, appended = true, keyboardVisible = false,
            autoScroll = true, nearEnd = false))
        assertFalse(ChatScrollFollowPolicy.shouldFollow(
            initial = true, appended = true, keyboardVisible = false,
            autoScroll = true, nearEnd = false))
    }

    @Test fun initialOpenNewMessagesAndKeyboardMayFollowOnlyAtTheBottom() {
        assertTrue(ChatScrollFollowPolicy.shouldFollow(
            initial = true, appended = true, keyboardVisible = false,
            autoScroll = true, nearEnd = true))
        assertTrue(ChatScrollFollowPolicy.shouldFollow(
            initial = false, appended = true, keyboardVisible = false,
            autoScroll = true, nearEnd = true))
        assertTrue(ChatScrollFollowPolicy.shouldFollow(
            initial = false, appended = false, keyboardVisible = true,
            autoScroll = false, nearEnd = true))
        assertFalse(ChatScrollFollowPolicy.shouldFollow(
            initial = false, appended = true, keyboardVisible = false,
            autoScroll = false, nearEnd = true))
        assertFalse(ChatScrollFollowPolicy.shouldFollow(
            initial = false, appended = false, keyboardVisible = false,
            autoScroll = true, nearEnd = true))
    }
}
