package com.jiacimu.lulu.data

import kotlinx.coroutines.runBlocking
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import java.time.Instant
import java.util.UUID

@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE, sdk = [28])
class ResidentPlanRevisionTest {
    @org.junit.Before fun workerHost() {
        val app = RuntimeEnvironment.getApplication() as android.content.Context
        if (runCatching { androidx.work.WorkManager.getInstance(app) }.isFailure)
            androidx.work.WorkManager.initialize(app, androidx.work.Configuration.Builder().build())
        initializeBackgroundRuntime(app)
    }

    private fun choice(tool: String, args: JSONObject) = JSONObject().put("choice", "wait")
        .put("reason", "还想等朋友的回复").put("steps", JSONArray().put(
            JSONObject().put("tool", tool).put("args", args)))

    @Test fun ordinaryWakeCanReviseItsOwnWaitingPlanAndStaleChoiceCannotOverwriteIt() = runBlocking {
        val app = RuntimeEnvironment.getApplication() as android.content.Context
        val role = "resident:plan-review-${UUID.randomUUID()}"
        val now = Instant.now()
        val plan = RoleDayScheduleStore.arrange(app, role, "和朋友再谈", "等待来信",
            now.plusSeconds(7200), now, activityAction = "publish_moment",
            activityArgs = JSONObject().put("text", "交流后的感受"))
        val waiting = RoleDayScheduleStore.reschedule(app, role, plan.id,
            now.plusSeconds(9000), "等待复议：朋友回复后再想", now)
        val context = RoleDayScheduleStore.plannedContext(app, role, now)
        assertTrue(context.contains("朋友回复后再想"))
        assertTrue(context.contains("expectedDueAt=${waiting.dueAt}"))
        assertFalse(RoleDayScheduleStore.plannedContext(app, "other-$role", now).contains(plan.id))
        assertTrue(CapabilityRegistry.allows(app, role,
            CapabilityRegistry.find("reschedule_personal_activity")!!, false))
        assertFalse(CapabilityRegistry.allows(app, role,
            CapabilityRegistry.find("capture_screen")!!, false))
        val earlier = now.plusSeconds(600)
        val args = JSONObject().put("planId", plan.id).put("expectedDueAt", waiting.dueAt.toString())
            .put("dueAt", earlier.toString()).put("reason", "看到朋友真实来信，想提前再考虑")
        val decision = choice("reschedule_personal_activity", args)
        assertNull(ResidentDailyDecision.dueContractError(decision, now))
        assertEquals(1, ResidentDailyDecision.executeWakeSteps(app, role, decision, "review-$role"))
        assertEquals(earlier, RoleDayScheduleStore.plans(app, role).single { it.id == plan.id }.dueAt)
        // Different request with old original version must fail, not move/cancel the new slot.
        assertEquals(0, ResidentDailyDecision.executeWakeSteps(app, role,
            choice("cancel_personal_activity", args), "stale-cancel-$role"))
        assertEquals("planned", RoleDayScheduleStore.plans(app, role).single { it.id == plan.id }.status)
        assertEquals(0, ResidentDailyDecision.executeWakeSteps(app, "other-$role",
            decision, "foreign-$role"))
        RoleDayScheduleStore.remove(app, role, plan.id)
        Unit
    }

    @Test fun managementRequiresOriginalVersionAndCannotReplaceCurrentPrimaryChoice() {
        val now = Instant.now()
        val args = JSONObject().put("planId", "current").put("reason", "改")
            .put("dueAt", now.plusSeconds(600).toString())
        assertTrue(ResidentDailyDecision.dueContractError(
            choice("reschedule_personal_activity", args), now)!!.contains("expectedDueAt"))
        args.put("expectedDueAt", now.plusSeconds(7200).toString())
        assertNotNull(ResidentDailyDecision.dueContractError(
            choice("reschedule_personal_activity", args), now, "current"))
        assertNull(ResidentDailyDecision.dueContractError(
            choice("reschedule_personal_activity", args), now, "another"))
        assertNotNull(ResidentDailyDecision.dueContractError(
            choice("publish_moment", JSONObject().put("planId", "current").put("text", "不能冒充原计划")), now))
    }
}
