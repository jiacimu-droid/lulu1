package com.jiacimu.lulu.data

import android.content.Context
import org.junit.After
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE, sdk = [28])
class OnlineChatBatchStoreTest {
    private val app: Context = RuntimeEnvironment.getApplication()
    private val role = "chat-burst-revision-test"

    @After fun reset() {
        OnlineChatBatchStore.cancel(app, role)
    }

    @Test fun threeBubblesBeforeReadingHaveOneRevisionAndAnExtendedDeadline() {
        OnlineChatBatchStore.cancel(app, role)
        val first = OnlineChatBatchStore.onUserBubble(app, role, 100_000L)
        assertEquals(100_000L + OnlineChatBatchStore.QUIET_MILLIS, first.dueAtMillis)
        val second = OnlineChatBatchStore.onUserBubble(app, role, 102_000L)
        val third = OnlineChatBatchStore.onUserBubble(app, role, 103_000L)
        assertEquals(first.revision, second.revision)
        assertEquals(first.revision, third.revision)
        assertEquals(108_000L, second.dueAtMillis)
        assertEquals(109_000L, third.dueAtMillis)
        assertFalse(OnlineChatBatchStore.claim(app, role, first.revision, 108_999L))
        assertTrue(OnlineChatBatchStore.claim(app, role, first.revision, third.dueAtMillis))
        OnlineChatBatchStore.finish(app, role, first.revision)
        assertNull(OnlineChatBatchStore.pendingDueAt(app, role))
    }

    @Test fun bubbleDuringModelGenerationSupersedesOldReplyWithoutLosingOriginals() {
        OnlineChatBatchStore.cancel(app, role)
        val old = OnlineChatBatchStore.onUserBubble(app, role, 100_000L)
        assertTrue(OnlineChatBatchStore.claim(app, role, old.revision, old.dueAtMillis))
        val newcomer = OnlineChatBatchStore.onUserBubble(app, role, old.dueAtMillis + 1_000L)
        assertNotEquals(old.revision, newcomer.revision)
        assertFalse(OnlineChatBatchStore.isCurrent(app, role, old.revision))
        assertTrue(OnlineChatBatchStore.isCurrent(app, role, newcomer.revision))
        assertEquals(old.dueAtMillis + 1_000L + OnlineChatBatchStore.QUIET_MILLIS,
            newcomer.dueAtMillis)
        assertFalse(OnlineChatBatchStore.claim(app, role, newcomer.revision, newcomer.dueAtMillis - 1))
        // Finishing the stale generation must not acknowledge or clear the
        // queued, combined unread turn which is now owned by the newer revision.
        OnlineChatBatchStore.finish(app, role, old.revision)
        assertEquals(newcomer.dueAtMillis,
            OnlineChatBatchStore.pendingDueAt(app, role))
        assertTrue(OnlineChatBatchStore.claim(app, role, newcomer.revision, newcomer.dueAtMillis))
        OnlineChatBatchStore.finish(app, role, newcomer.revision)
        assertNull(OnlineChatBatchStore.pendingDueAt(app, role))
    }

    @Test fun secondBubbleAtFiveSecondsRestartsSameSixSecondQuietWindowWithoutCallingFirstApi() {
        OnlineChatBatchStore.cancel(app, role)
        val first = OnlineChatBatchStore.onUserBubble(app, role, 100_000L)
        assertFalse(OnlineChatBatchStore.claim(app, role, first.revision, 105_000L))
        val second = OnlineChatBatchStore.onUserBubble(app, role, 105_000L)
        assertEquals(first.revision, second.revision)
        assertEquals(111_000L, second.dueAtMillis)
        assertFalse(OnlineChatBatchStore.claim(app, role, first.revision, 106_000L))
        assertTrue(OnlineChatBatchStore.claim(app, role, first.revision, 111_000L))
        OnlineChatBatchStore.finish(app, role, first.revision)
    }

    @Test fun threeBubblesAtFiveAndSixSecondsDoNotWaitTenAfterThird() {
        OnlineChatBatchStore.cancel(app, role)
        val first = OnlineChatBatchStore.onUserBubble(app, role, 100_000L)
        val second = OnlineChatBatchStore.onUserBubble(app, role, 105_000L)
        val third = OnlineChatBatchStore.onUserBubble(app, role, 106_000L)
        assertEquals(first.revision, third.revision)
        assertEquals(111_000L, second.dueAtMillis)
        assertEquals(112_000L, third.dueAtMillis)
        assertFalse(OnlineChatBatchStore.claim(app, role, third.revision, 109_000L))
        assertTrue(OnlineChatBatchStore.claim(app, role, third.revision, 112_000L))
        OnlineChatBatchStore.finish(app, role, third.revision)
    }

    @Test fun independentNonMessageWakeCannotAdvanceAnUnfinishedBubbleWindow() {
        OnlineChatBatchStore.cancel(app, role)
        val first = OnlineChatBatchStore.onUserBubble(app, role, 200_000L)
        val other = OnlineChatBatchStore.next(app, role, collectMessages = false, now = 201_000L)
        assertEquals(first, other)
        assertFalse(OnlineChatBatchStore.claim(app, role, first.revision, 202_000L))
        assertTrue(OnlineChatBatchStore.claim(app, role, first.revision, first.dueAtMillis))
        OnlineChatBatchStore.finish(app, role, first.revision)
    }
}
