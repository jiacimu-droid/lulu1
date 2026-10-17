package com.jiacimu.lulu.data

import android.app.AlarmManager
import android.content.Context
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import java.time.Instant

@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE, sdk = [28])
class BackgroundPerceptionRecoveryTest {
    private lateinit var context: Context
    private val role = "background-recovery-test"

    @Before fun before() {
        context = RuntimeEnvironment.getApplication()
        context.getSharedPreferences("lulu_autonomous_attempts_v1", Context.MODE_PRIVATE).edit().clear().commit()
        context.getSharedPreferences("lulu_proactive_runtime_v2", Context.MODE_PRIVATE).edit().clear().commit()
    }

    @After fun after() {
        @Suppress("UNCHECKED_CAST")
        (AutonomousPerceptionAttempts.javaClass.getDeclaredField("active").apply { isAccessible = true }
            .get(AutonomousPerceptionAttempts) as MutableSet<String>).clear()
    }

    @Test fun interruptedModelRestoresOnceAcrossRepeatedColdBootChecks() {
        interrupted("model")
        val queued = mutableListOf<String>()
        AutonomousPerceptionAttempts.recoverInterrupted(context) { character, _ -> queued.add(character) }
        AutonomousPerceptionAttempts.recoverInterrupted(context) { character, _ -> queued.add(character) }
        assertEquals(listOf(role), queued)
        assertFalse(attempts().contains(role))
        assertTrue(context.getSharedPreferences("lulu_proactive_runtime_v2", Context.MODE_PRIVATE)
            .getLong("background_failure_retry_at_$role", 0) > 0)
    }

    @Test fun interruptedActionIsNotReplayedAndFailedQueueRegistrationCanRecoverLater() {
        interrupted("action")
        var calls = 0
        AutonomousPerceptionAttempts.recoverInterrupted(context) { _, _ -> calls++ }
        assertEquals(0, calls)
        assertFalse(attempts().contains(role))
        interrupted("model")
        AutonomousPerceptionAttempts.recoverInterrupted(context) { _, _ -> throw IllegalStateException("queue unavailable") }
        assertTrue(attempts().contains(role))
        AutonomousPerceptionAttempts.recoverInterrupted(context) { _, _ -> calls++ }
        assertEquals(1, calls)
        assertFalse(attempts().contains(role))
    }

    @Test fun activeThinkingAndSuccessfulSilenceDoNotBecomeFailureRetries() {
        val attempt = AutonomousPerceptionAttempts.begin(context, role, "角色时间间隔", Instant.now())
        var calls = 0
        AutonomousPerceptionAttempts.recoverInterrupted(context) { _, _ -> calls++ }
        assertEquals(0, calls)
        AutonomousPerceptionAttempts.finish(context, attempt, failed = false)
        AutonomousPerceptionAttempts.recoverInterrupted(context) { _, _ -> calls++ }
        assertEquals(0, calls)
        assertFalse(attempts().contains(role))
    }

    @Test fun recoveryAttemptCannotRecursivelyScheduleItself() {
        interrupted("model", ProactivePerceptionRecoveryPolicy.RETRY_TRIGGER)
        var calls = 0
        AutonomousPerceptionAttempts.recoverInterrupted(context) { _, _ -> calls++ }
        assertEquals(0, calls)
        assertFalse(attempts().contains(role))
    }

    @Test fun nativeWakeSurvivesUiAbsenceAndRearmsAfterRegistrationWasRemoved() {
        val manager = context.getSystemService(AlarmManager::class.java)
        val shadow = shadowOf(manager)
        val due = Instant.now().plusSeconds(3_600)
        PerceptionNativeWake.schedule(context, due)
        PerceptionNativeWake.schedule(context, due)
        assertEquals(1, shadow.scheduledAlarms.size)
        assertNull(manager.nextAlarmClock) // Ordinary perception never impersonates a wake-up alarm clock.
        manager.cancel(shadow.scheduledAlarms.single().operation)
        assertTrue(shadow.scheduledAlarms.isEmpty())
        PerceptionNativeWake.invalidate(context)
        PerceptionNativeWake.schedule(context, due)
        assertEquals(1, shadow.scheduledAlarms.size)
        PerceptionNativeWake.schedule(context, null)
        assertTrue(shadow.scheduledAlarms.isEmpty())
    }

    private fun attempts() = context.getSharedPreferences("lulu_autonomous_attempts_v1", Context.MODE_PRIVATE)
    private fun interrupted(stage: String, trigger: String = "角色时间间隔") {
        attempts().edit().putString(role, JSONObject().put("id", "interrupted")
            .put("owner", "previous-process").put("characterId", role)
            .put("stage", stage).put("trigger", trigger).put("startedAt", System.currentTimeMillis()).toString()).commit()
    }
}
