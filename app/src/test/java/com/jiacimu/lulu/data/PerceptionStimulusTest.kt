package com.jiacimu.lulu.data

import org.junit.Assert.*
import org.junit.Test

class PerceptionStimulusTest {
    @Test fun silentBackgroundHeartbeatCannotRandomlyRewriteCharacterMood() {
        assertFalse(PerceptionStimulusResolver.shouldUpdateVisibleState(false, false, false))
        assertTrue(PerceptionStimulusResolver.shouldUpdateVisibleState(false, true, false))
        assertTrue(PerceptionStimulusResolver.shouldUpdateVisibleState(false, false, true))
        assertTrue(PerceptionStimulusResolver.shouldUpdateVisibleState(true, false, false))
    }

    @Test fun awakeSilenceCanKeepInnerStateWithoutPretendingAnExternalEventOccurred() {
        assertTrue(PerceptionStimulusResolver.shouldUpdateVisibleState(false, false, false,
            awakeReflection = true))
        assertNull(PerceptionStimulusResolver.select("", emptySet(), "", "", "", emptyList()))
    }

    @Test fun samePendingUserMessageIsNotInventedAgainEveryBackgroundWake() {
        val first = PerceptionStimulusResolver.select(
            unreadText = "", unreadIds = emptySet(), worldEvent = "", worldEventId = "",
            pendingText = "还没回复我的一句话", pendingIds = listOf("original-message-id"))
        val second = PerceptionStimulusResolver.select(
            unreadText = "", unreadIds = emptySet(), worldEvent = "", worldEventId = "",
            pendingText = "还没回复我的一句话", pendingIds = listOf("original-message-id"))
        assertEquals("original-message-id", first?.evidenceId)
        assertEquals(first, second)
        val newMessage = PerceptionStimulusResolver.select(
            unreadText = "", unreadIds = emptySet(), worldEvent = "", worldEventId = "",
            pendingText = "这次又有了新消息", pendingIds = listOf("new-message-id", "original-message-id"))
        assertEquals("new-message-id", newMessage?.evidenceId)
    }

    @Test fun newWorldEventMayBePerceivedWithoutTreatingOldChatAsFreshEmotion() {
        val event = PerceptionStimulusResolver.select(
            unreadText = "", unreadIds = emptySet(),
            worldEvent = "书掉到了地上", worldEventId = "book:fallen:rev1",
            pendingText = "昨晚还没回的话", pendingIds = listOf("old-id"))
        assertEquals("world-event:book:fallen:rev1", event?.evidenceId)
        assertTrue(event?.socialIds?.isEmpty() == true)
    }

    @Test fun actualUnreadMessagesWinOverOtherPendingHistory() {
        val latest = PerceptionStimulusResolver.select(
            unreadText = "刚收到的新消息", unreadIds = setOf("abc"),
            worldEvent = "旧世界事件", worldEventId = "world123",
            pendingText = "上次没答", pendingIds = listOf("older"))
        assertEquals("abc", latest?.evidenceId)
        assertEquals(setOf("user"), latest?.socialIds)
    }
}
