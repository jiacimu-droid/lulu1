package com.jiacimu.lulu.data

import com.jiacimu.lulu.LuluRepositories
import com.jiacimu.lulu.core.DurationSummary
import com.jiacimu.lulu.core.MemoryEntry
import com.jiacimu.lulu.core.MemoryKind
import com.jiacimu.lulu.study.PostgraduateExamStores
import com.jiacimu.lulu.study.StudyState
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch
import java.time.DayOfWeek
import java.time.Instant
import java.time.LocalDate
import java.time.temporal.TemporalAdjusters

/**
 * Keeps calendar-scoped study duration facts available to every character through the normal
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
                    PostgraduateExamStores.main.state,
                ) { durations, characters, studyState ->
                    Triple(durations, characters.values.toList(), studyState)
                }.collectLatest { (durations, characters, studyState) ->
                    if (durations.isEmpty() && studyState.profile.totalStudyMinutes <= 0) return@collectLatest
                    val now = Instant.now()
                    val today = LocalDate.now()
                    characters.forEach { character ->
                        LuluRepositories.memory.save(
                            MemoryEntry(
                                id = "system-duration-${character.characterId}",
                                characterId = character.characterId,
                                content = durations.toCompanionFact(studyState, today),
                                kind = MemoryKind.Fact,
                                source = "学习与时长统计",
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

    private fun DurationSummary.toCompanionFact(studyState: StudyState, today: LocalDate): String {
        val weekStart = today.with(TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY))
        val weekEnd = weekStart.plusDays(6)
        val todayMinutes = studyState.dailyStudyMinutes[today.toString()]?.coerceAtLeast(0) ?: 0
        val weekMinutes = (0L..6L).sumOf { offset ->
            studyState.dailyStudyMinutes[weekStart.plusDays(offset).toString()]?.coerceAtLeast(0) ?: 0
        }
        val totalMinutes = studyState.profile.totalStudyMinutes.coerceAtLeast(0)
        return buildString {
            appendLine("学习时长（设备本地日期 $today）")
            appendLine("今天：$todayMinutes 分钟")
            appendLine("本周（$weekStart 至 $weekEnd）：$weekMinutes 分钟")
            appendLine("历史累计：$totalMinutes 分钟")
            append("应用内累计：聊天 ${chatMinutes.coerceAtLeast(0)} 分钟，通话 ${callMinutes.coerceAtLeast(0)} 分钟")
        }
    }
}
