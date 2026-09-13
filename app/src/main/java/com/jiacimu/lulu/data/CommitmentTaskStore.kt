package com.jiacimu.lulu.data

import android.content.Context
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.time.Instant

object CommitmentTaskStore {
    private const val PREFS_NAME = "lulu_commitment_tasks"
    private const val KEY_TASKS = "tasks_v1"

    private var prefs: android.content.SharedPreferences? = null
    private val mutableTasks = MutableStateFlow<List<CommitmentTask>>(emptyList())
    val tasks: StateFlow<List<CommitmentTask>> = mutableTasks.asStateFlow()

    @Synchronized
    fun initialize(context: Context) {
        if (prefs != null) return
        prefs = context.applicationContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        mutableTasks.value = decodeCommitmentTasks(prefs?.getString(KEY_TASKS, null))
    }

    fun snapshot(characterId: String? = null): List<CommitmentTask> = mutableTasks.value
        .filter { characterId.isNullOrBlank() || it.characterId == characterId }
        .sortedWith(compareBy<CommitmentTask> { it.dueAt ?: Instant.MAX }.thenByDescending { it.updatedAt })

    fun active(characterId: String): List<CommitmentTask> = snapshot(characterId).filter { it.status.isActive() }

    @Synchronized
    fun save(task: CommitmentTask): CommitmentTask {
        val current = mutableTasks.value.firstOrNull { it.id == task.id }
        val normalized = task.copy(
            revision = current?.revision ?: task.revision.coerceAtLeast(1L),
            createdAt = current?.createdAt ?: task.createdAt,
            updatedAt = Instant.now(),
        )
        persist(listOf(normalized) + mutableTasks.value.filterNot { it.id == normalized.id })
        return normalized
    }

    @Synchronized
    fun update(id: String, transform: (CommitmentTask) -> CommitmentTask): CommitmentTask? {
        val current = mutableTasks.value.firstOrNull { it.id == id } ?: return null
        val next = transform(current).copy(
            id = current.id,
            characterId = current.characterId,
            createdAt = current.createdAt,
            revision = current.revision + 1L,
            updatedAt = Instant.now(),
        )
        persist(listOf(next) + mutableTasks.value.filterNot { it.id == id })
        return next
    }

    @Synchronized
    fun removeBySourceEvent(eventId: String) {
        persist(mutableTasks.value.filterNot { eventId in it.sourceEventIds })
    }

    @Synchronized
    fun clearCharacter(characterId: String) {
        persist(mutableTasks.value.filterNot { it.characterId == characterId })
    }

    private fun persist(values: List<CommitmentTask>) {
        val next = values.sortedByDescending(CommitmentTask::updatedAt)
        mutableTasks.value = next
        prefs?.edit()?.putString(KEY_TASKS, encodeCommitmentTasks(next))?.commit()
    }
}
