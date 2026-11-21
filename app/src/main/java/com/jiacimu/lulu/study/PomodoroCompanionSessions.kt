package com.jiacimu.lulu.study

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioTrack
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.util.UUID
import kotlin.math.ceil
import kotlin.random.Random

enum class PomodoroTimerMode { Countdown, CountUp }
enum class PomodoroSkin { Light, Dark }

/** Audible boundaries of a micro-rest; never a per-second ticker. */
enum class MicroRestCue { Start, End }

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
    // Session-local random 3–5 minute pauses; never creates role messages.
    val microRestEnabled: Boolean = false,
    val microRestNextAtEpochMillis: Long = 0L,
    val microRestEndsAtEpochMillis: Long = 0L,
    // Positive = time until next reminder; negative = paused remainder of a 10s rest.
    val microRestPausedMillis: Long = 0L,
    val microRestRemainingSeconds: Int = 0,
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

    /** User-controlled, off by default. Does not restart or extend the main timer. */
    fun updateMicroRestEnabled(enabled: Boolean, nowMillis: Long = System.currentTimeMillis()) {
        update { current ->
            if (current.microRestEnabled == enabled) current else {
                val activeCountdown = current.timerMode == PomodoroTimerMode.Countdown &&
                    current.activeSessionId.isNotBlank() && !current.completionHandled
                val wait = if (enabled && activeCountdown) randomMicroRestDelayMillis() else 0L
                current.copy(
                    microRestEnabled = enabled,
                    microRestNextAtEpochMillis = if (enabled && activeCountdown && current.countdownRunning) nowMillis + wait else 0L,
                    microRestEndsAtEpochMillis = 0L,
                    microRestPausedMillis = if (activeCountdown && !current.countdownRunning) wait else 0L,
                    microRestRemainingSeconds = 0,
                )
            }
        }
    }
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
                microRestNextAtEpochMillis = if (mode == PomodoroTimerMode.Countdown && it.microRestEnabled)
                    startedAtEpochMillis + randomMicroRestDelayMillis() else 0L,
                microRestEndsAtEpochMillis = 0L,
                microRestPausedMillis = 0L,
                microRestRemainingSeconds = 0,
            )
        }
    }

    fun markCompletionHandled() = update {
        it.copy(completionHandled = true, countUpRunning = false, countUpAnchorEpochMillis = 0L,
            countdownRunning = false, countdownEndAtEpochMillis = 0L,
            microRestNextAtEpochMillis = 0L, microRestEndsAtEpochMillis = 0L,
            microRestPausedMillis = 0L, microRestRemainingSeconds = 0)
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

    /**
     * One cue when the rest starts and a separate cue exactly 10 seconds later.
     * A stale end/start after process suspension is discarded rather than played late.
     * Never changes the main focus duration.
     */
    fun syncMicroRestClockEvent(nowMillis: Long = System.currentTimeMillis()): MicroRestCue? {
        val current = mutable.value
        if (!current.microRestEnabled || current.timerMode != PomodoroTimerMode.Countdown ||
            current.activeSessionId.isBlank() || current.completionHandled ||
            !current.countdownRunning) return null
        if (current.microRestEndsAtEpochMillis > 0L) {
            val seconds = ((current.microRestEndsAtEpochMillis - nowMillis).coerceAtLeast(0L) + 999L)
                .div(1000L).toInt()
            if (seconds <= 0) {
                update {
                    it.copy(microRestEndsAtEpochMillis = 0L, microRestRemainingSeconds = 0,
                        microRestNextAtEpochMillis = nowMillis + randomMicroRestDelayMillis())
                }
                return if (nowMillis - current.microRestEndsAtEpochMillis <= 2_000L)
                    MicroRestCue.End else null
            }
            if (seconds != current.microRestRemainingSeconds) {
                mutable.value = current.copy(microRestRemainingSeconds = seconds)
            }
            return null
        }
        val nextAt = current.microRestNextAtEpochMillis
        if (nextAt <= 0L || nowMillis - nextAt > 20_000L ||
            current.countdownEndAtEpochMillis - nowMillis <= 10_000L) {
            // Never ring after a long period away or in the last 10s of a session.
            if (current.countdownEndAtEpochMillis - nowMillis > 10_000L) {
                update { it.copy(microRestNextAtEpochMillis = nowMillis + randomMicroRestDelayMillis()) }
            }
            return null
        }
        if (nowMillis < nextAt) return null
        update {
            it.copy(microRestNextAtEpochMillis = 0L,
                microRestEndsAtEpochMillis = nowMillis + MICRO_REST_DURATION_MS,
                microRestRemainingSeconds = 10)
        }
        return MicroRestCue.Start
    }

    /** Compatibility for older callers/tests that only need to know about rest start. */
    fun syncMicroRestClock(nowMillis: Long = System.currentTimeMillis()): Boolean =
        syncMicroRestClockEvent(nowMillis) == MicroRestCue.Start

    fun toggleCountdown(nowMillis: Long = System.currentTimeMillis()) {
        val current = mutable.value
        if (current.timerMode != PomodoroTimerMode.Countdown ||
            current.activeSessionId.isBlank() || current.completionHandled) return
        if (current.countdownRunning) {
            val remaining = ((current.countdownEndAtEpochMillis - nowMillis).coerceAtLeast(0L) + 999L)
                .div(1000L).toInt()
            val restPauseMs = when {
                !current.microRestEnabled -> 0L
                current.microRestEndsAtEpochMillis > nowMillis ->
                    -(current.microRestEndsAtEpochMillis - nowMillis)
                current.microRestNextAtEpochMillis > nowMillis ->
                    current.microRestNextAtEpochMillis - nowMillis
                else -> randomMicroRestDelayMillis()
            }
            update { it.copy(countdownRunning = false,
                countdownRemainingSeconds = remaining, countdownEndAtEpochMillis = 0L,
                microRestNextAtEpochMillis = 0L, microRestEndsAtEpochMillis = 0L,
                microRestPausedMillis = restPauseMs, microRestRemainingSeconds = 0) }
        } else if (current.countdownRemainingSeconds > 0) {
            val pauseMs = current.microRestPausedMillis
            update { it.copy(countdownRunning = true,
                countdownEndAtEpochMillis = nowMillis + it.countdownRemainingSeconds * 1000L,
                microRestEndsAtEpochMillis = if (it.microRestEnabled && pauseMs < 0L)
                    nowMillis - pauseMs else 0L,
                microRestNextAtEpochMillis = if (it.microRestEnabled && pauseMs >= 0L)
                    nowMillis + if (pauseMs > 0L) pauseMs else randomMicroRestDelayMillis() else 0L,
                microRestPausedMillis = 0L,
                microRestRemainingSeconds = if (it.microRestEnabled && pauseMs < 0L)
                    ((-pauseMs + 999L) / 1000L).toInt() else 0) }
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
                microRestNextAtEpochMillis = 0L,
                microRestEndsAtEpochMillis = 0L,
                microRestPausedMillis = 0L,
                microRestRemainingSeconds = 0,
            )
        }
    }

    private fun randomMicroRestDelayMillis(): Long =
        Random.nextLong(MICRO_REST_MIN_DELAY_MS, MICRO_REST_MAX_DELAY_MS + 1L)

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
            .putBoolean(KEY_MICRO_REST_ENABLED, next.microRestEnabled)
            .putLong(KEY_MICRO_REST_NEXT, next.microRestNextAtEpochMillis)
            .putLong(KEY_MICRO_REST_ENDS, next.microRestEndsAtEpochMillis)
            .putLong(KEY_MICRO_REST_PAUSED, next.microRestPausedMillis)
            .putInt(KEY_MICRO_REST_REMAINING, next.microRestRemainingSeconds)
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
        microRestEnabled = prefs.getBoolean(KEY_MICRO_REST_ENABLED, false),
        microRestNextAtEpochMillis = prefs.getLong(KEY_MICRO_REST_NEXT, 0L).coerceAtLeast(0L),
        microRestEndsAtEpochMillis = prefs.getLong(KEY_MICRO_REST_ENDS, 0L).coerceAtLeast(0L),
        microRestPausedMillis = prefs.getLong(KEY_MICRO_REST_PAUSED, 0L)
            .coerceIn(-MICRO_REST_DURATION_MS, MICRO_REST_MAX_DELAY_MS),
        microRestRemainingSeconds = prefs.getInt(KEY_MICRO_REST_REMAINING, 0).coerceIn(0, 10),
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
        private const val MICRO_REST_DURATION_MS = 10_000L
        private const val MICRO_REST_MIN_DELAY_MS = 180_000L
        private const val MICRO_REST_MAX_DELAY_MS = 300_000L
        private const val KEY_MICRO_REST_ENABLED = "micro_rest_enabled"
        private const val KEY_MICRO_REST_NEXT = "micro_rest_next"
        private const val KEY_MICRO_REST_ENDS = "micro_rest_ends"
        private const val KEY_MICRO_REST_PAUSED = "micro_rest_paused"
        private const val KEY_MICRO_REST_REMAINING = "micro_rest_remaining"

        fun create(context: Context): PomodoroCompanionStore = PomodoroCompanionStore(context.applicationContext)
    }
}

