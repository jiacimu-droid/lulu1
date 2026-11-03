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
