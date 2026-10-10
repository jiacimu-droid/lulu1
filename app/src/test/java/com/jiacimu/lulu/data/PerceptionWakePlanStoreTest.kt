package com.jiacimu.lulu.data

import android.content.Context
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import java.time.Instant

@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE, sdk = [28])
class PerceptionWakePlanStoreTest {
    private val anchor = Instant.parse("2026-10-10T06:00:00Z")
    private val context get() = RuntimeEnvironment.getApplication() as Context
    private fun release() {
        PerceptionWakePlanStore.javaClass.getDeclaredField("prefs").apply { isAccessible = true }
            .set(PerceptionWakePlanStore, null)
    }
    @Before fun before() {
        release()
        context.getSharedPreferences("lulu_perception_wake_plans_v1", Context.MODE_PRIVATE).edit().clear().commit()
        PerceptionWakePlanStore.initialize(context)
    }
    @After fun after() { release() }
    private fun plan(at: Instant = anchor, minutes: Long = 60, signature: String = "hour") =
        PerceptionWakePlan("role", at, at.plusSeconds(minutes * 60), minutes, signature)

    @Test fun repeatedClockChecksAndRestartCannotPostponeChosenDeadline() {
        val original = PerceptionWakePlanStore.resolve("role", anchor, "hour") { plan() }
        // Even an overdue deadline stays overdue until a real new anchor or policy arrives.
        val again = PerceptionWakePlanStore.resolve("role", anchor, "hour") {
            fail("Reading a plan must not reroll adaptive timing")
            plan(minutes = 84)
        }
        assertEquals(original, again)
        release()
        PerceptionWakePlanStore.initialize(context)
        assertEquals(original, PerceptionWakePlanStore.plans.value["role"])
        assertEquals(original, PerceptionWakePlanStore.resolve("role", anchor, "hour") {
            fail("A restart must preserve the selected wake-up")
            plan()
        })
    }

    @Test fun missedWakeDeferredForQuietHoursDoesNotRevertOnNextCheckOrRestart() {
        PerceptionWakePlanStore.resolve("role", anchor, "hour") { plan() }
        val quietEnd = anchor.plusSeconds(9 * 3600)
        PerceptionWakePlanStore.deferUntil("role", quietEnd)
        assertEquals(quietEnd, PerceptionWakePlanStore.resolve("role", anchor, "hour") { plan() }.dueAt)
        release()
        PerceptionWakePlanStore.initialize(context)
        assertEquals(quietEnd, PerceptionWakePlanStore.plans.value["role"]?.dueAt)
        assertEquals(60L, PerceptionWakePlanStore.plans.value["role"]?.intervalMinutes)
    }

    @Test fun realActivityPolicyChangeAndPendingConcernCanChooseANewDeadline() {
        PerceptionWakePlanStore.resolve("role", anchor, "hour") { plan() }
        val newAnchor = anchor.plusSeconds(600)
        assertEquals(newAnchor.plusSeconds(3600),
            PerceptionWakePlanStore.resolve("role", newAnchor, "hour") { plan(newAnchor) }.dueAt)
        assertEquals(30L, PerceptionWakePlanStore.resolve("role", newAnchor, "half-hour") {
            plan(newAnchor, 30, "half-hour")
        }.intervalMinutes)
        PerceptionWakePlanStore.invalidate("role")
        assertEquals(20L, PerceptionWakePlanStore.resolve("role", newAnchor, "half-hour") {
            plan(newAnchor, 20, "half-hour")
        }.intervalMinutes)
    }
}
