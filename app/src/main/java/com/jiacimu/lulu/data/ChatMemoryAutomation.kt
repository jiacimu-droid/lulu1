package com.jiacimu.lulu.data

import com.jiacimu.lulu.LuluRepositories
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * Observes persisted conversations and starts memory extraction only after a newly appended
 * character reply. Existing messages loaded from disk are skipped, and each character has a
 * mutex so overlapping replies cannot create duplicate extraction batches.
 */
object ChatMemoryAutomation {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val conversationJobs = mutableMapOf<String, Job>()
    private val characterLocks = mutableMapOf<String, Mutex>()
    private var started = false

    @Synchronized
    fun initialize() {
        if (started) return
        started = true
        scope.launch {
            MigratedDomainStores.chat.conversations.collect { conversations ->
                val liveIds = conversations.mapTo(mutableSetOf()) { conversation -> conversation.id }
                conversationJobs.keys
                    .filterNot { conversationId -> conversationId in liveIds }
                    .forEach { conversationId -> conversationJobs.remove(conversationId)?.cancel() }

                conversations.forEach { conversation ->
                    if (conversation.id in conversationJobs) return@forEach
                    conversationJobs[conversation.id] = scope.launch {
                        MigratedDomainStores.chat.messages(conversation.id)
                            .drop(1)
                            .collect { messages ->
                                val latest = messages.lastOrNull() ?: return@collect
                                if (latest.sender != LuluChatMessage.Sender.Character ||
                                    latest.status != LuluChatMessage.Status.Sent
                                ) {
                                    return@collect
                                }
                                val characterId = conversation.characterId
                                val policy = LuluRepositories.memory
                                    .observePolicy(characterId)
                                    .first()
                                if (!policy.autoSummarize) return@collect

                                val lock = synchronized(characterLocks) {
                                    characterLocks.getOrPut(characterId) { Mutex() }
                                }
                                lock.withLock {
                                    LuluRepositories.memory.summarizeNow(characterId)
                                }
                            }
                    }
                }
            }
        }
    }
}
