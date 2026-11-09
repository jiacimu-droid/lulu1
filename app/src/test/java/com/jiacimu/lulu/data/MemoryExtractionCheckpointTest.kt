package com.jiacimu.lulu.data

import android.content.Context
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Before
import org.junit.After
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import java.time.Instant

@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE, sdk = [28])
class MemoryExtractionCheckpointTest {
    @Before fun before() { MemoryTestEnvironment.release(); MemoryExtractionJobStore.initialize(RuntimeEnvironment.getApplication()) }
    @After fun after() { MemoryTestEnvironment.release() }

    @Test fun messageBurstCoalescesAndAnOlderCompletionCannotLoseNewWork() {
        val old = MemoryExtractionJobStore.enqueue("role", sourceReplyId = "bubble-1")
        repeat(30) { MemoryExtractionJobStore.enqueue("role", sourceReplyId = "bubble-${it + 2}") }
        assertEquals(1, MemoryExtractionJobStore.pending().size)
        MemoryExtractionJobStore.complete(old)
        val pending = MemoryExtractionJobStore.pending().single()
        assertEquals(31L, pending.revision)
        assertEquals("bubble-31", pending.sourceReplyId)
        MemoryExtractionJobStore.failed(old, IllegalStateException("late failure"))
        assertEquals(0, MemoryExtractionJobStore.pending().single().attempts)
        MemoryExtractionJobStore.complete(pending)
        assertTrue(MemoryExtractionJobStore.pending().isEmpty())
        val newLife = MemoryExtractionJobStore.enqueue("role", sourceReplyId = "new")
        MemoryExtractionJobStore.complete(old) // No ABA collision after a completed queue was recreated.
        assertEquals(newLife, MemoryExtractionJobStore.pending().single())
    }

    @Test fun networkFailureDuringMessageBurstStillBacksOffWithoutLosingNewWork() {
        val old = MemoryExtractionJobStore.enqueue("role", sourceReplyId = "first")
        MemoryExtractionJobStore.enqueue("role", sourceReplyId = "arrived-during-post")
        MemoryExtractionJobStore.failed(old, java.io.IOException("connection closed"))
        val pending = MemoryExtractionJobStore.pending().single()
        assertEquals(2L, pending.revision)
        assertEquals("arrived-during-post", pending.sourceReplyId)
        assertEquals(1, pending.attempts)
        assertFalse(memoryExtractionRetryDue(pending, pending.updatedAt.plusSeconds(2)))
        MemoryExtractionJobStore.complete(old)
        assertEquals(pending, MemoryExtractionJobStore.pending().single())
        MemoryExtractionJobStore.complete(pending)
        val recreated = MemoryExtractionJobStore.enqueue("role", sourceReplyId = "new-life")
        MemoryExtractionJobStore.failed(old, java.io.IOException("late old socket"))
        assertEquals(recreated, MemoryExtractionJobStore.pending().single())
    }

    @Test fun residentQuotaDeferralSurvivesNewMessagesAndColdStartUntilNextBudgetDay() {
        val context = RuntimeEnvironment.getApplication() as Context
        val first = MemoryExtractionJobStore.enqueue("resident:quota-resume", sourceReplyId = "first")
        val resumeAt = Instant.now().plusSeconds(3_600)
        MemoryExtractionJobStore.defer(first, resumeAt)
        var deferred = MemoryExtractionJobStore.pending().single()
        assertEquals(first.id, deferred.id)
        assertEquals(first.revision, deferred.revision)
        assertEquals(0, deferred.attempts)
        assertEquals("", deferred.lastError)
        assertEquals(resumeAt, deferred.deferredUntil)
        assertFalse(memoryExtractionRetryDue(deferred, resumeAt.minusNanos(1)))
        assertTrue(memoryExtractionRetryDue(deferred, resumeAt))
        // New source events are still dirty, but cannot override the cost cap.
        MemoryExtractionJobStore.enqueue("resident:quota-resume", sourceReplyId = "arrived-while-deferred")
        deferred = MemoryExtractionJobStore.pending().single()
        assertEquals(2L, deferred.revision)
        assertEquals(resumeAt, deferred.deferredUntil)
        MemoryExtractionJobStore.complete(first)
        assertEquals(deferred, MemoryExtractionJobStore.pending().single())
        MemoryTestEnvironment.release()
        MemoryExtractionJobStore.initialize(context)
        val restored = MemoryExtractionJobStore.pending().single()
        assertEquals("arrived-while-deferred", restored.sourceReplyId)
        assertEquals(deferred.revision, restored.revision)
        assertEquals(resumeAt, restored.deferredUntil)
        assertFalse(memoryExtractionRetryDue(restored, resumeAt.minusSeconds(1)))
        assertTrue(memoryExtractionRetryDue(restored, resumeAt))
        MemoryExtractionJobStore.complete(restored)
        assertTrue(MemoryExtractionJobStore.pending().isEmpty())
    }