/**
 * One audible two-note cue at the start of a 10-second micro-rest.
 * Use the device's MEDIA volume, not the often-silenced notification channel.
 * Keep ToneGenerator alive until the asynchronous tone has finished.
 * No alarm-volume override, paid model call, or repeated alerts.
 */
internal object PomodoroMicroRestChime {
    private const val SAMPLE_RATE = 44_100
    private const val TOTAL_MS = 720L

    /**
     * Generate an actual PCM chime for the media output. Unlike notification tones
     * it is not suppressed just because the notification channel is silenced.
     * Return false on muted media volume or an AudioTrack failure; do not claim it rang.
     */
    suspend fun play(context: Context, cue: MicroRestCue): Boolean {
        val audio = context.getSystemService(Context.AUDIO_SERVICE) as? AudioManager
            ?: return false
        if (audio.getStreamVolume(AudioManager.STREAM_MUSIC) <= 0) return false
        val pcm = samples(cue)
        val track = runCatching {
            AudioTrack.Builder()
                .setAudioAttributes(
                    AudioAttributes.Builder()
                        .setUsage(AudioAttributes.USAGE_MEDIA)
                        .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                        .build()
                )
                .setAudioFormat(
                    AudioFormat.Builder()
                        .setSampleRate(SAMPLE_RATE)
                        .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                        .setChannelMask(AudioFormat.CHANNEL_OUT_MONO)
                        .build()
                )
                .setTransferMode(AudioTrack.MODE_STATIC)
                .setBufferSizeInBytes(pcm.size * 2)
                .build()
        }.getOrNull() ?: return false

        return try {
            if (track.state != AudioTrack.STATE_INITIALIZED) false
            else {
                val written = track.write(pcm, 0, pcm.size, AudioTrack.WRITE_BLOCKING)
                if (written != pcm.size) false
                else {
                    track.play()
                    delay(TOTAL_MS + 120L)
                    true
                }
            }
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            false
        } finally {
            runCatching { track.stop() }
            runCatching { track.release() }
        }
    }

