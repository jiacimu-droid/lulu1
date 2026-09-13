package com.jiacimu.lulu.data

import android.content.Context
import com.jiacimu.lulu.LuluRepositories
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.time.Duration
import java.time.Instant

object ChatMemoryAutomation {
    private const val RETRY_POLL_MS = 60_000L
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val conversationJobs = mutableMapOf<String, Job>()
    private val characterLocks = mutableMapOf<String, Mutex>()
    private val processedReplyIds = mutableMapOf<String, String>()
    private var started = false

    @Synchronized
    fun initialize(context: Context) {
        if (started) return
        started = true
        MemoryExtractionJobStore.initialize(context)
        scope.launch { recoverPersistedAndTimelineBacklogs() }
        scope.launch {
            while (true) {
                delay(RETRY_POLL_MS)
                retryFailedPersistentJobs()
            }
        }
        scope.launch {
            MigratedDomainStores.chat.conversations.collect { conversations ->
                val liveIds = conversations.mapTo(mutableSetOf()) { conversation -> conversation.id }
                conversationJobs.keys
                    .filterNot { conversationId -> conversationId in liveIds }
                    .forEach { conversationId ->
                        conversationJobs.remove(conversationId)?.cancel()
                        processedReplyIds.remove(conversationId)
                    }

                conversations.forEach { conversation ->
                    if (conversation.id in conversationJobs) return@forEach
                    conversationJobs[conversation.id] = scope.launch {
                        MigratedDomainStores.chat.messages(conversation.id)
                            .drop(1)
                            .collect { messages ->
                                val latest = messages.lastOrNull() ?: return@collect
                                if (
                                    latest.sender != LuluChatMessage.Sender.Character ||
                                    latest.status != LuluChatMessage.Status.Sent ||
                                    processedReplyIds[conversation.id] == latest.id
                                ) return@collect

                                val characterId = latest.authorCharacterId ?: conversation.characterId
                                val policy = LuluRepositories.memory.observePolicy(characterId).first()
                                if (!policy.autoSummarize) return@collect

                                val job = MemoryExtractionJobStore.enqueue(characterId, conversation.id, latest.id)
                                val lock = lockFor(characterId)
                                lock.withLock {
                                    if (processedReplyIds[conversation.id] == latest.id) {
                                        MemoryExtractionJobStore.complete(job.id)
                                        return@withLock
                                    }
                                    runCatching { LuluRepositories.memory.summarizeNow(characterId) }
                                        .onSuccess {
                                            processedReplyIds[conversation.id] = latest.id
                                            MemoryExtractionJobStore.complete(job.id)
                                        }
                                        .onFailure { error -> MemoryExtractionJobStore.failed(job.id, error) }
                                }
                            }
                    }
                }
            }
        }
    }

    private suspend fun recoverPersistedAndTimelineBacklogs() {
        recoverJobs(MemoryExtractionJobStore.pending(), respectBackoff = false)

        // Shared timeline events (meetings, calls, world activity, moments, games) can fail extraction
        // outside chat. summarizeNow is threshold-aware, so this startup sweep is cheap when there is
        // no eligible backlog and gives failed non-chat events a durable recovery point.
        MigratedDomainStores.characters.settings.value.keys.forEach { characterId ->
            val policy = LuluRepositories.memory.observePolicy(characterId).first()
            if (!policy.autoSummarize) return@forEach
            lockFor(characterId).withLock {
                runCatching { LuluRepositories.memory.summarizeNow(characterId) }
            }
        }
    }

    private suspend fun retryFailedPersistentJobs() {
        val failed = MemoryExtractionJobStore.pending().filter { job -> job.attempts > 0 }
        if (failed.isNotEmpty()) recoverJobs(failed, respectBackoff = true)
    }

    private suspend fun recoverJobs(jobs: List<MemoryExtractionJob>, respectBackoff: Boolean) {
        val now = Instant.now()
        jobs.groupBy(MemoryExtractionJob::characterId).forEach { (characterId, characterJobs) ->
            val dueJobs = characterJobs.filter { job -> !respectBackoff || retryDue(job, now) }
            if (dueJobs.isEmpty()) return@forEach
            val policy = LuluRepositories.memory.observePolicy(characterId).first()
            if (!policy.autoSummarize) {
                dueJobs.forEach { job -> MemoryExtractionJobStore.complete(job.id) }
                return@forEach
            }
            lockFor(characterId).withLock {
                runCatching { LuluRepositories.memory.summarizeNow(characterId) }
                    .onSuccess { dueJobs.forEach { job -> MemoryExtractionJobStore.complete(job.id) } }
                    .onFailure { error -> dueJobs.forEach { job -> MemoryExtractionJobStore.failed(job.id, error) } }
            }
        }
    }

    private fun retryDue(job: MemoryExtractionJob, now: Instant): Boolean {
        val exponent = job.attempts.coerceIn(1, 6)
        val backoffSeconds = (30L * (1L shl exponent)).coerceAtMost(30L * 60L)
        return !job.updatedAt.isAfter(now) &&
            Duration.between(job.updatedAt, now).seconds >= backoffSeconds
    }

    private fun lockFor(characterId: String): Mutex = synchronized(characterLocks) {
        characterLocks.getOrPut(characterId) { Mutex() }
    }
}
