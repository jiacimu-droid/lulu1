package com.jiacimu.lulu.data

import com.jiacimu.lulu.LuluRepositories
import com.jiacimu.lulu.core.DurationSummary
import com.jiacimu.lulu.core.MemoryEntry
import com.jiacimu.lulu.core.MemoryKind
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch
import java.time.Instant

/**
 * Keeps the performance duration summary available to every character through the normal
 * companion context path. The record uses a stable id, so updates replace one system fact
 * instead of producing an endless stream of duplicate memories.
 */
object RoleReadablePerformanceBridge {
    private var scope: CoroutineScope? = null

    fun initialize() {
        if (scope != null) return
        scope = CoroutineScope(SupervisorJob() + Dispatchers.Default).also { bridgeScope ->
            bridgeScope.launch {
                combine(
                    LuluRepositories.performance.observeDurations(),
                    MigratedDomainStores.characters.settings,
                ) { durations, characters -> durations to characters.values.toList() }
                    .collectLatest { (durations, characters) ->
                        if (durations.isEmpty()) return@collectLatest
                        val now = Instant.now()
                        characters.forEach { character ->
                            LuluRepositories.memory.save(
                                MemoryEntry(
                                    id = "system-duration-${character.characterId}",
                                    characterId = character.characterId,
                                    content = durations.toCompanionFact(),
                                    kind = MemoryKind.Fact,
                                    source = "性能监测",
                                    occurredAt = now,
                                    createdAt = now,
                                    strength = 10,
                                    pinned = true,
                                    canRecallProactively = true,
                                ),
                            )
                        }
                    }
            }
        }
    }

    fun shutdown() {
        scope?.cancel()
        scope = null
    }

    private fun DurationSummary.isEmpty(): Boolean =
        studyMinutes <= 0 && chatMinutes <= 0 && callMinutes <= 0

    private fun DurationSummary.toCompanionFact(): String =
        "性能监测当前记录：主人累计有效学习 ${studyMinutes.coerceAtLeast(0)} 分钟，" +
            "聊天 ${chatMinutes.coerceAtLeast(0)} 分钟，通话 ${callMinutes.coerceAtLeast(0)} 分钟。" +
            "这是应用内累计数据，角色回答相关问题时应直接使用，不要猜测。"
}
