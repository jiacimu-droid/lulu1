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

    @Test fun realDrainDefersAnInFlightExtractionAndResumesNewRevisionAfterReopen() = runBlocking {
        val context = RuntimeEnvironment.getApplication() as Context
        val role = "memory:drain-restart"
        val first = MemoryExtractionJobStore.enqueue(role, sourceReplyId = "source-before-post")
        val resumeAt = Instant.now().plusSeconds(3_600)
        var calls = 0
        ChatMemoryAutomation.drain(
            role,
            autoSummarize = { true },
            summarize = {
                calls++
                MemoryExtractionJobStore.enqueue(role, sourceReplyId = "arrived-during-post")
                throw BackgroundModelBudgetDeferred(resumeAt)
            },
        )
        val deferred = MemoryExtractionJobStore.pending().single()
        assertEquals(1, calls)
        assertEquals(first.id, deferred.id)
        assertEquals(2L, deferred.revision)
        assertEquals("arrived-during-post", deferred.sourceReplyId)
        assertEquals(resumeAt, deferred.deferredUntil)
        assertEquals(0, deferred.attempts)

        MemoryTestEnvironment.release()
        MemoryExtractionJobStore.initialize(context)
        ChatMemoryAutomation.drain(
            role, autoSummarize = { true },
            summarize = { calls++ },
            now = { resumeAt.minusNanos(1) },
        )
        assertEquals("Before the persisted deadline there must be no paid attempt", 1, calls)
        assertEquals(1, MemoryExtractionJobStore.pending().size)

        ChatMemoryAutomation.drain(
            role, autoSummarize = { true },
            summarize = { calls++ },
            now = { resumeAt },
        )
        assertEquals(2, calls)
        assertTrue(MemoryExtractionJobStore.pending().isEmpty())
    }

    @Test fun realDrainNetworkFailureRetainsNewEvidenceAndWaitsThroughRestart() = runBlocking {
        val context = RuntimeEnvironment.getApplication() as Context
        val role = "memory:drain-network-failure"
        val first = MemoryExtractionJobStore.enqueue(role, sourceReplyId = "before-network")
        var attempts = 0
        ChatMemoryAutomation.drain(
            role,
            autoSummarize = { true },
            summarize = {
                attempts++
                MemoryExtractionJobStore.enqueue(role, sourceReplyId = "during-network")
                throw java.io.IOException("ambiguous relay disconnect")
            },
        )
        val failed = MemoryExtractionJobStore.pending().single()
        assertEquals(1, attempts)
        assertEquals(first.id, failed.id)
        assertEquals(2L, failed.revision)
        assertEquals("during-network", failed.sourceReplyId)
        assertEquals(1, failed.attempts)
        val retryAt = failed.updatedAt.plusSeconds(15L * 60)

        MemoryTestEnvironment.release()
        MemoryExtractionJobStore.initialize(context)
        ChatMemoryAutomation.drain(
            role, autoSummarize = { true },
            summarize = { attempts++ },
            now = { retryAt.minusNanos(1) },
        )
        assertEquals("An ambiguous HTTP failure must not buy an immediate repeat", 1, attempts)
        assertEquals("during-network", MemoryExtractionJobStore.pending().single().sourceReplyId)

        ChatMemoryAutomation.drain(
            role, autoSummarize = { true },
            summarize = { attempts++ },
            now = { retryAt },
        )
        assertEquals(2, attempts)
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
    @Test fun cancelledDrainKeepsInFlightEvidenceWithoutChargingASecondAttempt() = runBlocking {
        val context = RuntimeEnvironment.getApplication() as Context
        val role = "memory:cancelled-drain"
        val first = MemoryExtractionJobStore.enqueue(role, sourceReplyId = "before-cancel")
        var attempts = 0
        try {
            ChatMemoryAutomation.drain(
                role, autoSummarize = { true },
                summarize = {
                    attempts++
                    MemoryExtractionJobStore.enqueue(role, sourceReplyId = "arrived-before-cancel")
                    throw kotlinx.coroutines.CancellationException("worker stopped")
                },
            )
            fail("Cancellation must propagate to the worker")
        } catch (_: kotlinx.coroutines.CancellationException) {
            // Cancellation is not an extraction failure or a successful checkpoint.
        }
        val pending = MemoryExtractionJobStore.pending().single()
        assertEquals(first.id, pending.id)
        assertEquals(2L, pending.revision)
        assertEquals("arrived-before-cancel", pending.sourceReplyId)
        assertEquals(0, pending.attempts)
        assertEquals(1, attempts)
        MemoryTestEnvironment.release()
        MemoryExtractionJobStore.initialize(context)
        val restored = MemoryExtractionJobStore.pending().single()
        assertEquals(pending.id, restored.id)
        assertEquals(pending.revision, restored.revision)
        assertEquals("arrived-before-cancel", restored.sourceReplyId)
        ChatMemoryAutomation.drain(
            role, autoSummarize = { true },
            summarize = { attempts++ },
        )
        assertEquals(2, attempts)
        assertTrue(MemoryExtractionJobStore.pending().isEmpty())
    }

    @Test fun ambiguousHttpFailureBacksOffNewRevisionButInputRejectionDoesNotPoisonIt() {
        val serverFailure = MemoryExtractionJobStore.enqueue(
            "memory:http-503-revision", sourceReplyId = "first")
        MemoryExtractionJobStore.enqueue("memory:http-503-revision", sourceReplyId = "newer")
        MemoryExtractionJobStore.failed(serverFailure,
            com.jiacimu.lulu.ai.ModelHttpException(503, "relay unavailable"))
        val retained = MemoryExtractionJobStore.pending().single()
        assertEquals(2L, retained.revision)
        assertEquals("newer", retained.sourceReplyId)
        assertEquals(1, retained.attempts)
        assertFalse(memoryExtractionRetryDue(retained, retained.updatedAt.plusSeconds(60)))

        MemoryExtractionJobStore.complete(retained)
        val rejected = MemoryExtractionJobStore.enqueue(
            "memory:http-400-revision", sourceReplyId = "old")
        MemoryExtractionJobStore.enqueue("memory:http-400-revision", sourceReplyId = "new")
        MemoryExtractionJobStore.failed(rejected,
            com.jiacimu.lulu.ai.ModelHttpException(400, "invalid old payload"))
        val newer = MemoryExtractionJobStore.pending().single()
        assertEquals(2L, newer.revision)
        assertEquals(0, newer.attempts)
        assertEquals("new", newer.sourceReplyId)
    }

    @Test fun realGateway503FailurePersistsDirtyMemoryWithoutImmediateSecondPost() = runBlocking {
        val context = RuntimeEnvironment.getApplication() as Context
        com.jiacimu.lulu.LuluRepositories.initialize(context)
        val role = "memory:gateway-503-drain-" + java.util.UUID.randomUUID()
        val server = okhttp3.mockwebserver.MockWebServer()
        server.enqueue(okhttp3.mockwebserver.MockResponse().setResponseCode(503)
            .setBody("""{"error":{"message":"Relay request body memory protection"}}"""))
        server.enqueue(okhttp3.mockwebserver.MockResponse().setResponseCode(200)
            .setBody("""{"choices":[{"message":{"content":"unexpected duplicate"}}]}"""))
        server.start()
        try {
            val gateway = com.jiacimu.lulu.ai.CompanionModelGateway(
                com.jiacimu.lulu.ai.ModelConnectionStore.create(context))
            val connection = com.jiacimu.lulu.ai.ModelConnection(
                server.url("/").toString(), "key", "test")
            val original = MemoryExtractionJobStore.enqueue(role, sourceReplyId = "original-evidence")
            ChatMemoryAutomation.drain(
                role, autoSummarize = { true },
                summarize = {
                    MemoryExtractionJobStore.enqueue(role, sourceReplyId = "arrived-during-503")
                    gateway.generate(
                        role, "verifiable original evidence", "summarize", "记忆", "连续记忆提取",
                        contextMode = com.jiacimu.lulu.ai.CompanionContextMode.Isolated,
                        connectionOverride = connection,
                    ).getOrThrow()
                },
            )
            assertEquals("Ambiguous relay failure must send exactly one POST", 1, server.requestCount)
            val failed = MemoryExtractionJobStore.pending().single()
            assertEquals(original.id, failed.id)
            assertEquals(2L, failed.revision)
            assertEquals("arrived-during-503", failed.sourceReplyId)
            assertEquals(1, failed.attempts)
            val retryAt = failed.updatedAt.plusSeconds(15L * 60)
            MemoryTestEnvironment.release()
            MemoryExtractionJobStore.initialize(context)
            ChatMemoryAutomation.drain(
                role, autoSummarize = { true },
                summarize = { error("Must not retry before persisted deadline") },
                now = { retryAt.minusNanos(1) },
            )
            assertEquals(1, server.requestCount)
            assertEquals("arrived-during-503", MemoryExtractionJobStore.pending().single().sourceReplyId)
            // A later permitted successful extraction consumes the surviving revision.
            ChatMemoryAutomation.drain(
                role, autoSummarize = { true },
                summarize = { },
                now = { retryAt },
            )
            assertTrue(MemoryExtractionJobStore.pending().isEmpty())
            assertEquals("No hidden network replay during recovery", 1, server.requestCount)
        } finally {
            server.shutdown()
        }
    }

}