    @Test fun deferredInFlightMemoryFailureKeepsLaterEventsAcrossRestart() {
        val context = RuntimeEnvironment.getApplication() as Context
        val inFlight = MemoryExtractionJobStore.enqueue("memory:relay-deferred", sourceReplyId = "first-evidence")
        val later = MemoryExtractionJobStore.enqueue("memory:relay-deferred", sourceReplyId = "second-evidence")
        val resumeAt = Instant.now().plusSeconds(15L * 60)
        // The network request was based on the older revision; a relay budget
        // deferral must not erase the event that arrived while it was running.
        MemoryExtractionJobStore.defer(inFlight, resumeAt)
        val pending = MemoryExtractionJobStore.pending().single()
        assertEquals(later.id, pending.id)
        assertEquals(later.revision, pending.revision)
        assertEquals("second-evidence", pending.sourceReplyId)
        assertEquals(0, pending.attempts)
        assertEquals(resumeAt, pending.deferredUntil)
        MemoryExtractionJobStore.complete(inFlight)
        assertEquals(pending, MemoryExtractionJobStore.pending().single())
        MemoryTestEnvironment.release()
        MemoryExtractionJobStore.initialize(context)
        val restored = MemoryExtractionJobStore.pending().single()
        assertEquals("second-evidence", restored.sourceReplyId)
        assertEquals(later.revision, restored.revision)
        assertFalse(memoryExtractionRetryDue(restored, resumeAt.minusNanos(1)))
        assertTrue(memoryExtractionRetryDue(restored, resumeAt))
        MemoryExtractionJobStore.complete(restored)
        assertTrue(MemoryExtractionJobStore.pending().isEmpty())
    }

    @Test fun dirtyRoleSurvivesSourceDeletionAndRestartWithBackoff() {
        val context = RuntimeEnvironment.getApplication() as Context
        MemoryExtractionJobStore.enqueue("role", sourceReplyId = "older")
        val old = MemoryExtractionJobStore.enqueue("role", sourceReplyId = "deleted")
        MemoryExtractionJobStore.removeBySourceEvent("deleted")
        MemoryExtractionJobStore.complete(old)
        val pending = MemoryExtractionJobStore.pending().single()
        MemoryExtractionJobStore.failed(pending, IllegalStateException("offline"))
        MemoryTestEnvironment.release()
        MemoryExtractionJobStore.initialize(context)
        val restored = MemoryExtractionJobStore.pending().single()
        assertEquals(1, restored.attempts)
        assertFalse(memoryExtractionRetryDue(restored, restored.updatedAt.plusSeconds(30)))
        assertFalse(memoryExtractionRetryDue(restored, restored.updatedAt.plusSeconds(61)))
        assertTrue(memoryExtractionRetryDue(restored, restored.updatedAt.plusSeconds(15L * 60)))
    }

    @Test fun unchangedFailedBatchNeverRetriesEveryHalfHourForeverAndIsNotLost() {
        val now = Instant.parse("2026-11-08T10:00:00Z")
        val job = MemoryExtractionJob(id = "memory:slow-retry",
            characterId = "slow-retry", attempts = 8, updatedAt = now)
        assertFalse(memoryExtractionRetryDue(job, now.plusSeconds(60L * 60)))
        assertFalse(memoryExtractionRetryDue(job, now.plusSeconds(23L * 60 * 60)))
        assertTrue(memoryExtractionRetryDue(job, now.plusSeconds(24L * 60 * 60)))
    }

    @Test fun persistenceDoesNotSilentlyEvictAnOlderRoleBeyondThreeHundredJobs() {
        val context = RuntimeEnvironment.getApplication() as Context
        repeat(305) { MemoryExtractionJobStore.enqueue("role-$it", sourceReplyId = "evidence") }
        MemoryTestEnvironment.release()
        MemoryExtractionJobStore.initialize(context)
        assertEquals(305, MemoryExtractionJobStore.pending().size)
        assertTrue(MemoryExtractionJobStore.pending().any { it.characterId == "role-0" })
    }
}
