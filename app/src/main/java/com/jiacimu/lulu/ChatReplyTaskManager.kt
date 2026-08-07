package com.jiacimu.lulu

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.time.Instant

/**
 * Owns in-flight chat reply work outside any Compose screen lifecycle.
 *
 * Once a private/group reply has started, navigating away from the chat screen does not cancel the
 * request or its paced bubble delivery. Returning to the conversation simply observes the same
 * task state again. The task still belongs to the app process: an explicit Stop cancels it, and an
 * OS process kill naturally ends in-memory work.
 */
object ChatReplyTaskManager {
    data class TaskState(
        val running: Boolean = false,
        val typingCharacterId: String? = null,
        val lastError: String? = null,
        val startedAt: Instant? = null,
    )

    class TaskContext internal constructor(private val conversationId: String) {
        fun setTypingCharacter(characterId: String?) {
            ChatReplyTaskManager.updateState(conversationId) { current ->
                current.copy(typingCharacterId = characterId)
            }
        }

        fun reportError(message: String) {
            val clean = message.trim().ifBlank { "回复失败" }
            ChatReplyTaskManager.updateState(conversationId) { current -> current.copy(lastError = clean) }
        }
    }

    private val appScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val lock = Any()
    private val jobs = mutableMapOf<String, Job>()
    private val mutableStates = MutableStateFlow<Map<String, TaskState>>(emptyMap())

    val states: StateFlow<Map<String, TaskState>> = mutableStates.asStateFlow()

    fun state(conversationId: String): TaskState = mutableStates.value[conversationId] ?: TaskState()

    fun launch(
        conversationId: String,
        block: suspend TaskContext.() -> Unit,
    ): Boolean {
        val cleanId = conversationId.trim()
        if (cleanId.isBlank()) return false
        synchronized(lock) {
            if (jobs[cleanId]?.isActive == true) return false
            updateState(cleanId) {
                TaskState(
                    running = true,
                    typingCharacterId = null,
                    lastError = null,
                    startedAt = Instant.now(),
                )
            }
            val job = appScope.launch(start = CoroutineStart.LAZY) {
                try {
                    TaskContext(cleanId).block()
                } catch (cancelled: kotlinx.coroutines.CancellationException) {
                    throw cancelled
                } catch (error: Throwable) {
                    TaskContext(cleanId).reportError(error.message ?: "回复失败")
                } finally {
                    synchronized(lock) { jobs.remove(cleanId) }
                    updateState(cleanId) { current ->
                        current.copy(running = false, typingCharacterId = null)
                    }
                }
            }
            jobs[cleanId] = job
            job.start()
            return true
        }
    }

    fun stop(conversationId: String): Boolean {
        val job = synchronized(lock) { jobs.remove(conversationId) } ?: return false
        job.cancel()
        updateState(conversationId) { current ->
            current.copy(running = false, typingCharacterId = null)
        }
        return true
    }

    fun clearError(conversationId: String) {
        updateState(conversationId) { current -> current.copy(lastError = null) }
    }

    private fun updateState(conversationId: String, transform: (TaskState) -> TaskState) {
        mutableStates.update { all ->
            all + (conversationId to transform(all[conversationId] ?: TaskState()))
        }
    }
}
