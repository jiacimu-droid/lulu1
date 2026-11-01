package com.jiacimu.lulu.study

import android.content.Context
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.util.UUID
import kotlin.math.ceil

enum class PomodoroTimerMode { Countdown, CountUp }
enum class PomodoroSkin { Light, Dark }

data class PomodoroCompanionPreferences(
    val task: String = "完成当前最重要的一项学习任务",
    val activeSessionId: String = "",
    val activeTask: String = "",
    val completionHandled: Boolean = false,
    val timerMode: PomodoroTimerMode = PomodoroTimerMode.Countdown,
    val skin: PomodoroSkin = PomodoroSkin.Light,
    val countUpRunning: Boolean = false,
    val countUpElapsedSeconds: Int = 0,
    val countUpAnchorEpochMillis: Long = 0L,
)

class PomodoroCompanionStore private constructor(context: Context) {
    private val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
    private val mutable = MutableStateFlow(load())
    val state: StateFlow<PomodoroCompanionPreferences> = mutable.asStateFlow()

    fun updateTask(task: String) = update { it.copy(task = task.trim().take(200)) }
    fun updateTimerMode(mode: PomodoroTimerMode) = update { current ->
        if (current.activeSessionId.isNotBlank() && !current.completionHandled) current else current.copy(timerMode = mode)
    }
    fun updateSkin(skin: PomodoroSkin) = update { it.copy(skin = skin) }

    fun beginSession(
        task: String,
        mode: PomodoroTimerMode,
        startedAtEpochMillis: Long = System.currentTimeMillis(),
    ) {
        val cleanTask = task.trim().take(200)
        update {
            it.copy(
                task = cleanTask,
                activeSessionId = UUID.randomUUID().toString(),
                activeTask = cleanTask,
                completionHandled = false,
                timerMode = mode,
                countUpRunning = mode == PomodoroTimerMode.CountUp,
                countUpElapsedSeconds = 0,
                countUpAnchorEpochMillis = if (mode == PomodoroTimerMode.CountUp) startedAtEpochMillis else 0L,
            )
        }
    }

    fun markCompletionHandled() = update {
        it.copy(completionHandled = true, countUpRunning = false, countUpAnchorEpochMillis = 0L)
    }

    fun currentCountUpSeconds(nowMillis: Long = System.currentTimeMillis()): Int {
        val current = mutable.value
        if (!current.countUpRunning || current.countUpAnchorEpochMillis <= 0L) return current.countUpElapsedSeconds.coerceAtLeast(0)
        val delta = ((nowMillis - current.countUpAnchorEpochMillis).coerceAtLeast(0L) / 1000L).toInt()
        return (current.countUpElapsedSeconds + delta).coerceAtLeast(0)
    }

    /** Refreshes the visible counter in memory; the persisted anchor remains enough to recover after process death. */
    fun syncCountUpClock(nowMillis: Long = System.currentTimeMillis()): Int {
        val elapsed = currentCountUpSeconds(nowMillis)
        val current = mutable.value
        if (current.countUpRunning) {
            mutable.value = current.copy(
                countUpElapsedSeconds = elapsed,
                countUpAnchorEpochMillis = nowMillis,
            )
        }
        return elapsed
    }

    fun toggleCountUp(nowMillis: Long = System.currentTimeMillis()) {
        val current = mutable.value
        if (current.timerMode != PomodoroTimerMode.CountUp || current.activeSessionId.isBlank() || current.completionHandled) return
        if (current.countUpRunning) {
            val elapsed = currentCountUpSeconds(nowMillis)
            update { it.copy(countUpRunning = false, countUpElapsedSeconds = elapsed, countUpAnchorEpochMillis = 0L) }
        } else {
            update { it.copy(countUpRunning = true, countUpAnchorEpochMillis = nowMillis) }
        }
    }

    fun stopCountUp(nowMillis: Long = System.currentTimeMillis()): Int {
        val elapsed = currentCountUpSeconds(nowMillis)
        update { it.copy(countUpRunning = false, countUpElapsedSeconds = elapsed, countUpAnchorEpochMillis = 0L) }
        return elapsed
    }

    fun clearSession() {
        update {
            it.copy(
                activeSessionId = "",
                activeTask = "",
                completionHandled = false,
                countUpRunning = false,
                countUpElapsedSeconds = 0,
                countUpAnchorEpochMillis = 0L,
            )
        }
    }

    private fun update(transform: (PomodoroCompanionPreferences) -> PomodoroCompanionPreferences) {
        val next = transform(mutable.value)
        mutable.value = next
        prefs.edit()
            .putString(KEY_TASK, next.task)
            .putString(KEY_ACTIVE_SESSION_ID, next.activeSessionId)
            .putString(KEY_ACTIVE_TASK, next.activeTask)
            .putBoolean(KEY_COMPLETION_HANDLED, next.completionHandled)
            .putString(KEY_TIMER_MODE, next.timerMode.name)
            .putString(KEY_SKIN, next.skin.name)
            .putBoolean(KEY_COUNT_UP_RUNNING, next.countUpRunning)
            .putInt(KEY_COUNT_UP_ELAPSED, next.countUpElapsedSeconds)
            .putLong(KEY_COUNT_UP_ANCHOR, next.countUpAnchorEpochMillis)
            .apply()
    }

