package com.jiacimu.lulu.data

import com.jiacimu.lulu.LuluRepositories
import com.jiacimu.lulu.core.LexiconSection
import com.jiacimu.lulu.core.LexiconStatus
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

/** Keeps 辞海 as the human-facing view while CommitmentTaskStore remains the execution source of truth. */
object CommitmentLexiconSync {
    private const val MARKER = "\n\n【任务进度】"
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private var started = false

    @Synchronized
    fun initialize() {
        if (started) return
        started = true
        scope.launch {
            CommitmentTaskStore.tasks.collectLatest { tasks ->
                tasks.forEach { task -> syncTask(task) }
            }
        }
        scope.launch {
            while (true) {
                delay(30_000L)
                reconcileDeletedLexiconEntries()
                refreshActiveEntries()
            }
        }
    }

    private suspend fun syncTask(task: CommitmentTask) {
        val lexiconId = task.lexiconEntryId ?: return
        val current = LuluRepositories.lexicon.snapshot(task.characterId).firstOrNull { it.id == lexiconId }
        if (current == null) {
            if (task.status.isActive()) CommitmentTaskStore.cancel(task.id, "对应辞海约定已删除")
            return
        }
        if (current.section != LexiconSection.Promise) return
        val base = current.content.substringBefore(MARKER).trim().ifBlank { task.goal }
        val progress = buildString {
            append(base)
            append(MARKER)
            append('\n').append("状态：").append(task.status.displayName())
            task.dueAt?.let { due ->
                val zone = task.timezone
                    ?.let { value -> runCatching { ZoneId.of(value) }.getOrNull() }
                    ?: ZoneId.systemDefault()
                append('\n').append("计划时间：")
                append(due.atZone(zone).format(TASK_TIME_FORMATTER))
            }
            task.nextCheckAt?.takeIf { it != task.dueAt }?.let { next ->
                append('\n').append("下次检查：")
                append(next.atZone(ZoneId.systemDefault()).format(TASK_TIME_FORMATTER))
            }
            if (task.currentStep > 0 && task.steps.isNotEmpty()) {
                append('\n').append("步骤：${task.currentStep.coerceAtMost(task.steps.size)}/${task.steps.size}")
            }
            if (task.lastActionResult.isNotBlank()) append('\n').append("最近结果：${task.lastActionResult}")
        }
        val status = when {
            task.status.isActive() -> LexiconStatus.Active
            task.status == CommitmentTaskStatus.Completed -> LexiconStatus.Resolved
            else -> LexiconStatus.Archived
        }
        if (current.content == progress && current.status == status) return
        LuluRepositories.lexicon.save(current.copy(content = progress, status = status, updatedAt = task.updatedAt))
    }

    private fun reconcileDeletedLexiconEntries() {
        CommitmentTaskStore.snapshot().filter { it.status.isActive() && it.lexiconEntryId != null }.forEach { task ->
            val exists = LuluRepositories.lexicon.snapshot(task.characterId).any { it.id == task.lexiconEntryId }
            if (!exists) CommitmentTaskStore.cancel(task.id, "对应辞海约定已删除")
        }
    }

    private suspend fun refreshActiveEntries() {
        val cutoff = Instant.now().minusSeconds(30 * 60L)
        CommitmentTaskStore.snapshot().filter { it.status.isActive() }.forEach { task ->
            val lexiconId = task.lexiconEntryId ?: return@forEach
            val entry = LuluRepositories.lexicon.snapshot(task.characterId).firstOrNull { it.id == lexiconId } ?: return@forEach
            if (entry.updatedAt.isBefore(cutoff)) LuluRepositories.lexicon.save(entry.copy(updatedAt = Instant.now()))
        }
    }
}

private fun CommitmentTaskStatus.displayName(): String = when (this) {
    CommitmentTaskStatus.NeedsClarification -> "待明确"
    CommitmentTaskStatus.Scheduled -> "已安排"
    CommitmentTaskStatus.Running -> "执行中"
    CommitmentTaskStatus.WaitingForFeedback -> "等待反馈"
    CommitmentTaskStatus.Completed -> "已完成"
    CommitmentTaskStatus.Cancelled -> "已取消"
    CommitmentTaskStatus.Blocked -> "受阻"
    CommitmentTaskStatus.Expired -> "已过期"
}

private val TASK_TIME_FORMATTER = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm")
