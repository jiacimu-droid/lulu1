package com.jiacimu.lulu

import org.junit.Assert.*
import org.junit.Test

class ChatScrollFollowPolicyTest {
    @Test fun returningFromOtherPagesNeverStealsTheUsersPlaceInChatHistory() {
        assertFalse(ChatScrollFollowPolicy.shouldFollow(
            initial = false, appended = false,
            autoScroll = true, nearEnd = false))
        assertFalse(ChatScrollFollowPolicy.shouldFollow(
            initial = false, appended = true,
            autoScroll = true, nearEnd = false))
        // This is precisely the initial oldest-message regression.
        assertTrue(ChatScrollFollowPolicy.shouldFollow(
            initial = true, appended = true,
            autoScroll = true, nearEnd = false))
    }

    @Test fun initialPositioningWorksWithHugeHistoryAndAutoScrollDisabled() {
        assertTrue(ChatScrollFollowPolicy.shouldFollow(
            initial = true, appended = false,
            autoScroll = false, nearEnd = false))
        assertTrue(ChatScrollFollowPolicy.shouldFollow(
            initial = true, appended = false,
            autoScroll = false, nearEnd = true))
    }

    @Test fun initialOpenNewMessagesAndKeyboardMayFollowOnlyAtTheBottom() {
        assertTrue(ChatScrollFollowPolicy.shouldFollow(
            initial = true, appended = true,
            autoScroll = true, nearEnd = true))
        assertTrue(ChatScrollFollowPolicy.shouldFollow(
            initial = false, appended = true,
            autoScroll = true, nearEnd = true))
        assertFalse(ChatScrollFollowPolicy.shouldFollow(
            initial = false, appended = false,
            autoScroll = false, nearEnd = true))
        assertFalse(ChatScrollFollowPolicy.shouldFollow(
            initial = false, appended = true,
            autoScroll = false, nearEnd = true))
        assertFalse(ChatScrollFollowPolicy.shouldFollow(
            initial = false, appended = false,
            autoScroll = true, nearEnd = true))
        assertFalse(ChatScrollFollowPolicy.shouldFollow(
            initial = false, appended = false,
            autoScroll = true, nearEnd = false))
    }
}
