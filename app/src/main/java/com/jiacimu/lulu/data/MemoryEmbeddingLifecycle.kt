package com.jiacimu.lulu.data

import com.jiacimu.lulu.LuluRepositories
import com.jiacimu.lulu.core.MemoryEntry
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * Keeps the persistent embedding index aligned with memory lifecycle events.
 *
 * New or edited memories are embedded ahead of recall, while deleted, disabled or superseded
 * memories lose their vector files. Recall still has a bounded on-demand fallback for resilience.
 */
internal object MemoryEmbeddingLifecycle {
    private const val BATCH_SIZE = 24
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val characterJobs = mutableMapOf<String, Job>()
    private val signatures = mutableMapOf<String, Map<String, Int>>()
    private val indexMutex = Mutex()
    private var started = false

    @Synchronized
    fun initialize() {
        if (started) return
        started = true
        scope.launch {
            MigratedDomainStores.characters.settings.collectLatest { settings ->
                val live = settings.keys
                characterJobs.keys.filterNot(live::contains).forEach { characterId ->
                    characterJobs.remove(characterId)?.cancel()
                    signatures.remove(characterId)?.keys?.forEach(MemoryEmbeddingIndex::removeMemory)
                }
                live.forEach { characterId ->
                    if (characterId in characterJobs) return@forEach
                    characterJobs[characterId] = scope.launch {
                        LuluRepositories.memory.observeMemories(characterId).collectLatest { memories ->
                            sync(characterId, memories)
                        }
                    }
                }
            }
        }
    }

    private suspend fun sync(characterId: String, memories: List<MemoryEntry>) = indexMutex.withLock {
        val active = memories.filter { memory ->
            memory.canRecallProactively &&
                MemoryValidityStore.isActive(memory.id) &&
                DigitalLifeProfileStore.allowsTimestamp(characterId, memory.occurredAt ?: memory.createdAt)
        }
        val previous = signatures[characterId].orEmpty()
        val current = active.associate { memory -> memory.id to signature(memory) }
        (previous.keys - current.keys).forEach(MemoryEmbeddingIndex::removeMemory)

        if (!MemoryModelRuntime.vectorEnabled()) {
            // Do not mark the active memories as indexed while vectors are disabled. If the setting
            // is later enabled, the next memory emission or app restart will index the full set.
            signatures[characterId] = previous.filterKeys(current::containsKey)
            return@withLock
        }
        val connection = MemoryModelRuntime.embeddingConnection() ?: return@withLock
        val changed = active.filter { memory -> previous[memory.id] != current[memory.id] }
        changed.chunked(BATCH_SIZE).forEach { batch ->
            val missing = batch.filter { memory ->
                val key = MemoryEmbeddingIndex.key(connection, memory)
                MemoryEmbeddingIndex.get(key, memory.id) == null
            }
            if (missing.isEmpty()) return@forEach
            val vectors = com.jiacimu.lulu.ai.LuluAiServices.gateway
                .embed(connection, missing.map(MemoryEntry::content))
                .getOrNull()
                ?.takeIf { it.size == missing.size }
                ?: return@forEach
            missing.forEachIndexed { index, memory ->
                MemoryEmbeddingIndex.put(
                    MemoryEmbeddingIndex.key(connection, memory),
                    memory.id,
                    vectors[index],
                )
            }
        }
        signatures[characterId] = current
    }

    private fun signature(memory: MemoryEntry): Int = 31 * memory.content.hashCode() +
        17 * memory.content.length + memory.canRecallProactively.hashCode()
}
