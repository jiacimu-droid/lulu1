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
        // Robolectric does not launch AndroidX Startup in this test manifest.
        if (runCatching { androidx.work.WorkManager.getInstance(context) }.isFailure) {
            androidx.work.WorkManager.initialize(context,
                androidx.work.Configuration.Builder().build())
        }
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

    @Test fun onlyRealSelfChosenNearTermPlansReplacePassiveFallback() {
        com.jiacimu.lulu.LuluRepositories.initialize(context)
        SharedExperienceTimeline.initialize(context)
        MigratedDomainStores.initialize(context)
        CharacterIdentityStore.initialize(context)
        DigitalLifeProfileStore.initialize(context)
        CompanionOnlineStore.initialize(context)
        // Robolectric does not launch AndroidX Startup in this test manifest.
        if (runCatching { androidx.work.WorkManager.getInstance(context) }.isFailure) {
            androidx.work.WorkManager.initialize(context,
                androidx.work.Configuration.Builder().build())
        }
        val role = MigratedDomainStores.characters.create("独立生活来源回归测试").characterId
        MigratedDomainStores.chat.ensureConversation(role, "独立生活来源回归测试")
        CompanionOnlineStore.resetCharacter(role)
        ProactivePerceptionPolicyStore.update(role) {
            it.copy(enabled = true, adaptiveFrequency = false,
                quietHoursEnabled = false, intervalValue = 2,
                intervalUnit = PerceptionIntervalUnit.Hours)
        }
        val now = Instant.now()
        val ordinary = requireNotNull(ProactivePerceptionRuntime.wakePlanFor(context, role, now))
        val ownerRequest = RoleDayScheduleStore.arrange(context, role,
            "主人交代的事项", "按照主人此前的吩咐按时提醒她",
            now.plusSeconds(11 * 3600L), now = now)
        val selfActivity = RoleDayScheduleStore.arrange(context, role,
            "自己继续看书", "我还对这本书后面的故事很感兴趣",
            now.plusSeconds(2 * 3600L + 30 * 60L), now = now,
            activityAction = "play_solo_game",
            activityArgs = org.json.JSONObject().put("gameId", "memory_match"),
            selfInitiated = true)
        try {
            val restored = RoleDayScheduleStore.plans(context, role)
            assertFalse(restored.first { it.id == ownerRequest.id }.selfInitiated)
            assertTrue(restored.first { it.id == selfActivity.id }.selfInitiated)
            assertEquals(selfActivity.dueAt,
                RoleDayScheduleStore.nextSelfDirectedWakeAt(context, role, now))
            val ownWake = requireNotNull(ProactivePerceptionRuntime.wakePlanFor(context, role, now))
            assertEquals(selfActivity.dueAt, ownWake.dueAt)
            assertTrue(ownWake.policySignature.contains(":self-directed="))
            assertTrue(RoleDayScheduleStore.cancel(context, role, selfActivity.id))
            assertNull(RoleDayScheduleStore.nextSelfDirectedWakeAt(context, role, now))
            assertEquals("A user-only task cannot suppress character's independent fallback",
                ordinary.dueAt,
                requireNotNull(ProactivePerceptionRuntime.wakePlanFor(context, role, now)).dueAt)
        } finally {
            RoleDayScheduleStore.remove(context, role, ownerRequest.id)
            RoleDayScheduleStore.remove(context, role, selfActivity.id)
        }
    }

    @Test fun oneDistantHobbyDoesNotCoverAnElevenHourGap() {
        val ordinary = anchor.plusSeconds(2 * 3600)
        val soon = anchor.plusSeconds(3 * 3600)
        val distant = anchor.plusSeconds(11 * 3600)
        assertEquals(soon, ProactivePerceptionRuntime.chooseAutonomousWakeTime(
            ordinary, soon, 120))
        assertEquals(ordinary, ProactivePerceptionRuntime.chooseAutonomousWakeTime(
            ordinary, distant, 120))
        assertEquals(ordinary, ProactivePerceptionRuntime.chooseAutonomousWakeTime(
            ordinary, null, 120))
        assertEquals(anchor.plusSeconds(3600),
            ProactivePerceptionRuntime.chooseAutonomousWakeTime(
                ordinary, anchor.plusSeconds(3600), 120))
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
