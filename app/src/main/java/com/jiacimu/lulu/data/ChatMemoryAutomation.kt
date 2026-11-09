package com.jiacimu.lulu.data

import android.content.Context
import com.jiacimu.lulu.LuluRepositories
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import java.time.Duration
import java.time.Instant

/** Canonical timeline writes enqueue here. Chat bubbles do not run a second extractor. */
object ChatMemoryAutomation {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val activeCharacters = mutableSetOf<String>()
    private var started = false

    @Synchronized
    fun initialize(context: Context) {
        if (started) return
        MemoryExtractionJobStore.initialize(context)
        MemorySemanticConsolidation.initialize(context)
        started = true
        scope.launch {
            // Recover pre-queue timelines too; summarizeNow is threshold aware.
            MigratedDomainStores.characters.settings.value.keys.forEach { request(it) }
            var lastTailCheck = Instant.EPOCH
            var lastMaintenanceCheck = Instant.EPOCH
            while (true) {
                delay(1_500)
                val now = Instant.now()
                if (Duration.between(lastTailCheck, now).seconds >= 60) {
                    lastTailCheck = now
                    enqueueIdleTails()
                }
                if (Duration.between(lastMaintenanceCheck, now).toMinutes() >= 60) {
                    lastMaintenanceCheck = now
                    // Periodic checks never block the extraction loop; overdue work resumes after launch.
                    val roles = MigratedDomainStores.characters.settings.value.keys.toList()
                    scope.launch {
                        roles.forEach { characterId ->
                            runCatching { MemorySemanticConsolidation.maintainWhenDue(characterId) }
                        }
                    }
                }
                MemoryExtractionJobStore.pending().filter { memoryExtractionRetryDue(it, now) }.forEach { job ->
                    val admitted = synchronized(activeCharacters) { activeCharacters.add(job.characterId) }
                    if (admitted) scope.launch {
                        try { drain(job.characterId) }
                        finally { synchronized(activeCharacters) { activeCharacters.remove(job.characterId) } }
                    }
                }
            }
        }
    }

    /** Persist before returning to the writer. A process death cannot lose the dirty watermark. */
    internal fun request(characterId: String, sourceEventId: String = "") {
        MemoryExtractionJobStore.enqueue(characterId, sourceReplyId = sourceEventId)
    }

    /** Durable drain; injectable boundaries keep failure and restart tests on the production loop. */
    internal suspend fun drain(
        characterId: String,
        autoSummarize: suspend (String) -> Boolean = { id ->
            LuluRepositories.memory.observePolicy(id).first().autoSummarize
        },
        summarize: suspend (String) -> Unit = { id -> LuluRepositories.memory.summarizeNow(id) },
    ) {
        while (true) {
            val job = MemoryExtractionJobStore.pending().firstOrNull { it.characterId == characterId } ?: return
            if (!memoryExtractionRetryDue(job)) return
            try {
                if (autoSummarize(characterId)) {
                    summarize(characterId)
                }
                // Events arriving during an API call increment revision and remain queued.
                MemoryExtractionJobStore.complete(job.id, job.revision)
            } catch (deferred: BackgroundModelBudgetDeferred) {
                // Optional paid extraction is deferred without failing or
                // clearing its original source-event watermark.
                MemoryExtractionJobStore.defer(job, deferred.resumeAt)
                return
            } catch (deferred: ResidentMemoryBudgetDeferred) {
                // A budget-deferred summarizer has NOT processed its batch.
                // Keep the dirty watermark; resume after local midnight rather
                // than spin every 1.5s, flood logs or spend past owner's cap.
                MemoryExtractionJobStore.defer(job, deferred.resumeAt)
                return
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Exception) {
                MemoryExtractionJobStore.failed(job, error)
                return
            }
        }
    }

    private fun enqueueIdleTails() {
        val queued = MemoryExtractionJobStore.pending().mapTo(mutableSetOf()) { it.characterId }
        MigratedDomainStores.characters.settings.value.keys.filterNot(queued::contains).forEach { characterId ->
            if (LuluRepositories.memory.shouldFlushPendingTail(characterId)) request(characterId)
        }
    }
}

internal fun memoryExtractionRetryDue(job: MemoryExtractionJob, now: Instant = Instant.now()): Boolean {
    // Quota deferral is a normal state, independent of network failure backoff.
    if (job.deferredUntil?.isAfter(now) == true) return false
    if (job.attempts == 0) return true
    // A malformed JSON batch or an ambiguous relay timeout can be billed.
    // After the first failure, never submit the unchanged batch every
    // 30 minutes forever. Slow down progressively, without EVER dropping it.
    val seconds = when (job.attempts) {
        1 -> 15L * 60
        2 -> 60L * 60
        3 -> 4L * 60 * 60
        4 -> 12L * 60 * 60
        else -> 24L * 60 * 60
    }
    return !job.updatedAt.isAfter(now) && Duration.between(job.updatedAt, now).seconds >= seconds
}