    private fun load(): PomodoroCompanionPreferences = PomodoroCompanionPreferences(
        task = prefs.getString(KEY_TASK, null).orEmpty().ifBlank { "完成当前最重要的一项学习任务" },
        activeSessionId = prefs.getString(KEY_ACTIVE_SESSION_ID, "").orEmpty(),
        activeTask = prefs.getString(KEY_ACTIVE_TASK, "").orEmpty(),
        completionHandled = prefs.getBoolean(KEY_COMPLETION_HANDLED, false),
        timerMode = runCatching { PomodoroTimerMode.valueOf(prefs.getString(KEY_TIMER_MODE, PomodoroTimerMode.Countdown.name).orEmpty()) }
            .getOrDefault(PomodoroTimerMode.Countdown),
        skin = runCatching { PomodoroSkin.valueOf(prefs.getString(KEY_SKIN, PomodoroSkin.Light.name).orEmpty()) }
            .getOrDefault(PomodoroSkin.Light),
        countUpRunning = prefs.getBoolean(KEY_COUNT_UP_RUNNING, false),
        countUpElapsedSeconds = prefs.getInt(KEY_COUNT_UP_ELAPSED, 0).coerceAtLeast(0),
        countUpAnchorEpochMillis = prefs.getLong(KEY_COUNT_UP_ANCHOR, 0L).coerceAtLeast(0L),
    )

    companion object {
        private const val PREFS_NAME = "lulu_study_focus"
        private const val KEY_TASK = "task"
        private const val KEY_ACTIVE_SESSION_ID = "active_session_id"
        private const val KEY_ACTIVE_TASK = "active_task"
        private const val KEY_COMPLETION_HANDLED = "completion_handled"
        private const val KEY_TIMER_MODE = "timer_mode"
        private const val KEY_SKIN = "skin"
        private const val KEY_COUNT_UP_RUNNING = "count_up_running"
        private const val KEY_COUNT_UP_ELAPSED = "count_up_elapsed"
        private const val KEY_COUNT_UP_ANCHOR = "count_up_anchor"

        fun create(context: Context): PomodoroCompanionStore = PomodoroCompanionStore(context.applicationContext)
    }
}

/**
 * Study timer state only. Starting, pausing and completing a session never creates
 * character messages, requests an AI response, or writes companion memories.
 * Earlier installations' character-related preference keys are deliberately ignored.
 */
object PomodoroCompanionSessions {
    private var internal: PomodoroCompanionStore? = null

    val store: PomodoroCompanionStore
        get() = checkNotNull(internal) { "PomodoroCompanionSessions 尚未初始化" }

    fun initialize(context: Context) {
        if (internal == null) internal = PomodoroCompanionStore.create(context.applicationContext)
    }

    fun beginSession(task: String, mode: PomodoroTimerMode) {
        store.beginSession(task, mode)
    }

    fun clearSession() = store.clearSession()
    fun toggleCountUp() = store.toggleCountUp()
    fun syncCountUpClock(): Int = store.syncCountUpClock()
    fun currentCountUpSeconds(): Int = store.currentCountUpSeconds()

    fun finishActiveSession(studyStore: PostgraduateExamStore): Pair<Int, String> {
        val pomodoro = store.state.value
        if (pomodoro.activeSessionId.isBlank() || pomodoro.completionHandled) return 0 to "本轮已经结束"

        val elapsedSeconds = if (pomodoro.timerMode == PomodoroTimerMode.CountUp) {
            store.stopCountUp()
        } else {
            val timer = studyStore.state.value.pomodoro
            if (timer.running) studyStore.togglePomodoro()
            val latest = studyStore.state.value.pomodoro
            (latest.selectedMinutes * 60 - latest.remainingSeconds).coerceAtLeast(0)
        }
        val actualMinutes = ceil(elapsedSeconds / 60.0).toInt()
        if (actualMinutes <= 0) {
            if (pomodoro.timerMode == PomodoroTimerMode.Countdown) studyStore.resetPomodoro()
            store.markCompletionHandled()
            return 0 to "未满1分钟，本次不记录"
        }

        val reward = studyStore.completePomodoro(actualMinutes)
        store.markCompletionHandled()
        return actualMinutes to reward
    }

    fun handleNaturalCompletion() {
        if (store.state.value.activeSessionId.isNotBlank() && !store.state.value.completionHandled) {
            store.markCompletionHandled()
        }
    }
}
