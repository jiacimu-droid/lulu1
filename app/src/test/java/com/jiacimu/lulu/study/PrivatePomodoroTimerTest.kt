package com.jiacimu.lulu.study

import android.content.Context
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE, sdk = [28])
class PrivatePomodoroTimerTest {
    @Test fun startingFocusDoesNotCreateOrRequireAnyCharacterIdentity() {
        val context = RuntimeEnvironment.getApplication() as Context
        val prefs = context.getSharedPreferences("lulu_study_focus", Context.MODE_PRIVATE)
        prefs.edit().clear().commit()
        val store = PomodoroCompanionStore.create(context)

        store.beginSession("整理刑法笔记", PomodoroTimerMode.CountUp, startedAtEpochMillis = 1_000L)
        val started = store.state.value
        assertTrue(started.activeSessionId.isNotBlank())
        assertEquals("整理刑法笔记", started.activeTask)
        assertTrue(started.countUpRunning)
        assertFalse(started.completionHandled)

        // The timer's persisted state contains no role link, opening-message
        // request, or voice-dialogue switch.
        assertFalse(prefs.contains("active_character_id"))
        assertFalse(prefs.contains("automatic_dialogue"))
        assertFalse(prefs.contains("opening_handled"))
        assertFalse(prefs.contains("session_message_start"))

        store.toggleCountUp(nowMillis = 61_000L)
        assertFalse(store.state.value.countUpRunning)
        assertEquals(60, store.state.value.countUpElapsedSeconds)
        store.markCompletionHandled()
        assertTrue(store.state.value.completionHandled)
        store.clearSession()
        assertTrue(store.state.value.activeSessionId.isBlank())
    }

    @Test fun oldCompanionPreferencesAreIgnoredAfterTimerMigration() {
        val context = RuntimeEnvironment.getApplication() as Context
        val prefs = context.getSharedPreferences("lulu_study_focus", Context.MODE_PRIVATE)
        prefs.edit().clear()
            .putString("active_character_id", "lulu")
            .putBoolean("automatic_dialogue", true)
            .putBoolean("opening_handled", false)
            .putString("active_session_id", "old-session")
            .putBoolean("completion_handled", true)
            .commit()

        val store = PomodoroCompanionStore.create(context)
        assertEquals("old-session", store.state.value.activeSessionId)
        assertTrue(store.state.value.completionHandled)

        store.beginSession("只属于自己的计时", PomodoroTimerMode.Countdown)
        assertFalse(store.state.value.completionHandled)
        assertEquals("只属于自己的计时", store.state.value.activeTask)
        // Legacy character-related keys are purged, not carried into 自序.
        assertFalse(prefs.contains("active_character_id"))
        assertFalse(prefs.contains("automatic_dialogue"))
        assertFalse(prefs.contains("opening_handled"))
    }
    @Test fun todoCountdownShortcutKeepsTheCurrentSessionAndOnlyChangesIdleMode() {
        val context = RuntimeEnvironment.getApplication() as Context
        context.getSharedPreferences("lulu_study_focus", Context.MODE_PRIVATE)
            .edit().clear().commit()
        val timer = PomodoroCompanionStore.create(context)
        timer.updateTask("完成今天的待办")
        timer.setDuration(35)
        timer.updateTimerMode(PomodoroTimerMode.CountUp)

        // 所行 shortcut selects countdown only when the timer is idle.
        timer.updateTimerMode(PomodoroTimerMode.Countdown)
        assertEquals(PomodoroTimerMode.Countdown, timer.state.value.timerMode)
        assertEquals("完成今天的待办", timer.state.value.task)
        assertEquals(35, timer.state.value.selectedMinutes)

        // An existing session is reopened, not switched, restarted or discarded.
        timer.updateTimerMode(PomodoroTimerMode.CountUp)
        timer.beginSession("正在进行的任务", PomodoroTimerMode.CountUp, startedAtEpochMillis = 5_000L)
        val active = timer.state.value
        timer.updateTimerMode(PomodoroTimerMode.Countdown)
        assertEquals(PomodoroTimerMode.CountUp, timer.state.value.timerMode)
        assertEquals(active.activeSessionId, timer.state.value.activeSessionId)
        assertEquals(active.countUpAnchorEpochMillis, timer.state.value.countUpAnchorEpochMillis)
        assertTrue(timer.state.value.countUpRunning)
    }

    @Test fun countdownCanPauseResumeAndFinishWithoutExamStore() {
        val context = RuntimeEnvironment.getApplication() as Context
        context.getSharedPreferences("lulu_study_focus", Context.MODE_PRIVATE)
            .edit().clear().commit()
        val timer = PomodoroCompanionStore.create(context)
        timer.setDuration(3)
        timer.beginSession("独立计时", PomodoroTimerMode.Countdown, startedAtEpochMillis = 100_000L)
        assertEquals(3, timer.state.value.selectedMinutes)
        assertTrue(timer.state.value.countdownRunning)
        assertEquals(180, timer.state.value.countdownRemainingSeconds)

        assertFalse(timer.syncCountdownClock(nowMillis = 160_000L))
        assertEquals(120, timer.state.value.countdownRemainingSeconds)

        timer.toggleCountdown(nowMillis = 170_000L)
        assertFalse(timer.state.value.countdownRunning)
        assertEquals(110, timer.state.value.countdownRemainingSeconds)
        timer.toggleCountdown(nowMillis = 220_000L)
        assertTrue(timer.state.value.countdownRunning)
        assertEquals(330_000L, timer.state.value.countdownEndAtEpochMillis)

        // Persistence reconstructs the same clock from the absolute end instant.
        val restored = PomodoroCompanionStore.create(context)
        assertTrue(restored.state.value.countdownRunning)
        assertEquals(330_000L, restored.state.value.countdownEndAtEpochMillis)
        assertFalse(restored.syncCountdownClock(nowMillis = 329_000L))
        assertEquals(1, restored.state.value.countdownRemainingSeconds)
        assertTrue(restored.syncCountdownClock(nowMillis = 330_000L))
        assertTrue(restored.state.value.completionHandled)
        assertFalse(restored.state.value.countdownRunning)
    }

