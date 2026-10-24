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

    @Test fun distantOrEarlierPersonalAppointmentsCannotPostponePassiveThinking() {
        // Initialize the real stores for the integrated scheduler regression test.
        com.jiacimu.lulu.LuluRepositories.initialize(context)
        SharedExperienceTimeline.initialize(context)
        MigratedDomainStores.initialize(context)
        CharacterIdentityStore.initialize(context)
        DigitalLifeProfileStore.initialize(context)
        CompanionOnlineStore.initialize(context)
        val role = MigratedDomainStores.characters.create("自主日程间隔测试").characterId
        MigratedDomainStores.chat.ensureConversation(role, "自主日程间隔测试")
        CompanionOnlineStore.resetCharacter(role)
        ProactivePerceptionPolicyStore.update(role) {
            it.copy(enabled = true, adaptiveFrequency = false,
                quietHoursEnabled = false, intervalValue = 2,
                intervalUnit = PerceptionIntervalUnit.Hours)
        }

        val now = Instant.now()
        val ordinary = requireNotNull(ProactivePerceptionRuntime.wakePlanFor(context, role, now))
        assertTrue("Two-hour fallback should not start eleven hours later",
            ordinary.dueAt.isBefore(now.plusSeconds(3 * 3600L)))

        // Plans retain their OWN durable wake workers; neither a far appointment
        // nor an earlier one is allowed to replace the passive perception clock.
        val late = RoleDayScheduleStore.arrange(context, role,
            "晚上和朋友聊天", "想在今晚和朋友聊聊近况",
            now.plusSeconds(11 * 3600L), now = now)
        val early = RoleDayScheduleStore.arrange(context, role,
            "下午读一会书", "想看看新书里讲了什么",
            now.plusSeconds(3600L), now = now)
        try {
            assertEquals(early.dueAt, RoleDayScheduleStore.nextPlannedWakeAt(context, role, now))
            val afterScheduling = requireNotNull(
                ProactivePerceptionRuntime.wakePlanFor(context, role, now))
            assertEquals("Creating personal activities must not postpone ordinary thinking",
                ordinary.dueAt, afterScheduling.dueAt)
            assertEquals(ordinary.policySignature, afterScheduling.policySignature)
            assertTrue(RoleDayScheduleStore.plans(context, role).any { it.id == late.id })
            assertTrue(RoleDayScheduleStore.plans(context, role).any { it.id == early.id })
        } finally {
            RoleDayScheduleStore.remove(context, role, early.id)
            RoleDayScheduleStore.remove(context, role, late.id)
        }
    }

    @Test fun onlineThinkingCadenceIsIndependentOfBackgroundTimingAndNeverFastByAccident() {
        val defaultPolicy = ProactivePerceptionPolicy()
        assertEquals(120L, defaultPolicy.baseIntervalMinutes)
        assertEquals(300, defaultPolicy.onlinePulseSeconds)
        assertEquals(300, defaultPolicy.copy(onlinePulseSeconds = 300).normalized().onlinePulseSeconds)
        assertEquals(150, defaultPolicy.copy(onlinePulseSeconds = 1).normalized().onlinePulseSeconds)
        assertEquals(300, defaultPolicy.copy(onlinePulseSeconds = 3_600).normalized().onlinePulseSeconds)
        assertEquals(120L, defaultPolicy.copy(onlinePulseSeconds = 300).baseIntervalMinutes)
    }
}
