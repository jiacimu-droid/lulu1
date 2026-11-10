package com.jiacimu.lulu.data

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import java.time.Instant

@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE, sdk = [28])
class ResidentDailyPolicyTest {
    private fun context(): Context =
        RuntimeEnvironment.getApplication() as Context

    @Test fun sameBudgetAppliesToEveryNativeResidentAndPersistsWithoutChangingCharacters() {
        val ctx = context()
        ctx.getSharedPreferences("lulu_resident_activity_policy_v1", Context.MODE_PRIVATE)
            .edit().clear().commit()
        assertEquals(ResidentDailyPolicy.DEFAULT_AGENDA_ITEMS,
            ResidentDailyPolicy.maxAgendaItems(ctx))
        assertTrue(ResidentDailyPolicy.setMaxAgendaItems(ctx, 2))
        assertEquals(2, ResidentDailyPolicy.maxAgendaItems(ctx.applicationContext))
        assertTrue(ResidentDailyPolicy.setMaxAgendaItems(ctx, 0))
        assertEquals(0, ResidentDailyPolicy.maxAgendaItems(ctx))
        assertFalse(ResidentDailyPolicy.setMaxAgendaItems(ctx, -1))
        assertFalse(ResidentDailyPolicy.setMaxAgendaItems(ctx, 9))
        assertEquals(0, ResidentDailyPolicy.maxAgendaItems(ctx))
        assertTrue(ResidentDailyPolicy.setMaxAgendaItems(ctx,
            ResidentDailyPolicy.MAX_AGENDA_ITEMS))
    }


    @Test fun ownerPopulationCapAndSpawnSwitchUseOnePersistentAuthority() {
        val ctx = context()
        val pref = ctx.getSharedPreferences("lulu_resident_activity_policy_v1", Context.MODE_PRIVATE)
        pref.edit().clear().commit()
        try {
            assertEquals(ResidentDailyPolicy.MAX_RESIDENTS, ResidentDailyPolicy.maxResidents(ctx))
            // Real installations default to no new residents until the owner opts in.
            assertFalse(ResidentDailyPolicy.allowNewResidents(ctx))
            assertFalse(ResidentDailyPolicy.mayAddResident(ctx, 0))
            assertTrue(ResidentDailyPolicy.setAllowNewResidents(ctx, true))
            assertTrue(ResidentDailyPolicy.mayAddResident(ctx, 0))
            assertTrue(ResidentDailyPolicy.setMaxResidents(ctx, 2))
            assertEquals(2, ResidentDailyPolicy.maxResidents(ctx.applicationContext))
            assertTrue(ResidentDailyPolicy.mayAddResident(ctx, 1))
            assertFalse(ResidentDailyPolicy.mayAddResident(ctx, 2))
            assertTrue(ResidentDailyPolicy.setAllowNewResidents(ctx, false))
            assertFalse(ResidentDailyPolicy.autoGenerationEnabled(ctx))
            assertFalse(ResidentDailyPolicy.mayAddResident(ctx, 0))
            assertTrue(ResidentDailyPolicy.setAutoGenerationEnabled(ctx, true))
            assertTrue(ResidentDailyPolicy.allowNewResidents(ctx))
            assertFalse(ResidentDailyPolicy.setMaxResidents(ctx, -1))
            assertFalse(ResidentDailyPolicy.setMaxResidents(ctx, 13))
            assertEquals(2, ResidentDailyPolicy.maxResidents(ctx))
            assertTrue(ResidentDailyPolicy.setMaxResidents(ctx, 0))
            assertFalse(ResidentDailyPolicy.mayAddResident(ctx, 0))
        } finally {
            pref.edit().clear().commit()
        }
    }

    @Test fun dailyDecisionRequestsCorrectionRatherThanSilentlyDroppingOverBudgetPlan() {
        val at = java.time.LocalDate.of(2026, 10, 28)
            .atStartOfDay(ZoneId.systemDefault()).toInstant()
        val agenda = JSONArray().put(JSONObject()
            .put("at", "14:00")
            .put("title", "给好友留言")
            .put("intent", "想聊聊昨天没说完的话")
            .put("activityAction", "publish_moment")
            .put("activityArgs", JSONObject().put("text", "今天想分享一件有意思的小事")))
        val proposal = JSONObject().put("lunch", "home").put("evening", "courtyard")
            .put("agenda", agenda)
        assertNull(ResidentDailyDecision.dayContractError(proposal, at, maxAgendaItems = 1))
        assertTrue(ResidentDailyDecision.dayContractError(proposal, at,
            maxAgendaItems = 0).orEmpty().contains("最多包含 0 项"))
        assertNull(ResidentDailyDecision.dayContractError(
            JSONObject().put("lunch", "home").put("evening", "games")
                .put("agenda", JSONArray()), at, maxAgendaItems = 0))
    }