    private fun samples(cue: MicroRestCue): ShortArray {
        val notes = if (cue == MicroRestCue.Start) doubleArrayOf(880.0, 1174.66)
            else doubleArrayOf(1174.66, 880.0)
        val total = (SAMPLE_RATE * TOTAL_MS / 1000L).toInt()
        return ShortArray(total) { index ->
            val millis = index * 1000.0 / SAMPLE_RATE
            val noteIndex = when {
                millis < 240.0 -> 0
                millis >= 335.0 && millis < 585.0 -> 1
                else -> -1
            }
            if (noteIndex < 0) 0
            else {
                val elapsed = if (noteIndex == 0) millis else millis - 335.0
                val duration = if (noteIndex == 0) 240.0 else 250.0
                val envelope = minOf(1.0, elapsed / 12.0, (duration - elapsed) / 25.0)
                    .coerceAtLeast(0.0)
                val sample = kotlin.math.sin(
                    2.0 * Math.PI * notes[noteIndex] * (index.toDouble() / SAMPLE_RATE)
                )
                (sample * envelope * Short.MAX_VALUE * 0.65).toInt().toShort()
            }
        }
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
    fun syncMicroRestClock(): Boolean = store.syncMicroRestClock()
    fun syncMicroRestClockEvent(): MicroRestCue? = store.syncMicroRestClockEvent()
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
