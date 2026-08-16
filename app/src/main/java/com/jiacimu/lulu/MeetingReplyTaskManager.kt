package com.jiacimu.lulu

import com.jiacimu.lulu.data.MeetingExperienceStore
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.time.Instant

/** Keeps meeting generation alive when its Compose page is no longer on screen. */
object MeetingReplyTaskManager {
    data class TaskState(
        val running: Boolean = false,
        val exchangeId: String? = null,
        val startedAt: Instant? = null,
        val lastError: String? = null,
    )

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val lock = Any()
    private val jobs = mutableMapOf<String, Job>()
    private val mutableStates = MutableStateFlow<Map<String, TaskState>>(emptyMap())
    val states: StateFlow<Map<String, TaskState>> = mutableStates.asStateFlow()

    fun state(sessionId: String): TaskState = mutableStates.value[sessionId] ?: TaskState()

    fun launch(
        sessionId: String,
        exchangeId: String,
        block: suspend () -> Unit,
    ): Boolean {
        if (sessionId.isBlank() || exchangeId.isBlank()) return false
        synchronized(lock) {
            if (jobs[sessionId]?.isActive == true) return false
            MeetingExperienceStore.markRunning(exchangeId)
            update(sessionId) {
                TaskState(running = true, exchangeId = exchangeId, startedAt = Instant.now())
            }
            val job = scope.launch(start = CoroutineStart.LAZY) {
                try {
                    block()
                } catch (cancelled: kotlinx.coroutines.CancellationException) {
                    throw cancelled
                } catch (error: Throwable) {
                    val message = error.message ?: "见面回复失败"
                    MeetingExperienceStore.failExchange(exchangeId, message)
                    update(sessionId) { it.copy(lastError = message) }
                } finally {
                    val completedJob = currentCoroutineContext()[Job]
                    val stillCurrent = synchronized(lock) {
                        if (jobs[sessionId] === completedJob) {
                            jobs.remove(sessionId)
                            true
                        } else false
                    }
                    if (stillCurrent) update(sessionId) { it.copy(running = false) }
                }
            }
            jobs[sessionId] = job
            job.start()
            return true
        }
    }

    fun clearError(sessionId: String) {
        update(sessionId) { it.copy(lastError = null) }
    }

    private fun update(sessionId: String, transform: (TaskState) -> TaskState) {
        mutableStates.update { all -> all + (sessionId to transform(all[sessionId] ?: TaskState())) }
    }
}
