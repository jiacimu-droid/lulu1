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
        // Old keys may remain for backward compatibility but must not be
        // consulted or refreshed by this timer-only store.
        assertEquals("lulu", prefs.getString("active_character_id", ""))
        assertFalse(prefs.getBoolean("opening_handled", true))
    }
}
