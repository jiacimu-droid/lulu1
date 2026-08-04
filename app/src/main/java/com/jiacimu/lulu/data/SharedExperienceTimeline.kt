package com.jiacimu.lulu.data

import com.jiacimu.lulu.LuluRepositories
import com.jiacimu.lulu.core.MemoryEntry
import com.jiacimu.lulu.core.MemoryKind
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import java.time.Instant

/**
 * A single persistent timeline shared by chat, calls, study, games and reading.
 * Feature screens write durable events here; normal memory recall makes them
 * available to later conversations without injecting the whole history.
 */
object SharedExperienceTimeline {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    fun remember(
        memoryId: String,
        characterId: String,
        label: String,
        detail: String,
        occurredAt: Instant = Instant.now(),
        strength: Int = 5,
        source: String = "shared-experience",
    ) {
        val cleanDetail = detail.trim()
        if (memoryId.isBlank() || characterId.isBlank() || cleanDetail.isBlank()) return
        scope.launch {
            LuluRepositories.memory.upsert(
                MemoryEntry(
                    id = memoryId,
                    characterId = characterId,
                    content = "$label：${cleanDetail.take(2_400)}",
                    kind = MemoryKind.Timeline,
                    source = source,
                    occurredAt = occurredAt,
                    createdAt = Instant.now(),
                    strength = strength.coerceIn(1, 10),
                    pinned = false,
                    canRecallProactively = true,
                ),
            )
        }
    }
}
