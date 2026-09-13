package com.jiacimu.lulu.data

import com.jiacimu.lulu.LuluRepositories
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * Closes the delete-vs-in-flight-extraction race.
 *
 * A memory model response may finish after one of its source timeline events was deleted. Deletion
 * already removes existing derived memories immediately; this integrity sweep catches a late write
 * that arrives afterwards and tombstones it again before it can remain in long-term recall.
 */
internal object MemorySourceIntegrityRuntime {
    private const val SWEEP_INTERVAL_MS = 20_000L
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var started = false

    @Synchronized
    fun initialize() {
        if (started) return
        started = true
        scope.launch {
            while (true) {
                sweepAllCharacters()
                delay(SWEEP_INTERVAL_MS)
            }
        }
    }

    private suspend fun sweepAllCharacters() {
        MigratedDomainStores.characters.settings.value.keys.forEach { characterId ->
            val liveIds = SharedExperienceTimeline.all(characterId)
                .mapTo(mutableSetOf(), SharedTimelineEvent::id)
            LuluRepositories.memory.snapshot(characterId).forEach { memory ->
                val sourceIds = memory.integritySourceEventIds()
                if (sourceIds.isNotEmpty() && sourceIds.any { it !in liveIds }) {
                    LuluRepositories.memory.delete(memory.id)
                    MemoryValidityStore.removeMemory(memory.id)
                }
            }
        }
    }
}

private fun com.jiacimu.lulu.core.MemoryEntry.integritySourceEventIds(): List<String> = when {
    source.startsWith("timeline-events:") -> source.removePrefix("timeline-events:").split('|')
    source.startsWith("timeline-batch:") -> source.removePrefix("timeline-batch:").split('|')
    else -> emptyList()
}.map(String::trim).filter(String::isNotBlank).distinct()
