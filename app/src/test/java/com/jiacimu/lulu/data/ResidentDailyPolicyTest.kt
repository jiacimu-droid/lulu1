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
        prefs.edit().clear().commit()
        try {
            val at = Instant.parse("2026-11-05T04:00:00Z")
            val role = "resident:memory-quota"
            assertTrue(ResidentDailyPolicy.setMaxModelDecisions(ctx, 1))
            assertFalse(residentMemoryBudgetDepleted(ctx, role, at))
            assertTrue(ResidentDailyPolicy.hasModelRequestAllowance(ctx, role, at))
            assertTrue(ResidentDailyPolicy.claimModelDecision(ctx, role, at))
            assertTrue(residentMemoryBudgetDepleted(ctx, role, at))
            assertEquals(1, ResidentDailyPolicy.modelDecisionsUsed(ctx, role, at))
            assertFalse(ResidentDailyPolicy.claimModelDecision(ctx, role, at))
            // The local deferral is a view on quota, not a new paid claim.
            assertEquals(1, ResidentDailyPolicy.modelDecisionsUsed(ctx, role, at))
            assertFalse(residentMemoryBudgetDepleted(ctx, role, at.plusSeconds(86400)))
            assertFalse(residentMemoryBudgetDepleted(ctx, "protagonist", at))
            assertTrue(ResidentDailyPolicy.setMaxModelDecisions(ctx, 0))
            assertTrue(residentMemoryBudgetDepleted(ctx, role, at.plusSeconds(86400)))
        } finally {
            prefs.edit().clear().commit()
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
}