    @Test fun agendaLimitDoesNotCapRealTransportAttempts() {
        val ctx = context()
        val role = "resident:agenda-only"
        val at = Instant.parse("2026-11-11T10:00:00Z")
        assertTrue(ResidentDailyPolicy.setActivityEnabled(ctx, true))
        assertTrue(ResidentDailyPolicy.setMaxAgendaItems(ctx, 0))
        assertEquals(0, ResidentDailyPolicy.remainingAgendaSlots(ctx, role, at))
        repeat(10) {
            assertTrue(ResidentDailyPolicy.recordModelAttemptIfEnabled(ctx, role, at))
        }
        assertEquals(10, ResidentDailyPolicy.modelDecisionsUsed(ctx, role, at))
        assertTrue(ResidentDailyPolicy.setMaxAgendaItems(ctx, 2))
        assertEquals(2, ResidentDailyPolicy.remainingAgendaSlots(ctx, role, at))
        assertEquals(0, ResidentDailyPolicy.modelDecisionsUsed(ctx, role, at.plusSeconds(86_400)))
    }

    @Test fun oldZeroLimitRemainsPausedAndIndividualPauseLeavesOtherResidentsActive() {
        val ctx = context()
        val prefs = ctx.getSharedPreferences("lulu_resident_activity_policy_v1", Context.MODE_PRIVATE)
        assertTrue(prefs.edit().clear().putInt("daily_model_decision_limit", 0).commit())
        val paused = "resident:paused"
        val other = "resident:other"
        assertFalse(ResidentDailyPolicy.activityEnabled(ctx, paused))
        assertFalse(ResidentDailyPolicy.recordModelAttemptIfEnabled(ctx, paused))
        assertTrue(ResidentDailyPolicy.setActivityEnabled(ctx, true))
        assertTrue(ResidentDailyPolicy.setActivityEnabled(ctx, false, paused))
        assertFalse(ResidentDailyPolicy.recordModelAttemptIfEnabled(ctx, paused))
        assertTrue(ResidentDailyPolicy.recordModelAttemptIfEnabled(ctx, other))
        assertTrue(ResidentDailyPolicy.activityEnabled(ctx, "main-character"))
        assertTrue(ResidentDailyPolicy.setActivityEnabled(ctx, true, paused))
        assertTrue(ResidentDailyPolicy.recordModelAttemptIfEnabled(ctx, paused))
    }

    @Test fun fiveMinuteResidentWindowExtendsOnlyOnFreshActivityAndEndsWhenPaused() {
        val ctx = context()
        val role = "resident:lease-${java.util.UUID.randomUUID()}"
        val at = Instant.now()
        assertTrue(ResidentDailyPolicy.setActivityEnabled(ctx, true))
        assertFalse(CompanionOnlineStore.renewResidentWindow(ctx, role, at))
        CompanionOnlineStore.openResidentWindow(ctx, role, at)
        assertTrue(CompanionOnlineStore.residentIsAwake(ctx, role, at.plusSeconds(299)))
        assertFalse(CompanionOnlineStore.residentIsAwake(ctx, role, at.plusSeconds(300)))
        val later = at.plusSeconds(120)
        assertTrue(CompanionOnlineStore.renewResidentWindow(ctx, role, later))
        assertFalse(CompanionOnlineStore.renewResidentWindow(ctx, role, at.plusSeconds(30)))
        assertEquals(later.plusSeconds(300), CompanionOnlineStore.states.value[role]?.onlineUntil)
        assertTrue(ResidentDailyPolicy.setActivityEnabled(ctx, false, role))
        CompanionOnlineStore.closePausedResidentWindows(ctx, later.plusSeconds(1))
        assertFalse(CompanionOnlineStore.renewResidentWindow(ctx, role, later.plusSeconds(2)))
        assertTrue(ResidentDailyPolicy.setActivityEnabled(ctx, true, role))
        assertFalse(CompanionOnlineStore.residentIsAwake(ctx, role, later.plusSeconds(2)))
    }
}
