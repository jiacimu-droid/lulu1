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
import java.time.ZoneId

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


    @Test fun optionalResidentMaintenanceWaitsForOwnDailyPlanningWithoutSpendingQuota() {
        val ctx = context()
        val policy = ctx.getSharedPreferences("lulu_resident_activity_policy_v1", Context.MODE_PRIVATE)
        val daily = ctx.getSharedPreferences("lulu_resident_daily_decisions_v1", Context.MODE_PRIVATE)
        val role = "resident:maintenance-reservation"
        val nativeId = "maintenance-reservation"
        val at = Instant.parse("2026-11-08T11:00:00Z")
        policy.edit().clear().commit()
        daily.edit().remove("attempt:$nativeId").commit()
        try {
            assertTrue(ResidentDailyPolicy.setMaxModelDecisions(ctx, 2))
            assertEquals(2, ResidentDailyPolicy.remainingModelRequests(ctx, role, at))
            assertTrue(ResidentDailyPolicy.hasModelRequestAllowance(ctx, role, at))
            // Optional review cannot starve the ordinary daily planning call.
            assertFalse(ResidentDailyPolicy.hasBackgroundMaintenanceAllowance(ctx, role, at))
            assertEquals(0, ResidentDailyPolicy.modelDecisionsUsed(ctx, role, at))
            daily.edit().putString("attempt:$nativeId", ResidentDailyDecision.date(at)).commit()
            // An attempt without a saved plan is not a completed daily choice.
            assertFalse(ResidentDailyPolicy.hasBackgroundMaintenanceAllowance(ctx, role, at))
            daily.edit().putString("plan:$nativeId",
                JSONObject().put("date", ResidentDailyDecision.date(at)).toString()).commit()
            assertTrue(ResidentDailyPolicy.hasBackgroundMaintenanceAllowance(ctx, role, at))
            assertTrue(ResidentDailyPolicy.claimModelDecision(ctx, role, at))
            assertTrue(ResidentDailyPolicy.hasBackgroundMaintenanceAllowance(ctx, role, at))
            assertTrue(ResidentDailyPolicy.claimModelDecision(ctx, role, at))
            assertFalse(ResidentDailyPolicy.hasBackgroundMaintenanceAllowance(ctx, role, at))
            assertFalse(ResidentDailyPolicy.hasBackgroundMaintenanceAllowance(ctx,
                role, at.plusSeconds(86_400)))
            assertTrue(ResidentDailyPolicy.hasModelRequestAllowance(ctx, role, at.plusSeconds(86_400)))
        } finally {
            policy.edit().clear().commit()
            daily.edit().remove("attempt:$nativeId").remove("plan:$nativeId").commit()
        }
    }

    @Test fun ordinaryResidentSocialWakePreservesTwoPaidAppointmentSlots() {
        val ctx = context()
        val policy = ctx.getSharedPreferences("lulu_resident_activity_policy_v1", Context.MODE_PRIVATE)
        val daily = ctx.getSharedPreferences("lulu_resident_daily_decisions_v1", Context.MODE_PRIVATE)
        val agendaPrefs = ctx.getSharedPreferences("lulu_role_day_plans_v1", Context.MODE_PRIVATE)
        val previousPlans = agendaPrefs.getString("plans", null)
        val residentId = "reserve-two-appointments-test"
        val role = "resident:$residentId"
        val at = Instant.parse("2026-11-08T09:00:00Z")
        try {
            policy.edit().clear().commit()
            assertTrue(ResidentDailyPolicy.setMaxModelDecisions(ctx, 3))
            val day = at.atZone(ZoneId.systemDefault()).toLocalDate()
            val slots = listOf(18, 20).map { hour ->
                day.atTime(hour, 0).atZone(ZoneId.systemDefault()).toInstant()
            }
            val twoPlans = JSONArray().apply {
                slots.forEachIndexed { index, due ->
                    put(JSONObject().put("id", "reserved-$index")
                        .put("characterId", role).put("title", "个人计划")
                        .put("intent", "本人选择").put("dueAt", due.toString())
                        .put("createdAt", at.toString()).put("status", "planned"))
                }
            }
            agendaPrefs.edit().putString("plans", twoPlans.toString()).commit()
            assertEquals(2, ResidentDailyPolicy.remainingTodayAgendaDecisions(ctx, role, at))
            assertFalse(ResidentDailyPolicy.hasOptionalResidentWakeAllowance(ctx, role, at))
            assertTrue(ResidentDailyPolicy.claimModelDecision(ctx, role, at))
            daily.edit().putString("plan:$residentId",
                JSONObject().put("date", ResidentDailyDecision.date(at)).toString()).commit()
            // One planning call has been spent: neither housekeeping nor
            // ordinary mail may consume the two remaining due-time calls.
            assertEquals(2, ResidentDailyPolicy.remainingModelRequests(ctx, role, at))
            assertFalse(ResidentDailyPolicy.hasOptionalResidentWakeAllowance(ctx, role, at))
            assertFalse(ResidentDailyPolicy.hasBackgroundMaintenanceAllowance(ctx, role, at))
            assertTrue(ResidentDailyPolicy.claimModelDecision(ctx, role, at))
            assertTrue(ResidentDailyPolicy.claimModelDecision(ctx, role, at))
            assertFalse(ResidentDailyPolicy.hasModelRequestAllowance(ctx, role, at))
        } finally {
            if (previousPlans == null) agendaPrefs.edit().remove("plans").commit()
            else agendaPrefs.edit().putString("plans", previousPlans).commit()
            daily.edit().remove("plan:$residentId").commit()
            policy.edit().clear().commit()
        }
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

    @Test fun populationSpawnAndActualResidentModelRequestsRespectOwnerLimits() {
        val ctx = context()
        val prefs = ctx.getSharedPreferences("lulu_resident_activity_policy_v1", Context.MODE_PRIVATE)
        prefs.edit().clear().commit()
        assertFalse(ResidentDailyPolicy.autoGenerationEnabled(ctx))
        assertFalse(ResidentDailyPolicy.mayAddResident(ctx, 0))
        assertTrue(ResidentDailyPolicy.setAutoGenerationEnabled(ctx, true))
        assertTrue(ResidentDailyPolicy.setMaxResidents(ctx, 1))
        assertTrue(ResidentDailyPolicy.mayAddResident(ctx, 0))
        assertFalse(ResidentDailyPolicy.mayAddResident(ctx, 1))
        assertTrue(ResidentDailyPolicy.setAutoGenerationEnabled(ctx, false))
        assertFalse(ResidentDailyPolicy.mayAddResident(ctx, 0))
        assertTrue(ResidentDailyPolicy.setMaxModelDecisions(ctx, 2))
        val role = "resident:quota-policy"
        val at = Instant.parse("2026-11-04T06:00:00Z")
        assertTrue(ResidentDailyPolicy.claimModelDecision(ctx, role, at))
        assertTrue(ResidentDailyPolicy.claimModelDecision(ctx, role, at))
        assertFalse(ResidentDailyPolicy.claimModelDecision(ctx, role, at))
        assertEquals(2, ResidentDailyPolicy.modelDecisionsUsed(ctx, role, at))
        assertTrue(ResidentDailyPolicy.claimModelDecision(ctx, "resident:another", at))
        assertTrue(ResidentDailyPolicy.claimModelDecision(ctx, role, at.plusSeconds(86_400)))
        assertTrue(ResidentDailyPolicy.setMaxModelDecisions(ctx, 0))
        assertFalse(ResidentDailyPolicy.claimModelDecision(ctx, role, at.plusSeconds(172_800)))
        prefs.edit().clear().commit()
    }

    @Test fun residentMemoryWaitsAtQuotaWithoutBuyingAnotherRequest() {
        val ctx = context()
        val prefs = ctx.getSharedPreferences("lulu_resident_activity_policy_v1", Context.MODE_PRIVATE)
        val daily = ctx.getSharedPreferences("lulu_resident_daily_decisions_v1", Context.MODE_PRIVATE)
        prefs.edit().clear().commit()
        val nativeId = "memory-quota"
        try {
            val at = Instant.parse("2026-11-05T04:00:00Z")
            val role = "resident:$nativeId"
            assertTrue(ResidentDailyPolicy.setMaxModelDecisions(ctx, 1))
            // No daily plan yet: memory must leave the only paid call for planning.
            assertTrue(residentMemoryBudgetDepleted(ctx, role, at))
            assertTrue(ResidentDailyPolicy.hasModelRequestAllowance(ctx, role, at))
            daily.edit().putString("plan:$nativeId",
                JSONObject().put("date", ResidentDailyDecision.date(at)).toString()).commit()
            assertFalse(residentMemoryBudgetDepleted(ctx, role, at))
            assertTrue(ResidentDailyPolicy.claimModelDecision(ctx, role, at))
            assertTrue(residentMemoryBudgetDepleted(ctx, role, at))
            assertEquals(1, ResidentDailyPolicy.modelDecisionsUsed(ctx, role, at))
            assertFalse(ResidentDailyPolicy.claimModelDecision(ctx, role, at))
            // The local deferral is a view on quota, not a new paid claim.
            assertEquals(1, ResidentDailyPolicy.modelDecisionsUsed(ctx, role, at))
            val tomorrow = at.plusSeconds(86400)
            assertTrue(residentMemoryBudgetDepleted(ctx, role, tomorrow))
            daily.edit().putString("plan:$nativeId",
                JSONObject().put("date", ResidentDailyDecision.date(tomorrow)).toString()).commit()
            assertFalse(residentMemoryBudgetDepleted(ctx, role, tomorrow))
            assertFalse(residentMemoryBudgetDepleted(ctx, "protagonist", at))
            assertTrue(ResidentDailyPolicy.setMaxModelDecisions(ctx, 0))
            assertTrue(residentMemoryBudgetDepleted(ctx, role, tomorrow))
        } finally {
            daily.edit().remove("plan:$nativeId").commit()
            prefs.edit().clear().commit()
        }
    }

    @Test fun dailyPlannerOffersOnlyFutureDecisionsCoveredByRemainingPaidCalls() {
        // The daily plan itself needs one paid call. One call may also include
        // many immediate local steps; only future agenda wakes cost more calls.
        assertEquals(0, ResidentDailyPolicy.affordableNewAgendaItems(5, 0))
        assertEquals(0, ResidentDailyPolicy.affordableNewAgendaItems(5, 1))
        assertEquals(1, ResidentDailyPolicy.affordableNewAgendaItems(5, 2))
        assertEquals(2, ResidentDailyPolicy.affordableNewAgendaItems(5, 4, pendingDueDecisions = 1))
        assertEquals(0, ResidentDailyPolicy.affordableNewAgendaItems(5, 2, pendingDueDecisions = 1))
        assertEquals(0, ResidentDailyPolicy.affordableNewAgendaItems(0, 8))
        assertEquals(3, ResidentDailyPolicy.affordableNewAgendaItems(3, 8))
        val ctx = context()
        val prefs = ctx.getSharedPreferences("lulu_resident_activity_policy_v1", Context.MODE_PRIVATE)
        prefs.edit().clear().commit()
        try {
            val role = "resident:planning-budget"
            val now = Instant.parse("2026-11-05T05:00:00Z")
            assertTrue(ResidentDailyPolicy.setMaxModelDecisions(ctx, 2))
            assertEquals(2, ResidentDailyPolicy.remainingModelRequests(ctx, role, now))
            assertTrue(ResidentDailyPolicy.claimModelDecision(ctx, role, now))
            assertEquals(1, ResidentDailyPolicy.remainingModelRequests(ctx, role, now))
            assertTrue(ResidentDailyPolicy.claimModelDecision(ctx, role, now))
            assertEquals(0, ResidentDailyPolicy.remainingModelRequests(ctx, role, now))
            assertFalse(ResidentDailyPolicy.hasModelRequestAllowance(ctx, role, now))
            assertEquals(0, ResidentDailyPolicy.affordableNewAgendaItems(5,
                ResidentDailyPolicy.remainingModelRequests(ctx, role, now)))
            assertEquals(2, ResidentDailyPolicy.remainingModelRequests(ctx,
                role, ResidentDailyPolicy.nextModelBudgetDayStart(now)))
            assertEquals(Int.MAX_VALUE,
                ResidentDailyPolicy.remainingModelRequests(ctx, "not-a-resident", now))
        } finally { prefs.edit().clear().commit() }
    }

    @Test fun nextResidentBudgetWindowUsesRealLocalMidnightNotRolling24Hours() {
        val zone = ZoneId.systemDefault()
        val original = java.time.LocalDate.of(2026, 11, 5)
            .atTime(23, 58).atZone(zone).toInstant()
        val expected = java.time.LocalDate.of(2026, 11, 6)
            .atStartOfDay(zone).toInstant()
        assertEquals(expected, ResidentDailyPolicy.nextModelBudgetDayStart(original))
        assertTrue(ResidentDailyPolicy.nextModelBudgetDayStart(original).isAfter(original))
        assertTrue(ResidentDailyPolicy.nextModelBudgetDayStart(original)
            .isBefore(original.plusSeconds(24 * 3600)))
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
}
