package com.jiacimu.lulu.data

import com.jiacimu.lulu.LuluRepositories
import com.jiacimu.lulu.core.LexiconEntry
import com.jiacimu.lulu.core.PromiseKind
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

/** Synchronizes legacy promise entries while CommitmentTaskStore remains the executable authority; the visible destination is 日程. */
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

    internal suspend fun syncTask(task: CommitmentTask) {
        // Repair legacy/tool-created commitments that were persisted in the
        // execution store without any linked legacy record. Write the durable
        // entry first, then link the task, so a process restart can reconcile.
        if (task.lexiconEntryId == null) {
            val id = "scheduled-task-${task.id}"
            if (LuluRepositories.lexicon.snapshot(task.characterId).none { it.id == id }) {
                LuluRepositories.lexicon.save(
                    LexiconEntry(
                        id = id,
                        characterId = task.characterId,
                        section = LexiconSection.Promise,
                        title = task.goal.take(24).ifBlank { "定时约定" },
                        content = task.goal,
                        promiseKind = if (task.dueAt != null) PromiseKind.Reminder else PromiseKind.Promise,
                        createdAt = task.createdAt,
                        updatedAt = task.updatedAt,
                    ),
                )
            }
            val linked = CommitmentTaskStore.update(task.id) { existing ->
                if (existing.lexiconEntryId == null) existing.copy(lexiconEntryId = id) else existing
            }
            if (linked != null) syncTask(linked)
            return
        }
        val lexiconId = task.lexiconEntryId
        val current = LuluRepositories.lexicon.snapshot(task.characterId).firstOrNull { it.id == lexiconId }
        if (current == null) {
            if (task.status.isActive()) CommitmentTaskStore.cancel(task.id, "对应约定记录已删除")
            return
        }
        if (current.section != LexiconSection.Promise) return
        // A reschedule changes the authoritative task goal/time; retaining the
        // original lexicon heading made "20分钟后" look absent after an update.
        val base = task.goal.trim().ifBlank { current.content.substringBefore(MARKER).trim() }
        val title = base.take(24).ifBlank { "新的约定" }
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
            if (task.lastActionResult.isNotBlank()) append('\n').append("最近结果：${task.readableLastResult()}")
        }
        val status = when {
            task.status.isActive() -> LexiconStatus.Active
            task.status == CommitmentTaskStatus.Completed -> LexiconStatus.Resolved
            else -> LexiconStatus.Archived
        }
        if (current.content == progress && current.status == status && current.title == title) return
        LuluRepositories.lexicon.save(current.copy(title = title, content = progress, status = status, updatedAt = task.updatedAt))
    }

    private fun reconcileDeletedLexiconEntries() {
        CommitmentTaskStore.snapshot().filter { it.status.isActive() && it.lexiconEntryId != null }.forEach { task ->
            val exists = LuluRepositories.lexicon.snapshot(task.characterId).any { it.id == task.lexiconEntryId }
            if (!exists) CommitmentTaskStore.cancel(task.id, "对应约定记录已删除")
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
