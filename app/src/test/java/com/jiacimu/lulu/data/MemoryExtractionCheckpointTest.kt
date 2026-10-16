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
        assertTrue(memoryExtractionRetryDue(restored, restored.updatedAt.plusSeconds(61)))
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
