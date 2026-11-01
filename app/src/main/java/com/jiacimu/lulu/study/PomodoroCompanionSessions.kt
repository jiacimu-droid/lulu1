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
    val selectedMinutes: Int = 25,
    val countdownRemainingSeconds: Int = 1500,
    val countdownRunning: Boolean = false,
    val countdownEndAtEpochMillis: Long = 0L,
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

    init {
        prefs.edit()
            .remove("active_character_id")
            .remove("automatic_dialogue")
            .remove("session_message_start")
            .remove("session_started_at")
            .remove("opening_handled")
            .apply()
    }

    fun updateTask(task: String) = update { it.copy(task = task.trim().take(200)) }
    fun updateTimerMode(mode: PomodoroTimerMode) = update { current ->
        if (current.activeSessionId.isNotBlank() && !current.completionHandled) current else current.copy(timerMode = mode)
    }
    fun updateSkin(skin: PomodoroSkin) = update { it.copy(skin = skin) }
    fun setDuration(minutes: Int) {
        val safe = minutes.coerceIn(1, 180)
        update { current ->
            if (current.activeSessionId.isNotBlank() && !current.completionHandled) current
            else current.copy(selectedMinutes = safe, countdownRemainingSeconds = safe * 60)
        }
    }

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
                countdownRemainingSeconds = it.selectedMinutes * 60,
                countdownRunning = mode == PomodoroTimerMode.Countdown,
                countdownEndAtEpochMillis = if (mode == PomodoroTimerMode.Countdown)
                    startedAtEpochMillis + it.selectedMinutes * 60_000L else 0L,
                completionHandled = false,
                timerMode = mode,
                countUpRunning = mode == PomodoroTimerMode.CountUp,
                countUpElapsedSeconds = 0,
                countUpAnchorEpochMillis = if (mode == PomodoroTimerMode.CountUp) startedAtEpochMillis else 0L,
            )
        }
    }

    fun markCompletionHandled() = update {
        it.copy(completionHandled = true, countUpRunning = false, countUpAnchorEpochMillis = 0L,
            countdownRunning = false, countdownEndAtEpochMillis = 0L)
    }

    fun syncCountdownClock(nowMillis: Long = System.currentTimeMillis()): Boolean {
        val current = mutable.value
        if (!current.countdownRunning || current.activeSessionId.isBlank() || current.completionHandled)
            return false
        val seconds = ((current.countdownEndAtEpochMillis - nowMillis).coerceAtLeast(0L) + 999L)
            .div(1000L).toInt()
        if (seconds <= 0) {
            markCompletionHandled()
            return true
        }
        mutable.value = current.copy(countdownRemainingSeconds = seconds)
        return false
    }

    fun toggleCountdown(nowMillis: Long = System.currentTimeMillis()) {
        val current = mutable.value
        if (current.timerMode != PomodoroTimerMode.Countdown ||
            current.activeSessionId.isBlank() || current.completionHandled) return
        if (current.countdownRunning) {
            val remaining = ((current.countdownEndAtEpochMillis - nowMillis).coerceAtLeast(0L) + 999L)
                .div(1000L).toInt()
            update { it.copy(countdownRunning = false,
                countdownRemainingSeconds = remaining, countdownEndAtEpochMillis = 0L) }
        } else if (current.countdownRemainingSeconds > 0) {
            update { it.copy(countdownRunning = true,
                countdownEndAtEpochMillis = nowMillis + it.countdownRemainingSeconds * 1000L) }
        } else {
            markCompletionHandled()
        }
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
                countdownRemainingSeconds = it.selectedMinutes * 60,
                countdownRunning = false,
                countdownEndAtEpochMillis = 0L,
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
            .putInt(KEY_SELECTED_MINUTES, next.selectedMinutes)
            .putInt(KEY_COUNTDOWN_REMAINING, next.countdownRemainingSeconds)
            .putBoolean(KEY_COUNTDOWN_RUNNING, next.countdownRunning)
            .putLong(KEY_COUNTDOWN_END, next.countdownEndAtEpochMillis)
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
        selectedMinutes = prefs.getInt(KEY_SELECTED_MINUTES, 25).coerceIn(1, 180),
        countdownRemainingSeconds = prefs.getInt(KEY_COUNTDOWN_REMAINING,
            prefs.getInt(KEY_SELECTED_MINUTES, 25).coerceIn(1, 180) * 60).coerceAtLeast(0),
        countdownRunning = prefs.getBoolean(KEY_COUNTDOWN_RUNNING, false),
        countdownEndAtEpochMillis = prefs.getLong(KEY_COUNTDOWN_END, 0L),
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
        private const val KEY_SELECTED_MINUTES = "selected_minutes"
        private const val KEY_COUNTDOWN_REMAINING = "countdown_remaining"
        private const val KEY_COUNTDOWN_RUNNING = "countdown_running"
        private const val KEY_COUNTDOWN_END = "countdown_end"
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
    fun toggleCountdown() = store.toggleCountdown()
    fun syncCountUpClock(): Int = store.syncCountUpClock()
    fun syncCountdownClock(): Boolean = store.syncCountdownClock()
    fun currentCountUpSeconds(): Int = store.currentCountUpSeconds()

    fun finishActiveSession(): Pair<Int, String> {
        val timer = store.state.value
        if (timer.activeSessionId.isBlank() || timer.completionHandled) return 0 to "本轮已经结束"
        val elapsed = if (timer.timerMode == PomodoroTimerMode.CountUp) {
            store.stopCountUp()
        } else {
            val remain = if (timer.countdownRunning) {
                ((timer.countdownEndAtEpochMillis - System.currentTimeMillis()).coerceAtLeast(0L) + 999L)
                    .div(1000L).toInt()
            } else timer.countdownRemainingSeconds
            (timer.selectedMinutes * 60 - remain).coerceAtLeast(0)
        }
        store.markCompletionHandled()
        val minutes = ceil(elapsed / 60.0).toInt()
        return minutes to if (minutes > 0) "本轮专注 $minutes 分钟" else "本轮计时已结束"
    }

    fun handleNaturalCompletion() {
        store.syncCountdownClock()
    }
}