    @Test fun microRestIsOffByDefaultAndNeverChangesStudyClock() {
        val context = RuntimeEnvironment.getApplication() as Context
        context.getSharedPreferences("lulu_study_focus", Context.MODE_PRIVATE).edit().clear().commit()
        val timer = PomodoroCompanionStore.create(context)
        timer.setDuration(30)
        timer.beginSession("复习法理", PomodoroTimerMode.Countdown, startedAtEpochMillis = 100_000L)
        assertFalse(timer.state.value.microRestEnabled)
        assertEquals(0L, timer.state.value.microRestNextAtEpochMillis)
        assertEquals(1_900_000L, timer.state.value.countdownEndAtEpochMillis)

        timer.updateMicroRestEnabled(true, nowMillis = 100_000L)
        val next = timer.state.value.microRestNextAtEpochMillis
        assertTrue(next in 280_000L..400_000L)
        assertFalse(timer.syncMicroRestClock(nowMillis = next - 1L))
        assertTrue(timer.syncMicroRestClock(nowMillis = next))
        assertEquals(10, timer.state.value.microRestRemainingSeconds)
        assertEquals(1_900_000L, timer.state.value.countdownEndAtEpochMillis)
        assertFalse(timer.syncMicroRestClock(nowMillis = next + 4_000L))
        assertEquals(6, timer.state.value.microRestRemainingSeconds)
        assertFalse(timer.syncMicroRestClock(nowMillis = next + 10_000L))
        assertEquals(0, timer.state.value.microRestRemainingSeconds)
        assertTrue(timer.state.value.microRestNextAtEpochMillis in
            (next + 190_000L)..(next + 310_000L))
        assertEquals(1_900_000L, timer.state.value.countdownEndAtEpochMillis)
    }

    @Test fun microRestPausesAndResumesWithoutLosingTenSecondRemainder() {
        val context = RuntimeEnvironment.getApplication() as Context
        context.getSharedPreferences("lulu_study_focus", Context.MODE_PRIVATE).edit().clear().commit()
        val timer = PomodoroCompanionStore.create(context)
        timer.setDuration(25)
        timer.updateMicroRestEnabled(true, nowMillis = 100_000L)
        timer.beginSession("读民法", PomodoroTimerMode.Countdown, startedAtEpochMillis = 100_000L)
        val first = timer.state.value.microRestNextAtEpochMillis
        timer.toggleCountdown(nowMillis = 160_000L)
        assertFalse(timer.state.value.countdownRunning)
        assertEquals(0L, timer.state.value.microRestNextAtEpochMillis)
        val restored = PomodoroCompanionStore.create(context)
        restored.toggleCountdown(nowMillis = 400_000L)
        val shifted = restored.state.value.microRestNextAtEpochMillis
        assertEquals(first + 240_000L, shifted)
        assertTrue(restored.syncMicroRestClock(nowMillis = shifted))
        restored.toggleCountdown(nowMillis = shifted + 3_000L)
        assertTrue(restored.state.value.microRestPausedMillis < 0L)
        restored.toggleCountdown(nowMillis = shifted + 90_000L)
        assertEquals(7, restored.state.value.microRestRemainingSeconds)
        assertFalse(restored.syncMicroRestClock(nowMillis = shifted + 97_000L))
        assertEquals(0, restored.state.value.microRestRemainingSeconds)
        restored.updateMicroRestEnabled(false)
        assertEquals(0L, restored.state.value.microRestNextAtEpochMillis)
        assertFalse(restored.state.value.microRestEnabled)
        assertFalse(restored.syncMicroRestClock(nowMillis = shifted + 300_000L))
    }

    @Test fun microRestDoesNotCreateMissedChimeOrRepeatDuringCountUp() {
        val context = RuntimeEnvironment.getApplication() as Context
        context.getSharedPreferences("lulu_study_focus", Context.MODE_PRIVATE).edit().clear().commit()
        val timer = PomodoroCompanionStore.create(context)
        timer.setDuration(30)
        timer.updateMicroRestEnabled(true)
        timer.beginSession("写作文", PomodoroTimerMode.Countdown, startedAtEpochMillis = 10_000L)
        val first = timer.state.value.microRestNextAtEpochMillis
        // If the app's timer was suspended for too long, skip the old notice.
        assertFalse(timer.syncMicroRestClock(nowMillis = first + 30_000L))
        assertTrue(timer.state.value.microRestNextAtEpochMillis > first + 30_000L)
        timer.markCompletionHandled()
        assertEquals(0L, timer.state.value.microRestNextAtEpochMillis)
        assertTrue(timer.state.value.microRestEnabled)
        timer.clearSession()
        timer.beginSession("练习技能", PomodoroTimerMode.CountUp, startedAtEpochMillis = 20_000L)
        assertEquals(0L, timer.state.value.microRestNextAtEpochMillis)
        assertFalse(timer.syncMicroRestClock(nowMillis = 500_000L))
    }

}
